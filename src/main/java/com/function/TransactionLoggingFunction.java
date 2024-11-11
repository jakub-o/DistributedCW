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

        // Extract and validate transaction data
        Map<String, Object> transactionData = request.getBody().orElse(null);
        if (transactionData == null) {
            context.getLogger().severe("Invalid transaction data");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid transaction data").build();
        }

        // Retrieve and validate required fields
        String senderId = (String) transactionData.get("sender_id");
        String receiverId = (String) transactionData.get("receiver_id");
        Object amountObj = transactionData.get("amount");

        if (senderId == null || receiverId == null || amountObj == null) {
            context.getLogger().severe("Missing required fields");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Missing required fields").build();
        }

        double amount;
        try {
            amount = Double.parseDouble(amountObj.toString());
        } catch (NumberFormatException e) {
            context.getLogger().severe("Invalid amount format: " + amountObj);
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid amount format").build();
        }

        Timestamp timestamp = new Timestamp(System.currentTimeMillis());

        // Retrieve database connection details from environment variables
        String jdbcUrl = System.getenv("SQL_CONNECTION_STRING");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        if (jdbcUrl == null || dbUser == null || dbPassword == null) {
            context.getLogger().severe("Database configuration missing in environment variables.");
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Database configuration error").build();
        }

        context.getLogger().info("Received transaction data: " + transactionData);
        context.getLogger().info("Connecting to database with URL: " + jdbcUrl);

        // Attempt to log the transaction in the database
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO transactions (amount, sender_id, receiver_id, timestamp) VALUES (?, ?, ?, ?)")) {

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
        } catch (Exception e) {
            context.getLogger().severe("Unexpected error: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Unexpected error").build();
        }
    }
}
