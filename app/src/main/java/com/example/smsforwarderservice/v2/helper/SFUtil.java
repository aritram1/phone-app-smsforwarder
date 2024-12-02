package com.example.smsforwarderservice.v2.helper;

import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.example.smsforwarderservice.v2.AppContextProvider;
import com.example.smsforwarderservice.v2.model.SMSMessageModel;
import com.example.smsforwarderservice.v2.model.SalesforceResponseModel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SFUtil {
    private static final String TAG = GlobalConstants.APP_NAME;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Main method to send SMS messages to Salesforce asynchronously.
     */
    public static void sendMessagesToSalesforce(ArrayList<SMSMessageModel> messages) {
        executor.execute(() -> {
            try {
                // Retrieve stored Salesforce token or login if not available
                SalesforceResponseModel sfResponse = getStoredResponse();
                if (sfResponse == null || sfResponse.accessToken == null) {
                    Log.d(TAG, "No valid token found. Initiating login flow...");
                    sfResponse = loginAndRetrieveToken();
                }

                // If token retrieval was successful, send messages
                if (sfResponse != null && sfResponse.accessToken != null) {
                    sendToSalesforce(messages, sfResponse);
                } else {
                    Log.e(TAG, "Failed to retrieve Salesforce token. Messages not sent.");
                    showToast("Failed to authenticate with Salesforce.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in sendMessagesToSalesforce: ", e);
                showToast("Error sending messages to Salesforce: " + e.getMessage());
            }
        });
    }

    /**
     * Retrieve the stored Salesforce token from local storage.
     */
    private static SalesforceResponseModel getStoredResponse() {
        return TokenStorage.getSavedResponse(AppContextProvider.getContext());
    }

    /**
     * Perform login using OAuth password flow and retrieve a new token.
     */
    private static SalesforceResponseModel loginAndRetrieveToken() {
        final SalesforceResponseModel[] response = {null};
        OAuthUtilPasswordFlow.loginWithPasswordFlow(new OAuthUtilPasswordFlow.Callback() {
            @Override
            public void onSuccess(SalesforceResponseModel sfResponse) {
                Log.d(TAG, "Login successful. Token retrieved.");
                response[0] = sfResponse;
                TokenStorage.saveToken(AppContextProvider.getContext(), sfResponse);
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "Login failed: " + error);
                showToast("Login failed: " + error);
            }
        });

        // Return the token retrieved
        return response[0];
    }

    /**
     * Send SMS messages to Salesforce.
     */
    private static void sendToSalesforce(ArrayList<SMSMessageModel> messages, SalesforceResponseModel sfResponse) {
        try {
            URL url = new URL(sfResponse.instanceUrl + GlobalConstants.RESOURCE_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(GlobalConstants.POST);
            connection.setRequestProperty("Authorization", "Bearer " + sfResponse.accessToken);
            connection.setRequestProperty("Content-Type", GlobalConstants.CONTENT_TYPE_APPLICATION_JSON);
            connection.setDoOutput(true);

            for (SMSMessageModel message : messages) {
                String payload = buildPayload(message);
                if(payload == null){
                    Log.d(TAG, "Message not required to be sent to SF");
                }
                else{
                    try (OutputStream os = connection.getOutputStream()) {
                        os.write(payload.getBytes());
                    }

                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_CREATED) {
                        Log.d(TAG, "Message sent successfully to Salesforce.");
                    } else {
                        Log.e(TAG, "Failed to send message. HTTP Response Code: " + responseCode);
                        handleErrorResponse(connection);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while sending messages to Salesforce: ", e);
        }
    }

    /**
     * Build the JSON payload for a given SMS message.
     */
    private static String buildPayload(SMSMessageModel message) throws Exception {
        Log.d(TAG, "I am here");
        String formattedContent = message.content.replaceAll("\\r?\\n", " ");
        String messageExternalID = message.receivedAt
                .replaceAll(":", "")
                .replaceAll(" ", "")
                .replaceAll("-", "")
                .replaceAll(".", "");
        boolean isTransactional = isTransactionMessage(formattedContent);
        Log.d(TAG, "isTransactional=>" + isTransactional);
        if(isTransactional != true) {
            return null;
        }
        else {
            String payload = new JSONObject()
                .put("FinPlan__Sender__c", message.sender)
                .put("FinPlan__Original_Content__c", message.content)
                .put("FinPlan__Received_At__c", message.receivedAt)
                .put("FinPlan__Created_From__c", "SMS")
                .put("FinPlan__Device__c", GlobalConstants.DEVICE_NAME)
                .put("FinPlan__External_Id__c", messageExternalID)
                .put("FinPlan__Content__c", formattedContent)
                .toString();
            Log.d(TAG, "the payload is=>" + payload);
            return payload;
        }
    }

    private static boolean isTransactionMessage(String content) {
        if (content == null || content.isEmpty()) {
            return false; // Return false if content is null or empty
        }

        // Normalize content for case-insensitive matching
        content = content.toLowerCase();

        // Check for the presence of transaction-related keywords
        return content.contains("rs ") ||
                content.contains("sent rs.") ||
                content.contains("amount") ||
                content.contains("amt") ||
                content.contains("credited") ||
                content.contains("debited") ||
                content.contains("bank account") ||
                content.contains("a/c *9560") ||
                content.contains("bank card") ||
                content.contains("balance") ||
                content.contains("available bal") ||
                content.contains("money received") ||
                content.contains("money sent") ||
                content.contains("a/c xx9560");
    }

    /**
     * Handle error responses from Salesforce.
     */
    private static void handleErrorResponse(HttpURLConnection connection) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            Log.e(TAG, "Salesforce API Error Response: " + response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error reading Salesforce error response: ", e);
        }
    }

    /**
     * Show a toast message on the main thread.
     */
    private static void showToast(String message) {
        handler.post(() -> Toast.makeText(AppContextProvider.getContext(), message, Toast.LENGTH_SHORT).show());
    }
}
