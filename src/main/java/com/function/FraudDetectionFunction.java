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

    // Static block to initialize the ONNX model session only once
    static {
        try {
            env = OrtEnvironment.getEnvironment();
            // Load model from resources
            InputStream modelStream = FraudDetectionFunction.class.getClassLoader().getResourceAsStream("fraud_model.onnx");
            if (modelStream == null) {
                throw new OrtException("Model file not found in resources.");
            }

            // Copy model to temporary directory
            String modelPath = Paths.get(System.getProperty("java.io.tmpdir"), "fraud_model.onnx").toString();
            Files.deleteIfExists(Paths.get(modelPath));  // Clear any existing temp file
            Files.copy(modelStream, Paths.get(modelPath));  // Save temporarily for access

            // Create ONNX session with the loaded model
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            session = env.createSession(modelPath, opts);
            System.out.println("ONNX model loaded and cached successfully.");
        } catch (Exception e) {
            System.err.println("Failed to load ONNX model: " + e.getMessage());
            session = null;  // Set session to null if loading fails
        }
    }

    @FunctionName("FraudDetection")
    public void processTransaction(
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

        // Split message and directly assign known float data
        String[] transactionParts = transactionMessage.split(",");
        double amount = Double.parseDouble(transactionParts[2]);

        String jdbcUrl = System.getenv("SQL_CONNECTION_STRING");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            // Insert transaction into the database
            String insertSql = "INSERT INTO transactions (sender_id, receiver_id, amount, timestamp) VALUES (?, ?, ?, GETDATE())";
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                insertStmt.setString(1, transactionParts[0]);  // senderId
                insertStmt.setString(2, transactionParts[1]);  // receiverId
                insertStmt.setDouble(3, amount);
                insertStmt.executeUpdate();
                logger.info("Transaction inserted successfully for sender: " + transactionParts[0] + ", amount: " + amount);
            }

            // Prepare input tensor with amount and timestamp directly
            float[] features = {(float) amount, (float) System.currentTimeMillis()};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, new float[][]{features});

            // Run prediction using cached model session
            HashMap<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(session.getInputNames().iterator().next(), inputTensor);
            OrtSession.Result results = session.run(inputs);

            // Log the class type of the output to understand its structure
            Object outputObject = results.get(0).getValue();
            logger.info("Model output type: " + outputObject.getClass().getName());

            // Assuming the output is long[] with a single value, handle accordingly
            if (outputObject instanceof long[]) {
                long[] output = (long[]) outputObject;
                if (output.length > 0) {
                    // Log the output value and handle fraud detection accordingly
                    long fraudProbability = output[0];  // Adjust based on model output
                    logger.info("Fraud probability (long): " + fraudProbability);
                    // If the fraud probability is above a threshold (e.g., 0.5), flag the transaction
                    if (fraudProbability > 0) {  // Adjust condition based on the actual fraud threshold
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
                logger.severe("Unexpected output type: " + outputObject.getClass().getName());
                // Handle the unexpected output type accordingly
            }
            inputTensor.close();  // Free tensor resources after prediction

        } catch (Exception e) {
            logger.severe("Error processing transaction: " + e.getMessage());
        }
    }
}
