package jp.hyperliquid.taxtracker.price;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface PriceProvider {

    PriceResponse fetch(String providerAssetId, String currency, Instant from, Instant to, String interval);

    record PriceResponse(JsonNode rawPayload, List<PricePoint> points) {
    }

    record PricePoint(Instant timestamp, BigDecimal price) {
    }
}
