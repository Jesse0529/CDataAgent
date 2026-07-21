package com.AIBI.service;

import com.AIBI.model.vo.MessageVO;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Agent 对话服务。
 * Agent 对话服务。
 * <p>
 * 单对话模式：系统自动创建唯一对话，前端通过 conversationId 查询历史。
 * <p>
 * 流式事件类型：
 * <ul>
 *   <li>{type:"message", data:"文本块"} — 纯分析文本</li>
 *   <li>{type:"status", data:"状态文本"} — 阶段状态更新</li>
 *   <li>{type:"chart", data:"[{选项JSON}]"} — 图表配置数组</li>
 * </ul>
 */
public interface AgentService {

    /**
     * 执行 Agent 对话（流式），无文件绑定。
     * 返回 Flux<Map>，每个元素为 {type, data} 结构事件。
     */
    Flux<Map<String, String>> chatStream(String userMessage);

    /**
     * 执行 Agent 对话（流式），绑定指定数据文件。
     * @param userMessage 用户消息文本
     * @param fileIds 本次消息绑定的数据文件 ID 列表（旧调用方为空时沿用已有上下文）
     */
    Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds);

    /**
     * 执行 Agent 对话（流式），绑定指定数据文件，支持协议协商。
     * @param userMessage 用户消息文本
     * @param fileIds 本次消息绑定的数据文件 ID 列表
     * @param renderProtocol 前端渲染协议（null = 旧协议, "render-document.v1" = 新协议）
     */
    Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds, String renderProtocol);

    /**
     * 使用调用方分配的运行标识执行流。runId 是事件恢复和状态隔离的主键。
     */
    Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds,
                                         String renderProtocol, String runId);

    /**
     * 显式文件范围版本。范围为空时表示本轮不允许沿用历史数据文件。
     */
    Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds,
                                         String renderProtocol, String runId,
                                         boolean explicitFileScope);

    /**
     * 获取对话的消息历史（按时间正序）。
     */
    List<MessageVO> getConversationMessages(Long conversationId);

    /**
     * 获取指定对话中所有含图表（chartOption 不为空）的消息。
     */
    List<MessageVO> getChartMessages(Long conversationId);

    /**
     * 获取当前默认对话 ID（不存在时自动创建）。
     */
    Long getOrCreateDefaultConversation();

    /**
     * 清空指定对话的所有聊天消息，不影响工作记忆。
     */
    void deleteMessages(Long conversationId);

    /**
     * 重置指定对话 — 清除所有消息、Redis checkpoints、分析状态和服务缓存。
     * <p>
     * 对话本身保留不变，重置后可继续在该对话中发送消息。
     * 对话绑定的数据文件不受影响。
     *
     * @param conversationId 对话 ID
     */
    void resetConversation(Long conversationId);

}
