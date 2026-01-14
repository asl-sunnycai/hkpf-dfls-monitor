#!/bin/bash

# --- 1. 定義路徑 ---
APP_DIR="/opt/dfls/dfls-monitor/bin"
JAR_NAME="dfls-monitor.jar"
LOG_FILE="$APP_DIR/logs/startup.log"

# 重要：Crontab 環境通常沒有 Java 環境變量，建議手動指定
# 如果你的 java 命令可以直接運行，可以註釋掉下面這行
# export JAVA_HOME=/usr/java/jdk1.8.0_xxx
# export PATH=$JAVA_HOME/bin:$PATH

# --- 2. 檢測邏輯 ---
# pgrep -f 會搜尋包含 jar 名字的進程
if pgrep -f "$JAR_NAME" > /dev/null
then
    # 情況 A：進程存在，不做任何事
    # echo "Service is running." # (Debug用，平時註釋掉以免產生垃圾郵件)
    exit 0
else
    # 情況 B：進程不存在，啟動它
    echo "$(date) - Service NOT running. Starting..." >> $LOG_FILE
    
    cd $APP_DIR
    
    # --- 3. 啟動命令 ---
    # nohup: 讓程序忽略掛斷信號（後台運行）
    # > /dev/null 2>&1: 把標準輸出和錯誤丟棄（因為 Java 內部已有 Log 邏輯）
    # &: 在後台執行
    nohup java -jar $JAR_NAME > /dev/null 2>&1 &
    
    echo "$(date) - Start command executed." >> $LOG_FILE
fi