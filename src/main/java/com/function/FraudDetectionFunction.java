package com.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import ai.onnxruntime.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.HashMap;
import java.util.logging.Logger;

public class FraudDetectionFunction {

    private static OrtEnvironment env;
    private static OrtSession session;

    // Static block to initialize the ONNX model session only once when the class is loaded
    static {
        try {
            // Set up the ONNX runtime environment
            env = OrtEnvironment.getEnvironment();
            // Load the fraud detection model from resources
            InputStream modelStream = FraudDetectionFunction.class.getClassLoader().getResourceAsStream("fraud_model.onnx");
            if (modelStream == null) {
                throw new OrtException("Model file not found in resources.");
            }

            // Copy the model to a temporary directory to make it accessible for the ONNX session
            String modelPath = Paths.get(System.getProperty("java.io.tmpdir"), "fraud_model.onnx").toString();
            // Delete any existing temporary model file
            Files.deleteIfExists(Paths.get(modelPath));
            // Copy the model file to temp
            Files.copy(modelStream, Paths.get(modelPath));

            // Create an ONNX session with the loaded model
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            session = env.createSession(modelPath, opts);
            System.out.println("ONNX model loaded and cached successfully.");
        } catch (Exception e) {
            System.err.println("Failed to load ONNX model: " + e.getMessage());
            // Set session to null if model loading fails
            session = null;
        }
    }

    /**
     * Function to process transactions for fraud detection.
     * It triggers on messages in an Azure Queue, logs transaction info, and checks for potential fraud.
     */
    @FunctionName("FraudDetection")
    public void processTransaction(
        // Queue trigger to listen to transaction messages in the queue "transactionqueue"
        @QueueTrigger(name = "transactionQueue", queueName = "transactionqueue", connection = "AzureWebJobsStorage")
        String transactionMessage,
        final ExecutionContext context) {

        Logger logger = context.getLogger();
        logger.info("Processing transaction for fraud detection: " + transactionMessage);

        // Check if the ONNX model was successfully loaded
        if (session == null) {
            logger.severe("ONNX model not loaded. Skipping fraud detection.");
            return;
        }

        // Split the message to extract transaction details (sender, receiver, amount)
        String[] transactionParts = transactionMessage.split(",");
        double amount = Double.parseDouble(transactionParts[2]);

        // Set up database connection parameters from environment variables
        String jdbcUrl = System.getenv("SQL_CONNECTION_STRING");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            // Insert transaction details into the database
            String insertSql = "INSERT INTO transactions (sender_id, receiver_id, amount, timestamp) VALUES (?, ?, ?, GETDATE())";
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                insertStmt.setString(1, transactionParts[0]);
                insertStmt.setString(2, transactionParts[1]);
                insertStmt.setDouble(3, amount);
                insertStmt.executeUpdate();
                logger.info("Transaction inserted successfully for sender: " + transactionParts[0] + ", amount: " + amount);
            }

            // Prepare input tensor for the fraud detection model with transaction amount and timestamp
            float[] features = {(float) amount, (float) System.currentTimeMillis()};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, new float[][]{features});

            // Run the prediction using the ONNX model
            HashMap<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(session.getInputNames().iterator().next(), inputTensor);
            OrtSession.Result results = session.run(inputs);

            // Retrieve (and log the model output for verification for debugging)
            Object outputObject = results.get(0).getValue();
            //logger.info("Model output type: " + outputObject.getClass().getName());

            // Assuming the model output is a long array, extract the fraud probability
            if (outputObject instanceof long[]) {
                long[] output = (long[]) outputObject;
                if (output.length > 0) {
                    // Log fraud probability and check if it exceeds a threshold for flagging
                    long fraudProbability = output[0];
                    logger.info("Fraud probability (long): " + fraudProbability);
                    // If fraud probability is above a threshold, flag the transaction as suspicious
                    if (fraudProbability > 0) {
                        logger.warning("Fraudulent transaction detected! Sender: " + transactionParts[0] + ", Amount: " + amount);
                        String updateSql = "UPDATE transactions SET fraud_flag = 1 WHERE sender_id = ? AND receiver_id = ? AND amount = ?";
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                            updateStmt.setString(1, transactionParts[0]);
                            updateStmt.setString(2, transactionParts[1]);
                            updateStmt.setDouble(3, amount);
                            updateStmt.executeUpdate();
                        }
                    }
                } else {
                    logger.warning("Model output is empty or does not contain valid fraud detection information.");
                }
            } else {
                // Handle unexpected output types if necessary
                logger.severe("Unexpected output type: " + outputObject.getClass().getName());
            }
            // Free resources allocated for the tensor
            inputTensor.close();

        } catch (Exception e) {
            logger.severe("Error processing transaction: " + e.getMessage());
        }
    }
}
