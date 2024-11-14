import pandas as pd
import numpy as np
import random
from sklearn.model_selection import train_test_split, GridSearchCV
from sklearn.metrics import confusion_matrix, classification_report, accuracy_score
from sklearn.ensemble import RandomForestClassifier
from imblearn.over_sampling import SMOTE
from sklearn.preprocessing import StandardScaler

# Function to simulate transaction data with specified distributions for transaction amounts and timestamps
def simulate_data_with_distribution(num_records, fraud_probability=0.1):
    sender_ids = ['S1', 'S2', 'S3', 'S4', 'S5']
    receiver_ids = ['R1', 'R2', 'R3', 'R4', 'R5']
    amount_distribution = np.random.lognormal(mean=5, sigma=2, size=num_records)
    timestamp_distribution = np.random.normal(loc=pd.to_datetime('2023-11-01').value, scale=360000000000, size=num_records)

    data = [
        [
            random.choice(sender_ids),
            random.choice(receiver_ids),
            max(0, amount_distribution[i]),
            pd.to_datetime(timestamp_distribution[i]),
            1 if random.random() < fraud_probability else 0
        ]
        for i in range(num_records)
    ]
    return pd.DataFrame(data, columns=['sender_id', 'receiver_id', 'amount', 'timestamp', 'fraud_flag'])

# Generate data
df = simulate_data_with_distribution(10000)

# Encode categorical variables and feature engineering
df['sender_id'] = df['sender_id'].astype('category').cat.codes
df['receiver_id'] = df['receiver_id'].astype('category').cat.codes
df['amount_log'] = np.log1p(df['amount'])
df['sender_avg_amount'] = df.groupby('sender_id')['amount'].transform('mean')

# Calculate the transaction count within the last 7 days for each transaction of a sender
def calculate_tx_count_last_week(group):
    tx_count = []
    
    for i, row in group.iterrows():
        start_time = row['timestamp'] - pd.Timedelta(days=7)
        end_time = row['timestamp']
        count = group[(group['timestamp'] >= start_time) & (group['timestamp'] < end_time)].shape[0]
        tx_count.append(count)
    
    group['sender_tx_count_last_week'] = tx_count
    return group

# Apply the function to calculate the 7-day transaction count for each sender
df = df.groupby('sender_id').apply(calculate_tx_count_last_week)

# Ensure that the sender_id column is treated as a column and not as an index
df = df.reset_index(drop=True)

# Transaction amount ratio and high-velocity transactions flag
df['amount_to_avg_ratio'] = df['amount'] / (df['sender_avg_amount'] + 1e-5)

# Define features and target
X = df[['amount_log', 'sender_avg_amount', 'sender_tx_count_last_week', 'amount_to_avg_ratio']]
y = df['fraud_flag']

# Handle NaN values and split data
X.fillna(0, inplace=True)
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, stratify=y, random_state=42)

# Scale features
scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)

# Balance the training data with SMOTE
smote = SMOTE(random_state=42)
X_train_resampled, y_train_resampled = smote.fit_resample(X_train_scaled, y_train)

# Initialize and train Random Forest with GridSearchCV
rf_model = RandomForestClassifier(random_state=42)
param_grid = {
    'n_estimators': [200],
    'max_depth': [12],
    'min_samples_split': [5],
    'min_samples_leaf': [2]
}
grid_search = GridSearchCV(rf_model, param_grid, cv=3, scoring='precision', n_jobs=-1)
grid_search.fit(X_train_resampled, y_train_resampled)
best_rf_model = grid_search.best_estimator_

# Adjust prediction threshold
y_pred_proba = best_rf_model.predict_proba(X_test_scaled)[:, 1]
threshold = 0.4
y_pred = (y_pred_proba >= threshold).astype(int)

# Results and classification report
accuracy = accuracy_score(y_test, y_pred)
conf_matrix = confusion_matrix(y_test, y_pred)
report = classification_report(y_test, y_pred)

print("Best Random Forest Model:", best_rf_model)
print(f"Model Accuracy: {accuracy * 100:.2f}%")
print("\nConfusion Matrix:")
print(conf_matrix)
print("\nClassification Report:")
print(report)
