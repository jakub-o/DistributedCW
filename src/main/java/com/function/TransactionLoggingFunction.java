package com.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.microsoft.azure.storage.*;
import com.microsoft.azure.storage.queue.*;

import java.util.Map;
import java.util.Optional;

public class TransactionLoggingFunction {

    /**
     * Azure Function to handle and log transaction data.
     * It triggers on HTTP POST requests, validates the request data,
     * and queues a message for further transaction processing.
     */
    @FunctionName("TransactionLogging")
    public HttpResponseMessage logTransaction(
        // Define an HTTP trigger that listens to POST requests with function-level authorization
        @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.FUNCTION)
        HttpRequestMessage<Optional<Map<String, Object>>> request,
        final ExecutionContext context) {

        // Retrieve the request body containing transaction data
        Map<String, Object> transactionData = request.getBody().orElse(null);
        if (transactionData == null) {
            // Log an error and return a 400 response if the data is missing
            context.getLogger().severe("Invalid transaction data");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid transaction data").build();
        }

        // Extract fields required for the transaction
        String senderId = (String) transactionData.get("sender_id");
        String receiverId = (String) transactionData.get("receiver_id");
        Object amountObj = transactionData.get("amount");

        // Check for missing fields and respond with an error if any are missing
        if (senderId == null || receiverId == null || amountObj == null) {
            context.getLogger().severe("Missing required fields");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Missing required fields").build();
        }

        // Convert the amount to a double, handling any formatting errors
        double amount;
        try {
            amount = Double.parseDouble(amountObj.toString());
        } catch (NumberFormatException e) {
            context.getLogger().severe("Invalid amount format: " + amountObj);
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid amount format").build();
        }

        // Proceed with creating a queue message for transaction processing
        try {
            // Connect to the Azure Storage Queue service
            CloudStorageAccount storageAccount = CloudStorageAccount.parse(System.getenv("AzureWebJobsStorage"));
            CloudQueueClient queueClient = storageAccount.createCloudQueueClient();
            CloudQueue queue = queueClient.getQueueReference("transactionqueue");

            // Format the transaction data as a comma-separated string for the queue message
            String messageContent = senderId + "," + receiverId + "," + amount;
            CloudQueueMessage message = new CloudQueueMessage(messageContent);

            // Enqueue the message to the Azure Queue
            queue.addMessage(message);

            // Log success and return a 200 OK response
            context.getLogger().info("Transaction queued for processing: " + messageContent);
            return request.createResponseBuilder(HttpStatus.OK).body("Transaction queued successfully").build();

        } catch (Exception e) {
            // Log any errors that occur while connecting to the queue or sending the message
            context.getLogger().severe("Error queuing transaction: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Error queuing transaction").build();
        }
    }
}
