import pandas as pd
import numpy as np
import random
import xgboost as xgb
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score, confusion_matrix
from imblearn.over_sampling import SMOTE
import onnxmltools
from onnxmltools.convert import convert_xgboost
from onnxmltools.convert.common.data_types import FloatTensorType

# Function to simulate transaction data with a distribution for amount and timestamp
def simulate_data_with_distribution(num_records):
    sender_ids = ['S1', 'S2', 'S3', 'S4', 'S5']  # Sender IDs
    receiver_ids = ['R1', 'R2', 'R3', 'R4', 'R5']  # Receiver IDs
    amount_distribution = np.random.lognormal(mean=5, sigma=2, size=num_records)  # Lognormal distribution for transaction amounts
    timestamp_distribution = np.random.normal(loc=pd.to_datetime('2023-11-01').value, scale=360000000000, size=num_records)  # Normal distribution for timestamps
    fraud_probability = 0.1  # 10% fraud probability
    data = []  # List to store transaction data

    # Generate data
    for i in range(num_records):
        sender = random.choice(sender_ids)  # Random sender
        receiver = random.choice(receiver_ids)  # Random receiver
        amount = max(0, amount_distribution[i])  # Ensure no negative amounts
        timestamp = pd.to_datetime(timestamp_distribution[i])  # Convert timestamp to datetime
        fraud = 1 if random.random() < fraud_probability else 0  # Random fraud flag (1 for fraud, 0 for non-fraud)
        data.append([sender, receiver, amount, timestamp, fraud])  # Append to the data list

    # Return the generated DataFrame
    return pd.DataFrame(data, columns=['sender_id', 'receiver_id', 'amount', 'timestamp', 'fraud_flag'])

# Simulate the transaction data and preprocess it for modeling
df = simulate_data_with_distribution(10000)  # Simulate 10,000 records

# Convert categorical 'sender_id' and 'receiver_id' to numeric codes
df['sender_id'] = df['sender_id'].astype('category').cat.codes
df['receiver_id'] = df['receiver_id'].astype('category').cat.codes

# Convert 'timestamp' to integer format (nanoseconds since epoch)
df['timestamp'] = pd.to_datetime(df['timestamp']).astype('int64')

# Selecting only 'amount' and 'timestamp' as features for the model
X = df[['amount', 'timestamp']]
y = df['fraud_flag']

# Rename columns to match ONNX pattern 'f0', 'f1' for compatibility
X.columns = ['f0', 'f1']

# Split data into training and testing sets (80% training, 20% testing)
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

# Apply SMOTE (Synthetic Minority Over-sampling Technique) to balance the classes
smote = SMOTE(random_state=42)
X_train_resampled, y_train_resampled = smote.fit_resample(X_train, y_train)

# Initialize and train the XGBoost model
model = xgb.XGBClassifier(
    learning_rate=0.1,
    max_depth=10,
    n_estimators=200,
    subsample=0.8,
    colsample_bytree=1.0,
    random_state=42
)

# Fit the model to the resampled training data
model.fit(X_train_resampled, y_train_resampled)

# Make predictions on the test set
y_pred = model.predict(X_test)

# Calculate and print the accuracy of the model
accuracy = accuracy_score(y_test, y_pred)
print(f"Model Accuracy: {accuracy * 100:.2f}%")

# Generate and print the confusion matrix to evaluate the model performance
cm = confusion_matrix(y_test, y_pred)
print("\nConfusion Matrix:")
print(cm)

# Print an example of a fraudulent transaction that was correctly caught (True Positive)
fraudulent_caught = X_test[(y_test == 1) & (y_pred == 1)]  # Filter out correctly caught fraudulent transactions

# If there are any true positives, print one example
if not fraudulent_caught.empty:
    print("\nExample of a fraudulent transaction that was correctly caught (True Positive):")
    example = fraudulent_caught.iloc[0]  # Select the first fraudulent transaction caught
    example_data = df.iloc[example.name]  # Access the corresponding row from the original DataFrame
    print(example_data)
else:
    print("\nNo fraudulent transactions caught in the test set.")  # In case no true positives were caught

# Define the initial input types for the XGBoost model (required for ONNX conversion)
initial_types = [('float_input', FloatTensorType([None, X_train.shape[1]]))]

# Convert the trained XGBoost model to ONNX format using the onnxmltools library
onnx_model = convert_xgboost(model, initial_types=initial_types)

# Save the ONNX model to a file
with open("fraud_model.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())

# Print a confirmation message indicating the model has been saved
print("XGBoost model saved as 'fraud_model.onnx'")
