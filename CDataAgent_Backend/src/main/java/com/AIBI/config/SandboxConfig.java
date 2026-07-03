package com.AIBI.config;

import com.alibaba.cloud.ai.sandbox.RuntimeFunctionToolCallback;
import com.alibaba.cloud.ai.sandbox.SandboxAwareTool;
import com.alibaba.cloud.ai.sandbox.ToolkitInit;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.manager.ManagerConfig;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClientStarter;
import io.agentscope.runtime.sandbox.manager.fs.local.LocalFileSystemConfig;
import io.agentscope.runtime.sandbox.manager.model.container.PortRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * AgentScope 沙箱配置（单实例 + 健康检查 + 自动重建）。
 * <p>
 * 启动时创建 1 个 Docker 沙箱容器（基于 runtime-sandbox-base），
 * 通过 @Scheduled 每 60s 健康检查，失效时自动重建并热替换到工具回调中。
 * 仅在 sandbox.enabled=true 时启用。
 * </p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sandbox.enabled", havingValue = "true", matchIfMissing = true)
public class SandboxConfig {

    @Value("${sandbox.mount-dir:/data/sandbox-workspace}")
    private String mountDir;

    @Value("${sandbox.execution-timeout:60}")
    private int executionTimeout;

    /** 数据分析包列表（沙箱容器启动后自动安装） */
    private static final String[] DATA_PACKAGES = {
            "pandas", "scipy", "scikit-learn"
    };

    /** 标记包是否已安装 */
    private volatile boolean packagesInstalled = false;

    private SandboxService sandboxService;

    /** 当前沙箱实例，健康检查失败时重建 */
    private volatile BaseSandbox sandbox;

    /** 工具函数引用，可通过 setSandbox() 热替换沙箱（无需重建整个 ToolCallback） */
    private SandboxAwareTool<?, ?> sandboxTool;

    @Bean(destroyMethod = "close")
    public SandboxService sandboxService() {
        // 使用默认 Docker 客户端（Unix socket），不设置自定义 host 避免 tcp:// 前缀拼接问题
        DockerClientStarter dockerStarter = DockerClientStarter.builder().build();

        ManagerConfig config = ManagerConfig.builder()
                .portRange(new PortRange(11000, 12000))
                .clientStarter(dockerStarter)
                .build();

        this.sandboxService = new SandboxService(config);
        this.sandboxService.start();
        log.info("SandboxService 已启动");
        return this.sandboxService;
    }

    @Bean
    @DependsOn("sandboxService")
    public ToolCallback sandboxCallback() {
        this.sandbox = createSandbox();

        ToolCallback callback = ToolkitInit.RunPythonCodeTool(this.sandbox);
        if (callback instanceof RuntimeFunctionToolCallback) {
            this.sandboxTool = ((RuntimeFunctionToolCallback) callback).getToolFunction();
            log.info("沙箱自愈机制已就绪");
        } else {
            log.warn("无法获取 SandboxAwareTool，沙箱自愈功能不可用");
        }

        log.info("沙箱工具回调已注册");
        return callback;
    }

    /**
     * 定时沙箱健康检查（默认 60 秒间隔）。
     * 首次执行时同时触发数据分析包安装。
     */
    @Scheduled(fixedDelayString = "${sandbox.health-check-interval:60000}",
               initialDelayString = "${sandbox.health-check-interval:60000}")
    public void healthCheck() {
        if (sandbox == null || sandbox.isClosed()) {
            if (sandbox != null) {
                log.warn("沙箱已关闭，触发自动重建");
                rebuildSandbox();
            }
            return;
        }

        // 首次健康检查：强制容器初始化 + 安装数据分析包
        try {
            if (!packagesInstalled) {
                installDataPackages();
            } else {
                // 常规探活
                sandbox.runShellCommand("python3 -c \"print('__sandbox_health_ok__')\"");
                log.debug("沙箱健康检查通过");
            }
        } catch (Exception e) {
            log.warn("沙箱健康检查失败 ({}), 触发自动重建", e.getMessage());
            rebuildSandbox();
        }
    }

    /**
     * 安装 Python 数据分析包（pandas/scipy/sklearn）。
     * 利用框架的 runShellCommand 触发容器懒惰初始化后安装。
     */
    private void installDataPackages() {
        String pkgs = String.join(" ", DATA_PACKAGES);
        log.info("沙箱首次初始化，安装数据分析包: {}", pkgs);

        // runShellCommand 会触发容器创建（懒惰初始化），第一次调用较慢
        String output = sandbox.runShellCommand(
                "/agentscope_runtime/venv/bin/pip3 install --quiet " + pkgs);
        log.info("pip3 install 输出: {}", output != null ? output.substring(0, Math.min(output.length(), 500)) : "null");

        // 验证安装
        String verifyOutput = sandbox.runShellCommand(
                "/agentscope_runtime/venv/bin/python3 -c \"import pandas, scipy, sklearn; print('pkg_ok')\"");
        log.info("包验证输出: {}", verifyOutput);

        packagesInstalled = true;
        log.info("沙箱数据分析包安装完成");
    }

    /**
     * 重建沙箱：关闭旧容器 → 创建新容器 → 热替换到工具引用中。
     */
    private synchronized void rebuildSandbox() {
        BaseSandbox oldSandbox = this.sandbox;

        try {
            BaseSandbox newSandbox = createSandbox();
            if (sandboxTool != null) {
                sandboxTool.setSandbox(newSandbox);
            }
            this.sandbox = newSandbox;
            this.packagesInstalled = false; // 新沙箱需重新安装包
            log.info("沙箱自动重建完成 ({} → {})",
                    oldSandbox != null ? oldSandbox.getSandboxId() : "null",
                    newSandbox.getSandboxId());
        } catch (Exception e) {
            log.error("沙箱重建失败", e);
            return;
        }

        // 旧沙箱延迟关闭，避免影响正在执行的 call()
        if (oldSandbox != null) {
            try {
                oldSandbox.close();
            } catch (Exception e) {
                log.warn("关闭旧沙箱异常: {}", e.getMessage());
            }
        }
    }

    private BaseSandbox createSandbox() {
        LocalFileSystemConfig fsConfig = LocalFileSystemConfig.builder()
                .mountDir(mountDir).build();
        BaseSandbox newSandbox = new BaseSandbox(sandboxService, "cdata-system", "analysis", fsConfig);
        log.info("创建 BaseSandbox, mountDir={}", mountDir);
        return newSandbox;
    }

    public int getExecutionTimeoutSeconds() {
        return executionTimeout;
    }

    public BaseSandbox getSandbox() {
        return sandbox;
    }
}
