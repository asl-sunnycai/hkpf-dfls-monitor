package com.example;

import javax.net.ssl.*;
import javax.mail.*;
import javax.mail.internet.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

public class DflsMonitor {

    private static final Logger logger = Logger.getLogger(DflsMonitor.class.getName());
    private static Properties config = new Properties();

    private static final String ERROR_LOG_FILE = "/opt/dfls/log/ppms/ppms.log";
    
    // 狀態變量
    private static int consecutiveFailures = 0;
    private static long lastAlertTime = 0;

    public static void main(String[] args) {
        setupLogging();
        loadConfig();
		
		// 忽略 SSL 證書驗證 (針對內部自簽名證書)
        disableSSLVerification();

        logger.info("DFLS Monitor started.");
        
        int interval = Integer.parseInt(config.getProperty("check.interval.seconds", "60"));

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // 執行定時任務
        scheduler.scheduleAtFixedRate(DflsMonitor::performCheck, 0, interval, TimeUnit.SECONDS);
    }

    private static void performCheck() {
        String targetUrl = config.getProperty("target.url");
        logger.info("performCheck: " + targetUrl);
        try {
            boolean isHealthy = checkUrl(targetUrl);
            logger.info("isHealthy: " + isHealthy);
            if (isHealthy) {
                if (consecutiveFailures > 0) {
                    logger.info("Target recovered. Counter reset.");
                }
                consecutiveFailures = 0;
                // 恢復後可選擇是否重置 lastAlertTime，這裡選擇不重置以便邏輯簡單
            } else {
                handleFailure();
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error during check execution: " + e.getMessage(), e);
            handleFailure(); // 異常也視為失敗
        }
    }

    private static boolean checkUrl(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            // 使用 HEAD 請求，只獲取標頭，不下載內容，節省帶寬
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000); // 5秒連接超時
            connection.setReadTimeout(5000);    // 5秒讀取超時

            int responseCode = connection.getResponseCode();
            logger.fine("Check URL: " + urlString + " | Response: " + responseCode);

            // 200 OK 代表文件存在且可訪問
            return responseCode == 200;

        } catch (IOException e) {
            logger.warning("Network check failed: " + e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void handleFailure() {
        consecutiveFailures++;
        int threshold = Integer.parseInt(config.getProperty("fail.threshold", "3"));
        int resendMinutes = Integer.parseInt(config.getProperty("resend.interval.minutes", "60"));

        logger.warning("Check failed. Consecutive failures: " + consecutiveFailures);

        if (consecutiveFailures >= threshold) {
            long currentTime = System.currentTimeMillis();
            // 判斷是否需要發送郵件：
            // 1. 剛好達到閾值
            // 2. 或者 (已達到閾值 且 距離上次發送超過了重發間隔)
            if (consecutiveFailures == threshold || 
               (currentTime - lastAlertTime > (long) resendMinutes * 60 * 1000)) {
                
                sendAlertEmail(consecutiveFailures);
                lastAlertTime = currentTime;
            }
        }
    }

    private static void sendAlertEmail(int failCount) {
        String to = config.getProperty("mail.to");
        String from = config.getProperty("mail.from");
        String host = config.getProperty("mail.smtp.host");
        String port = config.getProperty("mail.smtp.port", "25");
        String subject = config.getProperty("mail.subject");
        String url = config.getProperty("target.url");

        logger.info("Attempting to send alert email to " + to);

        // 創建錯誤信息內容
        String errorMessage = String.format(
                "[%s] Error occurred in DFLS Server", new Date().toString()
        );

        Properties mailProps = new Properties();
        mailProps.put("mail.smtp.host", host);
        mailProps.put("mail.smtp.port", port);
        
        // 判斷是否需要 Auth
        String user = config.getProperty("mail.user");
        String pass = config.getProperty("mail.password");
        boolean auth = user != null && !user.isEmpty();
        mailProps.put("mail.smtp.auth", auth ? "true" : "false");

        Session session;
        if (auth) {
            session = Session.getInstance(mailProps, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass);
                }
            });
        } else {
            session = Session.getInstance(mailProps);
        }

        try {
            // 1. 記錄錯誤到專門的錯誤日誌文件
            logErrorToFile(errorMessage);

            // 2. 發送郵件
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
			// 支持單個收件人
            // message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
			
			// 支持多個收件人，用逗號分隔
			String[] recipients = to.split(",");
			InternetAddress[] toAddresses = new InternetAddress[recipients.length];
			for (int i = 0; i < recipients.length; i++) {
				toAddresses[i] = new InternetAddress(recipients[i].trim());
			}
			message.setRecipients(Message.RecipientType.TO, toAddresses);
			
            message.setSubject(subject);
            message.setSentDate(new Date());

            String body = "Alert: The DFLS Portal check has failed.\n\n" +
                          "Target URL: " + url + "\n" +
                          "Consecutive Failures: " + failCount + "\n" +
                          "Time: " + new Date().toString() + "\n\n" +
                          "Please check the server status.";
            message.setText(body);

            Transport.send(message);
            logger.info("Alert email sent successfully.");

        } catch (MessagingException e) {
            logger.log(Level.SEVERE, "Failed to send email", e);
        }
    }

    // 新增方法：將錯誤記錄到文件
    private static void logErrorToFile(String message) {
        try {
            // 確保日誌目錄存在
            File logFile = new File(ERROR_LOG_FILE);
            File parentDir = logFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 使用FileWriter以追加模式寫入日誌
            try (FileWriter writer = new FileWriter(ERROR_LOG_FILE, true)) {
                writer.write(message + "\n");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to write to error log file: " + ERROR_LOG_FILE, e);
        }
    }

    // --- 輔助方法：加載配置與日誌 ---

    private static void loadConfig() {
        // 優先讀取 jar 同級目錄下的 conf/config.properties
        File configFile = new File("conf" + File.separator + "config.properties");
        if (!configFile.exists()) {
             // 為了方便測試，如果外部沒有，嘗試讀取項目根目錄（IDE環境）
            configFile = new File("config.properties");
        }
        
        try (FileInputStream input = new FileInputStream(configFile)) {
            config.load(input);
        } catch (IOException ex) {
            logger.severe("Could not load config file from " + configFile.getAbsolutePath());
            System.exit(1);
        }
    }

    private static void setupLogging() {
        try {
            // 確保 logs 目錄存在
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdir();
            }

            // 設置日誌輸出到 logs/app.log，限制大小 10MB，保留 5 個文件，追加模式
            FileHandler fileHandler = new FileHandler("logs/app.log", 1024 * 1024 * 10, 5, true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
            
            // 同時保留 Console 輸出
            logger.setLevel(Level.INFO);
            
        } catch (IOException e) {
            System.err.println("Failed to setup logger: " + e.getMessage());
        }
    }
	
	// 用於繞過 HTTPS 證書驗證的輔助方法 (內部環境常用)
    private static void disableSSLVerification() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }};
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}