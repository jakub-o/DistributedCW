package com.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.sql.*;
import java.util.logging.Logger;

public class FraudDetectionFunction {

    @FunctionName("FraudDetection")
    public void detectFraud(
        @TimerTrigger(name = "fraudDetectionTrigger", schedule = "*/5 * * * * *") String timerInfo,
        final ExecutionContext context) {

        Logger logger = context.getLogger();
        logger.info("Fraud detection function triggered.");

        // Database connection details
        String jdbcUrl = System.getenv("SQL_CONNECTION_STRING");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        double thresholdAmount = 10000; // Threshold for high-value transactions

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
            PreparedStatement selectStmt = conn.prepareStatement(
                "SELECT id, amount, sender_id, receiver_id, timestamp FROM transactions " +
                "WHERE timestamp > DATEADD(SECOND, -5, GETDATE()) AND fraud_flag = 0");
            PreparedStatement updateStmt = conn.prepareStatement(
                "UPDATE transactions SET fraud_flag = 1 WHERE id = ?")) {

            ResultSet rs = selectStmt.executeQuery();

            while (rs.next()) {
                double amount = rs.getDouble("amount");
                String senderId = rs.getString("sender_id");
                String receiverId = rs.getString("receiver_id");
                Timestamp timestamp = rs.getTimestamp("timestamp");
                int transactionId = rs.getInt("id");

                if (amount > thresholdAmount) {
                    logger.warning("High-value transaction detected. Sender: " + senderId +
                                ", Receiver: " + receiverId + ", Amount: " + amount +
                                ", Timestamp: " + timestamp);

                    // Mark this transaction as processed for fraud
                    updateStmt.setInt(1, transactionId);
                    updateStmt.executeUpdate();
                }

                logger.info("Checked transaction for fraud. Sender: " + senderId + ", Amount: " + amount);
            }

        } catch (SQLException e) {
            logger.severe("Database connection error during fraud detection: " + e.getMessage());
        }
    }
}
