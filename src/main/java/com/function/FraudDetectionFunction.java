package com.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.sql.*;
import java.util.logging.Logger;

public class FraudDetectionFunction {

    @FunctionName("FraudDetection")
    public void processTransaction(
        @QueueTrigger(name = "transactionQueue", queueName = "transactionqueue", connection = "AzureWebJobsStorage")
        String transactionMessage,
        final ExecutionContext context) {

        Logger logger = context.getLogger();
        logger.info("Processing transaction for fraud detection: " + transactionMessage);

        // Parse the transaction message
        String[] transactionParts = transactionMessage.split(",");
        String senderId = transactionParts[0];
        String receiverId = transactionParts[1];
        double amount = Double.parseDouble(transactionParts[2]);

        double thresholdAmount = 10000; // Threshold for high-value transactions
        String jdbcUrl = System.getenv("SQL_CONNECTION_STRING");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        // Insert transaction and perform fraud detection
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            // Insert transaction into the database
            String insertSql = "INSERT INTO transactions (sender_id, receiver_id, amount, timestamp) VALUES (?, ?, ?, GETDATE())";
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                insertStmt.setString(1, senderId);
                insertStmt.setString(2, receiverId);
                insertStmt.setDouble(3, amount);
                insertStmt.executeUpdate();

                logger.info("Transaction inserted successfully for sender: " + senderId + ", amount: " + amount);
            }

            // Fraud detection logic
            if (amount > thresholdAmount) {
                logger.warning("High-value transaction detected. Sender: " + senderId +
                        ", Receiver: " + receiverId + ", Amount: " + amount);

                // Mark this transaction as potentially fraudulent
                String updateSql = "UPDATE transactions SET fraud_flag = 1 WHERE sender_id = ? AND receiver_id = ? AND amount = ?";
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, senderId);
                    updateStmt.setString(2, receiverId);
                    updateStmt.setDouble(3, amount);
                    updateStmt.executeUpdate();

                    logger.info("Fraudulent transaction marked for sender: " + senderId + ", amount: " + amount);
                }
            }

        } catch (SQLException e) {
            logger.severe("Database error during transaction processing: " + e.getMessage());
        }
    }
}
