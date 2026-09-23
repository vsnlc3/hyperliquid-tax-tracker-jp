package jp.hyperliquid.taxtracker.price;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class JpyRateService {

    private static final String INTERVAL = "hourly";
    private static final Duration MAX_REQUEST_RANGE = Duration.ofDays(100);

    private final PriceProvider priceProvider;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final List<PriceAsset> priceAssets;

    public JpyRateService(
            PriceProvider priceProvider,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            @Value("${coingecko.sol-asset-id:solana}") String solAssetId,
            @Value("${coingecko.usdc-asset-id:usd-coin}") String usdcAssetId) {
        this.priceProvider = priceProvider;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.priceAssets = List.of(
                new PriceAsset("SOL", solAssetId),
                new PriceAsset("USDC", usdcAssetId));
    }

    @Transactional
    public ImportResult importPrices(UUID userId, Instant requestedFrom, Instant requestedTo) {
        validateRange(requestedFrom, requestedTo);
        UUID importBatchId = insertImportBatch(userId, requestedFrom, requestedTo);
        List<String> errors = new ArrayList<>();
        int rawInserted = 0;
        int snapshotsInserted = 0;
        Instant actualFrom = null;
        Instant actualTo = null;

        for (PriceAsset asset : priceAssets) {
            Instant cursor = requestedFrom;
            while (!cursor.isAfter(requestedTo)) {
                Instant chunkTo = earlier(cursor.plus(MAX_REQUEST_RANGE), requestedTo);
                try {
                    PriceProvider.PriceResponse response = priceProvider.fetch(
                            asset.providerAssetId(), "jpy", cursor, chunkTo, INTERVAL);
                    UUID rawDataId = insertRawData(asset, cursor, chunkTo, response.rawPayload(), importBatchId);
                    rawInserted++;
                    if (response.points().isEmpty()) {
                        errors.add(asset.symbol() + " " + cursor + ".." + chunkTo
                                + ": provider returned no price points");
                    }
                    for (PriceProvider.PricePoint point : response.points()) {
                        if (point.timestamp().isBefore(requestedFrom)
                                || point.timestamp().isAfter(requestedTo)) {
                            continue;
                        }
                        if (insertPriceSnapshot(asset, point, cursor, chunkTo, rawDataId)) {
                            snapshotsInserted++;
                        }
                        actualFrom = earlier(actualFrom, point.timestamp());
                        actualTo = later(actualTo, point.timestamp());
                    }
                } catch (RuntimeException exception) {
                    errors.add(asset.symbol() + " " + cursor + ".." + chunkTo + ": "
                            + safeMessage(exception));
                }
                if (chunkTo.equals(requestedTo)) {
                    break;
                }
                cursor = chunkTo;
            }
        }

        CoreDomain.DataCoverageStatus status = coverageStatus(rawInserted, snapshotsInserted, errors);
        String reason = errors.isEmpty() ? null : String.join("; ", errors);
        persistCoverage(userId, requestedFrom, requestedTo, actualFrom, actualTo,
                status, reason, importBatchId);
        return new ImportResult(importBatchId, status, rawInserted, snapshotsInserted, reason);
    }

    public Rate resolveExact(String asset, Instant timestamp) {
        List<Rate> rates = jdbcTemplate.query("""
                SELECT id, asset, currency, price, price_timestamp, source, provider_asset_id,
                       request_from, request_to, interval, raw_data_id, created_at
                FROM price_snapshots
                WHERE asset = ? AND currency = 'JPY' AND price_timestamp = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, (resultSet, rowNum) -> new Rate(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("asset"),
                resultSet.getString("currency"),
                resultSet.getBigDecimal("price"),
                resultSet.getTimestamp("price_timestamp").toInstant(),
                resultSet.getString("source"),
                resultSet.getString("provider_asset_id"),
                resultSet.getObject("raw_data_id", UUID.class)), asset, Timestamp.from(timestamp));
        if (rates.isEmpty()) {
            throw new MissingPriceException(asset, timestamp);
        }
        return rates.get(0);
    }

    public JpyValuation convertExact(String asset, BigDecimal amount, Instant timestamp) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("JPY valuation amount must be non-negative");
        }
        Rate rate = resolveExact(asset, timestamp);
        return new JpyValuation(rate, amount.multiply(rate.price()));
    }

    private UUID insertImportBatch(UUID userId, Instant requestedFrom, Instant requestedTo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO import_batches (user_id, source, dataset, requested_from, requested_to)
                VALUES (?, 'COINGECKO', 'PRICE_DATA', ?, ?)
                RETURNING id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                userId, Timestamp.from(requestedFrom), Timestamp.from(requestedTo));
    }

    private UUID insertRawData(
            PriceAsset asset,
            Instant requestFrom,
            Instant requestTo,
            JsonNode payload,
            UUID importBatchId) {
        String payloadText = writePayload(payload);
        String payloadHash = sha256(payloadText);
        jdbcTemplate.update("""
                INSERT INTO raw_data (
                    source, dataset, external_id, occurred_at_raw, payload, payload_hash, import_batch_id
                ) VALUES ('COINGECKO', 'PRICE_DATA', ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (source, dataset, payload_hash) DO NOTHING
                """, asset.providerAssetId() + ":" + requestFrom + ":" + requestTo,
                requestFrom + ".." + requestTo, payloadText, payloadHash, importBatchId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM raw_data
                WHERE source = 'COINGECKO' AND dataset = 'PRICE_DATA' AND payload_hash = ?
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class), payloadHash);
    }

    private boolean insertPriceSnapshot(
            PriceAsset asset,
            PriceProvider.PricePoint point,
            Instant requestFrom,
            Instant requestTo,
            UUID rawDataId) {
        return !jdbcTemplate.query("""
                INSERT INTO price_snapshots (
                    asset, currency, price, price_timestamp, source, provider_asset_id,
                    request_from, request_to, interval, raw_data_id
                ) VALUES (?, 'JPY', ?, ?, 'COINGECKO', ?, ?, ?, ?, ?)
                ON CONFLICT (asset, currency, price_timestamp, source, provider_asset_id) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setString(1, asset.symbol());
            preparedStatement.setBigDecimal(2, point.price());
            preparedStatement.setTimestamp(3, Timestamp.from(point.timestamp()));
            preparedStatement.setString(4, asset.providerAssetId());
            preparedStatement.setTimestamp(5, Timestamp.from(requestFrom));
            preparedStatement.setTimestamp(6, Timestamp.from(requestTo));
            preparedStatement.setString(7, INTERVAL);
            preparedStatement.setObject(8, rawDataId);
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class)).isEmpty();
    }

    private void persistCoverage(
            UUID userId,
            Instant requestedFrom,
            Instant requestedTo,
            Instant actualFrom,
            Instant actualTo,
            CoreDomain.DataCoverageStatus status,
            String reason,
            UUID importBatchId) {
        jdbcTemplate.update("""
                INSERT INTO data_import_statuses (
                    user_id, dataset, requested_from, requested_to, actual_from, actual_to,
                    status, reason, import_batch_id
                ) VALUES (?, 'PRICE_DATA', ?, ?, ?, ?, ?, ?, ?)
                """, userId, Timestamp.from(requestedFrom), Timestamp.from(requestedTo),
                timestamp(actualFrom), timestamp(actualTo), status.name(), reason, importBatchId);
    }

    private static CoreDomain.DataCoverageStatus coverageStatus(
            int rawInserted,
            int snapshotsInserted,
            List<String> errors) {
        if (rawInserted == 0 && snapshotsInserted == 0) {
            return CoreDomain.DataCoverageStatus.FAILED;
        }
        return errors.isEmpty()
                ? CoreDomain.DataCoverageStatus.COMPLETE
                : CoreDomain.DataCoverageStatus.PARTIAL;
    }

    private static void validateRange(Instant requestedFrom, Instant requestedTo) {
        if (requestedFrom == null || requestedTo == null || requestedFrom.isAfter(requestedTo)) {
            throw new IllegalArgumentException("Price import requires a valid requested time range");
        }
    }

    private static Instant earlier(Instant left, Instant right) {
        return left == null || right.isBefore(left) ? right : left;
    }

    private static Instant later(Instant left, Instant right) {
        return left == null || right.isAfter(left) ? right : left;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String writePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize price provider payload", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeMessage(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record PriceAsset(String symbol, String providerAssetId) {
    }

    public record ImportResult(
            UUID importBatchId,
            CoreDomain.DataCoverageStatus coverageStatus,
            int rawRows,
            int snapshotRows,
            String reason) {
    }

    public record Rate(
            UUID id,
            String asset,
            String currency,
            BigDecimal price,
            Instant priceTimestamp,
            String source,
            String providerAssetId,
            UUID rawDataId) {
    }

    public record JpyValuation(Rate rate, BigDecimal jpyValue) {
    }

    public static class MissingPriceException extends RuntimeException {
        public MissingPriceException(String asset, Instant timestamp) {
            super("Missing price for " + asset + " at " + timestamp);
        }
    }
}
