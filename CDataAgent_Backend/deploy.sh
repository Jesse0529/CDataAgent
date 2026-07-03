#!/bin/bash
# ============================================================
# CData Agent Backend — Docker 部署脚本 (Linux)
# 使用: chmod +x deploy.sh && ./deploy.sh
# ============================================================
set -e

echo "🚀 开始部署 CData Agent Backend..."

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "❌ 未安装 Docker，请先安装"
    exit 1
fi

# 检查 .env 文件
if [ ! -f .env ]; then
    echo "⚠️  未找到 .env 文件"
    if [ -f .env.example ]; then
        cp .env.example .env
        echo "📝 已从 .env.example 创建 .env，请填入真实的 DEEPSEEK_API_KEY 后重新运行"
    else
        echo "DEEPSEEK_API_KEY=sk-your-key-here" > .env
        echo "📝 已创建 .env，请填入真实的 DEEPSEEK_API_KEY 后重新运行"
    fi
    exit 1
fi

# 创建宿主机目录
sudo mkdir -p /data/sandbox-workspace
sudo chmod 777 /data/sandbox-workspace

# 停止旧容器
echo "🛑 停止旧容器..."
docker compose down 2>/dev/null || true

# 构建并启动
echo "🔨 构建镜像并启动服务..."
docker compose up -d --build

# 等待启动
echo "⏳ 等待服务就绪..."
sleep 10

# 查看状态
echo ""
echo "📊 容器状态:"
docker compose ps

echo ""
echo "✅ 部署完成！"
echo "   应用地址:  http://<服务器IP>:9999"
echo "   RabbitMQ:  http://<服务器IP>:15672 (guest/guest)"
echo ""
echo "   查看日志:  docker compose logs -f app"
echo "   停止服务:  docker compose down"
