package jp.hyperliquid.taxtracker.hyperliquid;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HyperliquidInfoClient {

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;

    public HyperliquidInfoClient(
            RestClient.Builder restClientBuilder,
            @Value("${hyperliquid.info-url:https://api.hyperliquid.xyz/info}") String infoUrl) {
        this.restClient = restClientBuilder.baseUrl(infoUrl).build();
    }

    public JsonNode query(String type, String user, Long startTime, Long endTime) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", type);
        if (user != null) {
            request.put("user", user);
        }
        if (startTime != null) {
            request.put("startTime", startTime);
        }
        if (endTime != null) {
            request.put("endTime", endTime);
        }
        return call(request, type);
    }

    public JsonNode spotMeta() {
        return call(Map.of("type", "spotMeta"), "spotMeta");
    }

    private JsonNode call(Map<String, Object> request, String type) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    throw new HyperliquidInfoException(type + " returned an empty response");
                }
                if (response.hasNonNull("error")) {
                    throw new HyperliquidInfoException(type + " returned an API error: "
                            + response.get("error"));
                }
                return response;
            } catch (RuntimeException exception) {
                lastException = exception;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                waitBeforeRetry(attempt);
            }
        }
        throw new HyperliquidInfoException(
                type + " failed after " + MAX_ATTEMPTS + " attempts", lastException);
    }

    private static void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new HyperliquidInfoException("Retry interrupted", exception);
        }
    }

    public static class HyperliquidInfoException extends RuntimeException {
        public HyperliquidInfoException(String message) {
            super(message);
        }

        public HyperliquidInfoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
