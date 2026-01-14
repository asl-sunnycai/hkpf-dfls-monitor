@echo off
:: 切換到當前腳本所在的目錄 (確保能讀取到 conf 文件夾)
cd /d "%~dp0"

echo Starting DFLS Monitor...
:: 運行 jar 包
java -jar dfls-monitor.jar

:: 如果程序意外崩潰，暫停窗口以便查看錯誤
pause