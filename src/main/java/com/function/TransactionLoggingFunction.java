package com.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.microsoft.azure.storage.*;
import com.microsoft.azure.storage.queue.*;

import java.util.Map;
import java.util.Optional;

public class TransactionLoggingFunction {

    @FunctionName("TransactionLogging")
    public HttpResponseMessage logTransaction(
        @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.FUNCTION)
        HttpRequestMessage<Optional<Map<String, Object>>> request,
        final ExecutionContext context) {

        Map<String, Object> transactionData = request.getBody().orElse(null);
        if (transactionData == null) {
            context.getLogger().severe("Invalid transaction data");
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Invalid transaction data").build();
        }

        // Extract fields from transaction data
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

        // Create a queue message for transaction processing
        try {
            // Connect to Azure Storage Queue
            CloudStorageAccount storageAccount = CloudStorageAccount.parse(System.getenv("AzureWebJobsStorage"));
            CloudQueueClient queueClient = storageAccount.createCloudQueueClient();
            CloudQueue queue = queueClient.getQueueReference("transactionqueue");

            // Create message content
            String messageContent = senderId + "," + receiverId + "," + amount;
            CloudQueueMessage message = new CloudQueueMessage(messageContent);

            // Add message to the queue
            queue.addMessage(message);

            context.getLogger().info("Transaction queued for processing: " + messageContent);
            return request.createResponseBuilder(HttpStatus.OK).body("Transaction queued successfully").build();

        } catch (Exception e) {
            context.getLogger().severe("Error queuing transaction: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Error queuing transaction").build();
        }
    }
}
