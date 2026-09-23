package jp.hyperliquid.taxtracker.price;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class CoinGeckoPriceProvider implements PriceProvider {

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final String apiKey;

    public CoinGeckoPriceProvider(
            RestClient.Builder restClientBuilder,
            @Value("${coingecko.base-url:https://api.coingecko.com/api/v3}") String baseUrl,
            @Value("${coingecko.api-key:}") String apiKey) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    public PriceResponse fetch(
            String providerAssetId,
            String currency,
            Instant from,
            Instant to,
            String interval) {
        if (providerAssetId == null || providerAssetId.isBlank()
                || currency == null || currency.isBlank()
                || from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("CoinGecko price request is invalid");
        }

        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                RestClient.RequestHeadersSpec<?> request = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/coins/{id}/market_chart/range")
                                .queryParam("vs_currency", currency)
                                .queryParam("from", from.getEpochSecond())
                                .queryParam("to", to.getEpochSecond())
                                .queryParam("interval", interval)
                                .queryParam("precision", "full")
                                .build(providerAssetId));
                JsonNode payload = hasApiKey()
                        ? request.header("x-cg-demo-api-key", apiKey).retrieve().body(JsonNode.class)
                        : request.retrieve().body(JsonNode.class);
                if (payload == null) {
                    throw new PriceProviderException("CoinGecko returned an empty response");
                }
                return new PriceResponse(payload, parsePoints(payload));
            } catch (RuntimeException exception) {
                lastException = exception;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                waitBeforeRetry(attempt);
            }
        }
        throw new PriceProviderException(
                "CoinGecko request failed after " + MAX_ATTEMPTS + " attempts", lastException);
    }

    private static List<PricePoint> parsePoints(JsonNode payload) {
        JsonNode prices = payload.path("prices");
        if (!prices.isArray()) {
            throw new PriceProviderException("CoinGecko response did not contain a prices array");
        }

        List<PricePoint> points = new ArrayList<>();
        for (JsonNode point : prices) {
            if (!point.isArray() || point.size() < 2 || !point.get(0).canConvertToLong()
                    || !point.get(1).isNumber()) {
                throw new PriceProviderException("CoinGecko response contained an invalid price point");
            }
            BigDecimal price = point.get(1).decimalValue();
            if (price.signum() <= 0) {
                throw new PriceProviderException("CoinGecko response contained a non-positive price");
            }
            points.add(new PricePoint(
                    Instant.ofEpochMilli(point.get(0).longValue()), price));
        }
        return points;
    }

    private boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PriceProviderException("CoinGecko retry interrupted", exception);
        }
    }

    public static class PriceProviderException extends RuntimeException {
        public PriceProviderException(String message) {
            super(message);
        }

        public PriceProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
