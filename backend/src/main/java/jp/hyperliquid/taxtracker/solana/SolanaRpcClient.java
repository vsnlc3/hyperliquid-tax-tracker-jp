package jp.hyperliquid.taxtracker.solana;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SolanaRpcClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final int HISTORY_PAGE_LIMIT = 1_000;

    private final RestClient restClient;

    public SolanaRpcClient(
            RestClient.Builder restClientBuilder,
            @Value("${solana.rpc-url:https://api.mainnet-beta.solana.com}") String rpcUrl) {
        this.restClient = restClientBuilder.baseUrl(rpcUrl).build();
    }

    public JsonNode getSignaturesForAddress(String address, String before) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("commitment", "finalized");
        config.put("limit", HISTORY_PAGE_LIMIT);
        if (before != null && !before.isBlank()) {
            config.put("before", before);
        }
        return call("getSignaturesForAddress", List.of(address, config));
    }

    public JsonNode getTransaction(String signature) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("commitment", "finalized");
        config.put("encoding", "jsonParsed");
        config.put("maxSupportedTransactionVersion", 0);
        return call("getTransaction", List.of(signature, config));
    }

    private JsonNode call(String method, List<Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", method);
        request.put("method", method);
        request.put("params", params);

        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    throw new SolanaRpcException(method + " returned an empty response");
                }
                if (response.hasNonNull("error")) {
                    throw new SolanaRpcException(method + " returned an RPC error: "
                            + response.get("error").toString());
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
        throw new SolanaRpcException(method + " failed after " + MAX_ATTEMPTS + " attempts", lastException);
    }

    private static void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SolanaRpcException("Retry interrupted", exception);
        }
    }

    public static class SolanaRpcException extends RuntimeException {
        public SolanaRpcException(String message) {
            super(message);
        }

        public SolanaRpcException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
