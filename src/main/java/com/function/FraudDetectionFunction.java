package com.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.sql.*;
import java.util.logging.Logger;

public class FraudDetectionFunction {

    @FunctionName("FraudDetection")
    public void detectFraud(
        @TimerTrigger(name = "fraudDetectionTrigger", schedule = "*/5 * * * * *") // Runs every 5 seconds for demo
        String timerInfo,
        final ExecutionContext context) {

        Logger logger = context.getLogger();
        logger.info("Fraud detection function triggered.");

        // Database connection details
        String jdbcUrl = System.getenv("JDBC_URL");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM transactions WHERE timestamp > DATEADD(SECOND, -5, GETDATE())")) {

            // Check recent transactions
            while (rs.next()) {
                double amount = rs.getDouble("amount");
                String senderId = rs.getString("sender_id");

                // Basic rule-based fraud detection: flag high-value transactions
                if (amount > 10000) {
                    logger.warning("High-value transaction detected: " + amount);
                }

                // Additional checks can be added here (e.g., frequency analysis)

                logger.info("Checked transaction for fraud: " + senderId + ", Amount: " + amount);
            }

        } catch (SQLException e) {
            logger.severe("Database connection error during fraud detection: " + e.getMessage());
        }
    }
}
