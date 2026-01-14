#!/bin/bash

# --- 配置 ---
APP_DIR="/opt/dfls/dfls-monitor/bin"
JAR_NAME="dfls-monitor.jar"

echo "=================================================="
echo "Restarting DFLS Monitor..."
echo "=================================================="

# 1. 停止舊進程
# 獲取 PID
PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')

if [ -n "$PID" ]; then
    echo "[STOP] Found process $PID. Killing it..."
    pkill -9 -f dfls-monitor.jar
    sleep 2 # 等待操作系統釋放資源
    
    # 再次確認是否真的殺掉了
    if ps -p $PID > /dev/null; then
        echo "[ERROR] Failed to kill process. Please check permissions."
        exit 1
    else
        echo "[STOP] Process killed successfully."
    fi
else
    echo "[STOP] No running process found."
fi

# 2. 啟動新進程
echo "[START] Starting new process..."

cd $APP_DIR

# 這裡的啟動命令必須和 keep_alive.sh 裡的一模一樣
nohup java -jar $JAR_NAME > /dev/null 2>&1 &

sleep 2

# 3. 驗證結果
NEW_PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')

if [ -n "$NEW_PID" ]; then
    echo "=================================================="
    echo "✅ RESTART SUCCESSFUL!"
    echo "New PID: $NEW_PID"
    echo "You can check logs at: $APP_DIR/logs/app.log"
    echo "=================================================="
else
    echo "=================================================="
    echo "❌ RESTART FAILED!"
    echo "Process did not start. Check logs/startup.log or try running manually."
    echo "=================================================="
fi