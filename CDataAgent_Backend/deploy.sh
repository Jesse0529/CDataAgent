#!/bin/bash
# CData Agent Docker 部署脚本
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
    echo "未检测到 Docker，请先安装 Docker。"
    exit 1
fi

if [ ! -f .env ]; then
    cp .env.example .env
    echo "已创建 .env。请填写 MODEL_ENCRYPTION_KEY 后重新执行部署。"
    exit 1
fi

if ! grep -qE '^MODEL_ENCRYPTION_KEY=.+$' .env; then
    echo "MODEL_ENCRYPTION_KEY 未设置。请在 .env 中填写随机高强度密钥后重新执行部署。"
    exit 1
fi

docker compose config --quiet
docker compose down
docker compose up -d --build
docker compose ps

echo "部署完成。"
echo "应用默认仅监听本机地址：http://localhost:${SERVER_PORT:-9999}"
echo "查看日志：docker compose logs -f app"
