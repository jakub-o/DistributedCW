package com.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.sql.*;
import java.util.Map;
import java.util.Optional;

public class TransactionLoggingFunction {

    @FunctionName("TransactionLogging")
    public HttpResponseMessage logTransaction(
        @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.FUNCTION)
        HttpRequestMessage<Optional<Map<String, Object>>> request,
        final ExecutionContext context) {

        // Extract transaction data from the request body
        Map<String, Object> transactionData = request.getBody().orElse(null);
        if (transactionData == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid transaction data").build();
        }

        double amount = Double.parseDouble(transactionData.get("amount").toString());
        String senderId = transactionData.get("sender_id").toString();
        String receiverId = transactionData.get("receiver_id").toString();
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());

        // Database connection details
        String jdbcUrl = System.getenv("JDBC_URL");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO transactions (amount, sender_id, receiver_id, timestamp) VALUES (?, ?, ?, ?)")) {

            // Insert the transaction into the database
            stmt.setDouble(1, amount);
            stmt.setString(2, senderId);
            stmt.setString(3, receiverId);
            stmt.setTimestamp(4, timestamp);
            stmt.executeUpdate();

            context.getLogger().info("Transaction logged: " + transactionData);
            return request.createResponseBuilder(HttpStatus.OK).body("Transaction logged successfully").build();

        } catch (SQLException e) {
            context.getLogger().severe("Database error: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Error logging transaction").build();
        }
    }
}
