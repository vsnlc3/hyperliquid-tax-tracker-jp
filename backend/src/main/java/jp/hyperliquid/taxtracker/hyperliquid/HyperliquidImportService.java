package jp.hyperliquid.taxtracker.hyperliquid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class HyperliquidImportService {

    private static final String NORMALIZATION_VERSION = "hyperliquid-v1";
    private static final int FILL_PAGE_LIMIT = 2_000;
    private static final int GENERAL_TIME_RANGE_PAGE_LIMIT = 500;
    private static final int FILL_HISTORY_LIMIT = 10_000;

    private final HyperliquidInfoClient infoClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public HyperliquidImportService(
            HyperliquidInfoClient infoClient,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.infoClient = infoClient;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ImportResult importAccount(UUID userId, UUID accountId, Instant requestedFrom, Instant requestedTo) {
        if (requestedFrom == null || requestedTo == null || requestedFrom.isAfter(requestedTo)) {
            throw new IllegalArgumentException("Hyperliquid import requires a valid requested time range");
        }
        AccountRow account = account(accountId, userId);
        if (!"UNIFIED".equals(account.accountMode())) {
            throw new UnsupportedAccountModeException(account.accountMode());
        }

        Map<CoreDomain.Dataset, CoverageResult> results = new LinkedHashMap<>();
        results.put(CoreDomain.Dataset.HYPERLIQUID_FILLS,
                importTimedDataset(userId, account.address(), CoreDomain.Dataset.HYPERLIQUID_FILLS,
                        "userFillsByTime", requestedFrom, requestedTo, FILL_PAGE_LIMIT, FILL_HISTORY_LIMIT));
        results.put(CoreDomain.Dataset.HYPERLIQUID_FUNDING,
                importTimedDataset(userId, account.address(), CoreDomain.Dataset.HYPERLIQUID_FUNDING,
                        "userFunding", requestedFrom, requestedTo, GENERAL_TIME_RANGE_PAGE_LIMIT, Integer.MAX_VALUE));
        results.put(CoreDomain.Dataset.HYPERLIQUID_LEDGER,
                importTimedDataset(userId, account.address(), CoreDomain.Dataset.HYPERLIQUID_LEDGER,
                        "userNonFundingLedgerUpdates", requestedFrom, requestedTo,
                        GENERAL_TIME_RANGE_PAGE_LIMIT, Integer.MAX_VALUE));
        results.put(CoreDomain.Dataset.HYPERLIQUID_SPOT_METADATA,
                importSpotMetadata(userId, requestedFrom, requestedTo));
        return new ImportResult(results);
    }

    private CoverageResult importTimedDataset(
            UUID userId,
            String accountAddress,
            CoreDomain.Dataset dataset,
            String requestType,
            Instant requestedFrom,
            Instant requestedTo,
            int pageLimit,
            int historyLimit) {
        UUID importBatchId = insertImportBatch(userId, dataset, requestedFrom, requestedTo);
        List<JsonNode> records = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long cursor = requestedFrom.toEpochMilli();
        long endTime = requestedTo.toEpochMilli();
        int historyCount = 0;

        while (cursor <= endTime) {
            JsonNode response;
            try {
                response = infoClient.query(requestType, accountAddress, cursor, endTime);
            } catch (RuntimeException exception) {
                errors.add(exception.getMessage());
                break;
            }
            if (!response.isArray()) {
                errors.add(requestType + " response was not an array");
                break;
            }
            if (response.isEmpty()) {
                break;
            }
            long lastTime = cursor;
            for (JsonNode record : response) {
                records.add(record);
                Long time = longValue(record, "time");
                if (time == null) {
                    errors.add(requestType + " record did not contain time");
                } else {
                    lastTime = Math.max(lastTime, time);
                }
            }
            historyCount += response.size();
            if (response.size() < pageLimit) {
                break;
            }
            if (historyCount >= historyLimit) {
                errors.add(requestType + " history limit was reached before the requested range ended");
                break;
            }
            if (lastTime < cursor || lastTime >= endTime) {
                break;
            }
            cursor = lastTime + 1;
        }

        int rawRows = 0;
        int normalizedRows = 0;
        Instant actualFrom = null;
        Instant actualTo = null;
        for (JsonNode record : records) {
            Instant occurredAt = instantFromEpochMillis(record.get("time"));
            actualFrom = earlier(actualFrom, occurredAt);
            actualTo = later(actualTo, occurredAt);
            String externalKey = externalKey(dataset, record);
            Optional<UUID> rawDataId = insertRawData(
                    dataset,
                    externalKey,
                    occurredAt,
                    payload(requestType, accountAddress, requestedFrom, requestedTo, record),
                    sha256(record.toString()),
                    importBatchId);
            if (rawDataId.isEmpty()) {
                continue;
            }
            rawRows++;
            if (saveRecord(dataset, rawDataId.get(), externalKey, record)) {
                normalizedRows++;
            }
        }

        if (records.isEmpty() && !errors.isEmpty()) {
            return persistCoverage(userId, dataset, requestedFrom, requestedTo, actualFrom, actualTo,
                    CoreDomain.DataCoverageStatus.FAILED, String.join("; ", errors), importBatchId,
                    rawRows, normalizedRows);
        }
        CoreDomain.DataCoverageStatus status = errors.isEmpty()
                ? CoreDomain.DataCoverageStatus.COMPLETE
                : CoreDomain.DataCoverageStatus.PARTIAL;
        return persistCoverage(userId, dataset, requestedFrom, requestedTo, actualFrom, actualTo,
                status, errors.isEmpty() ? null : String.join("; ", errors), importBatchId,
                rawRows, normalizedRows);
    }

    private CoverageResult importSpotMetadata(UUID userId, Instant requestedFrom, Instant requestedTo) {
        CoreDomain.Dataset dataset = CoreDomain.Dataset.HYPERLIQUID_SPOT_METADATA;
        UUID importBatchId = insertImportBatch(userId, dataset, requestedFrom, requestedTo);
        JsonNode response;
        try {
            response = infoClient.spotMeta();
        } catch (RuntimeException exception) {
            return persistCoverage(userId, dataset, requestedFrom, requestedTo, null, null,
                    CoreDomain.DataCoverageStatus.FAILED, exception.getMessage(), importBatchId, 0, 0);
        }
        List<String> errors = new ArrayList<>();
        if (!response.path("tokens").isArray()) {
            errors.add("spotMeta response did not contain tokens");
        }
        if (!response.path("universe").isArray()) {
            errors.add("spotMeta response did not contain universe");
        }
        Instant occurredAt = Instant.now();
        String externalKey = "spotMeta";
        Optional<UUID> rawDataId = insertRawData(
                dataset, externalKey, occurredAt, payload("spotMeta", null, requestedFrom, requestedTo, response),
                sha256(response.toString()),
                importBatchId);
        int rawRows = rawDataId.isPresent() ? 1 : 0;
        int normalizedRows = rawDataId.filter(id -> saveSpotMetadata(id, response, occurredAt)).isPresent() ? 1 : 0;
        CoreDomain.DataCoverageStatus status = errors.isEmpty()
                ? CoreDomain.DataCoverageStatus.COMPLETE
                : CoreDomain.DataCoverageStatus.PARTIAL;
        return persistCoverage(userId, dataset, requestedFrom, requestedTo, occurredAt, occurredAt,
                status, errors.isEmpty() ? null : String.join("; ", errors), importBatchId,
                rawRows, normalizedRows);
    }

    private boolean saveRecord(
            CoreDomain.Dataset dataset,
            UUID rawDataId,
            String externalKey,
            JsonNode record) {
        return switch (dataset) {
            case HYPERLIQUID_FILLS -> saveFill(rawDataId, externalKey, record);
            case HYPERLIQUID_FUNDING -> saveFunding(rawDataId, externalKey, record);
            case HYPERLIQUID_LEDGER -> saveLedger(rawDataId, externalKey, record);
            default -> false;
        };
    }

    private boolean saveFill(UUID rawDataId, String externalKey, JsonNode record) {
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO hyperliquid_fills (
                    raw_data_id, external_key, coin, side, direction, size, price, occurred_at,
                    start_position, crossed, order_id, trade_id, hash, twap_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (external_key) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setObject(1, rawDataId);
            preparedStatement.setString(2, externalKey);
            preparedStatement.setString(3, text(record, "coin"));
            preparedStatement.setString(4, text(record, "side"));
            preparedStatement.setString(5, text(record, "dir"));
            preparedStatement.setBigDecimal(6, decimal(record, "sz"));
            preparedStatement.setBigDecimal(7, decimal(record, "px"));
            preparedStatement.setObject(8, sqlTimestamp(instantFromEpochMillis(record.get("time"))));
            preparedStatement.setBigDecimal(9, decimal(record, "startPosition"));
            preparedStatement.setObject(10, booleanValue(record, "crossed"));
            preparedStatement.setString(11, text(record, "oid"));
            preparedStatement.setString(12, text(record, "tid"));
            preparedStatement.setString(13, text(record, "hash"));
            preparedStatement.setString(14, text(record, "twapId"));
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        Optional<UUID> fillId = ids.stream().findFirst();
        if (fillId.isEmpty()) {
            return false;
        }
        if (record.has("closedPnl") && !record.get("closedPnl").isNull()) {
            jdbcTemplate.update("""
                    INSERT INTO hyperliquid_closed_pnl (
                        raw_data_id, fill_id, coin, occurred_at, closed_pnl, pnl_asset
                    ) VALUES (?, ?, ?, ?, ?, NULL)
                    ON CONFLICT (fill_id) DO NOTHING
                    """, rawDataId, fillId.get(), text(record, "coin"),
                    sqlTimestamp(instantFromEpochMillis(record.get("time"))), decimal(record, "closedPnl"));
        }
        if (record.has("fee") && !record.get("fee").isNull()) {
            jdbcTemplate.update("""
                    INSERT INTO hyperliquid_fees (
                        raw_data_id, related_fill_id, fee_type, fee_asset, fee_amount,
                        occurred_at, external_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (external_key) DO NOTHING
                    """, rawDataId, fillId.get(), feeType(record).name(), text(record, "feeToken"),
                    decimal(record, "fee"), sqlTimestamp(instantFromEpochMillis(record.get("time"))),
                    externalKey + "#fee");
        }
        return true;
    }

    private boolean saveFunding(UUID rawDataId, String externalKey, JsonNode record) {
        JsonNode delta = record.path("delta");
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO hyperliquid_funding (
                    raw_data_id, external_key, hash, coin, occurred_at, funding_rate,
                    funding_amount, position_size, funding_asset, sample_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (external_key) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setObject(1, rawDataId);
            preparedStatement.setString(2, externalKey);
            preparedStatement.setString(3, text(record, "hash"));
            preparedStatement.setString(4, text(delta, "coin"));
            preparedStatement.setObject(5, sqlTimestamp(instantFromEpochMillis(record.get("time"))));
            preparedStatement.setBigDecimal(6, decimal(delta, "fundingRate"));
            preparedStatement.setBigDecimal(7, decimal(delta, "usdc"));
            preparedStatement.setBigDecimal(8, decimal(delta, "szi"));
            preparedStatement.setString(9, hasValue(delta, "usdc") ? "USDC" : null);
            preparedStatement.setObject(10, integerValue(delta, "nSamples"));
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        return !ids.isEmpty();
    }

    private boolean saveLedger(UUID rawDataId, String externalKey, JsonNode record) {
        JsonNode delta = record.path("delta");
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO hyperliquid_ledger_updates (
                    raw_data_id, external_key, hash, subtype, ledger_update_type, token, amount,
                    usdc_value, user_address, destination_address, fee, native_token_fee, nonce,
                    fee_asset, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (external_key) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setObject(1, rawDataId);
            preparedStatement.setString(2, externalKey);
            preparedStatement.setString(3, text(record, "hash"));
            preparedStatement.setString(4, ledgerSubtype(text(delta, "type")));
            preparedStatement.setString(5, text(delta, "type"));
            preparedStatement.setString(6, text(delta, "token"));
            preparedStatement.setBigDecimal(7, decimal(delta, "amount"));
            preparedStatement.setBigDecimal(8, decimal(delta, "usdcValue"));
            preparedStatement.setString(9, text(delta, "user"));
            preparedStatement.setString(10, text(delta, "destination"));
            preparedStatement.setBigDecimal(11, decimal(delta, "fee"));
            preparedStatement.setBigDecimal(12, decimal(delta, "nativeTokenFee"));
            preparedStatement.setObject(13, longValue(delta, "nonce"));
            preparedStatement.setString(14, textOrNull(delta, "feeToken"));
            preparedStatement.setObject(15, sqlTimestamp(instantFromEpochMillis(record.get("time"))));
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        Optional<UUID> ledgerId = ids.stream().findFirst();
        if (ledgerId.isEmpty()) {
            return false;
        }
        if (delta.has("fee") && !delta.get("fee").isNull()) {
            jdbcTemplate.update("""
                    INSERT INTO hyperliquid_fees (
                        raw_data_id, related_ledger_update_id, fee_type, fee_asset,
                        fee_amount, occurred_at, external_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (external_key) DO NOTHING
                    """, rawDataId, ledgerId.get(), ledgerFeeType(delta).name(), textOrNull(delta, "feeToken"),
                    decimal(delta, "fee"), sqlTimestamp(instantFromEpochMillis(record.get("time"))),
                    externalKey + "#fee");
        }
        return true;
    }

    private boolean saveSpotMetadata(UUID rawDataId, JsonNode response, Instant occurredAt) {
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO hyperliquid_spot_metadata_snapshots (raw_data_id, occurred_at)
                VALUES (?, ?)
                ON CONFLICT (raw_data_id) DO NOTHING
                RETURNING id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class), rawDataId, sqlTimestamp(occurredAt));
        Optional<UUID> snapshotId = ids.stream().findFirst();
        if (snapshotId.isEmpty()) {
            return false;
        }
        for (JsonNode token : response.path("tokens")) {
            jdbcTemplate.update("""
                    INSERT INTO hyperliquid_spot_tokens (
                        metadata_snapshot_id, token_index, name, size_decimals, wei_decimals,
                        token_id, is_canonical, full_name, raw_payload
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (metadata_snapshot_id, token_index) DO NOTHING
                    """, snapshotId.get(), integerValue(token, "index"), text(token, "name"),
                    integerValue(token, "szDecimals"), integerValue(token, "weiDecimals"),
                    text(token, "tokenId"), booleanValue(token, "isCanonical"), text(token, "fullName"),
                    token.toString());
        }
        for (JsonNode pair : response.path("universe")) {
            JsonNode tokens = pair.path("tokens");
            if (!tokens.isArray() || tokens.size() < 2) {
                continue;
            }
            jdbcTemplate.update("""
                    INSERT INTO hyperliquid_spot_pairs (
                        metadata_snapshot_id, pair_index, name, base_token_index,
                        quote_token_index, is_canonical, raw_payload
                    ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (metadata_snapshot_id, pair_index) DO NOTHING
                    """, snapshotId.get(), integerValue(pair, "index"), text(pair, "name"),
                    tokens.get(0).asInt(), tokens.get(1).asInt(), booleanValue(pair, "isCanonical"),
                    pair.toString());
        }
        return true;
    }

    private CoverageResult persistCoverage(
            UUID userId,
            CoreDomain.Dataset dataset,
            Instant requestedFrom,
            Instant requestedTo,
            Instant actualFrom,
            Instant actualTo,
            CoreDomain.DataCoverageStatus status,
            String reason,
            UUID importBatchId,
            int rawRows,
            int normalizedRows) {
        jdbcTemplate.update("""
                INSERT INTO data_import_statuses (
                    user_id, dataset, requested_from, requested_to, actual_from, actual_to,
                    status, reason, import_batch_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, dataset.name(), sqlTimestamp(requestedFrom), sqlTimestamp(requestedTo),
                sqlTimestamp(actualFrom), sqlTimestamp(actualTo),
                status.name(), reason, importBatchId);
        return new CoverageResult(dataset, status, rawRows, normalizedRows, reason);
    }

    private UUID insertImportBatch(UUID userId, CoreDomain.Dataset dataset, Instant requestedFrom, Instant requestedTo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO import_batches (user_id, source, dataset, requested_from, requested_to)
                VALUES (?, 'HYPERLIQUID', ?, ?, ?)
                RETURNING id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                userId, dataset.name(), sqlTimestamp(requestedFrom), sqlTimestamp(requestedTo));
    }

    private Optional<UUID> insertRawData(
            CoreDomain.Dataset dataset,
            String externalKey,
            Instant occurredAt,
            String payload,
            String payloadHash,
            UUID importBatchId) {
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO raw_data (
                    source, dataset, external_id, occurred_at, occurred_at_raw,
                    timezone_confidence, payload, payload_hash, import_batch_id
                ) VALUES ('HYPERLIQUID', ?, ?, ?, ?, 'UTC_FROM_EPOCH_MILLISECONDS', ?::jsonb, ?, ?)
                ON CONFLICT (source, dataset, payload_hash) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setString(1, dataset.name());
            preparedStatement.setString(2, externalKey);
            preparedStatement.setObject(3, sqlTimestamp(occurredAt));
            preparedStatement.setString(4, occurredAt == null ? null : occurredAt.toString());
            preparedStatement.setString(5, payload);
            preparedStatement.setString(6, payloadHash);
            preparedStatement.setObject(7, importBatchId);
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        if (!ids.isEmpty()) {
            return ids.stream().findFirst();
        }
        return jdbcTemplate.query("""
                SELECT id FROM raw_data
                WHERE source = 'HYPERLIQUID' AND dataset = ? AND payload_hash = ?
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class), dataset.name(), payloadHash)
                .stream().findFirst();
    }

    private AccountRow account(UUID accountId, UUID userId) {
        return jdbcTemplate.queryForObject("""
                SELECT account_address, account_mode
                FROM hyperliquid_accounts
                WHERE id = ? AND user_id = ?
                """, (resultSet, rowNum) -> new AccountRow(
                resultSet.getString("account_address"), resultSet.getString("account_mode")), accountId, userId);
    }

    private String payload(
            String type,
            String user,
            Instant requestedFrom,
            Instant requestedTo,
            JsonNode responseRecord) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("request", Map.of(
                "type", type,
                "user", user == null ? "" : user,
                "startTime", requestedFrom == null ? 0 : requestedFrom.toEpochMilli(),
                "endTime", requestedTo == null ? 0 : requestedTo.toEpochMilli()));
        value.put("response", responseRecord);
        return json(value);
    }

    private String externalKey(CoreDomain.Dataset dataset, JsonNode record) {
        return switch (dataset) {
            case HYPERLIQUID_FILLS -> firstNonBlank(
                    text(record, "tid"),
                    text(record, "hash") + "#" + text(record, "time") + "#" + text(record, "oid")
                            + "#" + text(record, "coin"));
            case HYPERLIQUID_FUNDING, HYPERLIQUID_LEDGER -> sha256(record.toString());
            default -> throw new IllegalArgumentException("Unsupported Hyperliquid dataset: " + dataset);
        };
    }

    private CoreDomain.FeeType feeType(JsonNode record) {
        return isSpotCoin(text(record, "coin"))
                ? CoreDomain.FeeType.HYPERLIQUID_SPOT
                : CoreDomain.FeeType.HYPERLIQUID_PERPETUAL;
    }

    private CoreDomain.FeeType ledgerFeeType(JsonNode delta) {
        return "withdrawal".equalsIgnoreCase(text(delta, "type"))
                ? CoreDomain.FeeType.HYPERLIQUID_WITHDRAWAL
                : CoreDomain.FeeType.OTHER;
    }

    private static boolean isSpotCoin(String coin) {
        return coin != null && (coin.startsWith("@") || coin.endsWith("/USDC"));
    }

    private static String ledgerSubtype(String type) {
        if ("deposit".equalsIgnoreCase(type)) {
            return "DEPOSIT";
        }
        if ("withdrawal".equalsIgnoreCase(type)) {
            return "WITHDRAWAL";
        }
        if ("spotTransfer".equalsIgnoreCase(type) || "transfer".equalsIgnoreCase(type)) {
            return "TRANSFER";
        }
        return "OTHER";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Hyperliquid data", exception);
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static boolean hasValue(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() && !node.get(field).asText().isBlank();
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isNumber() && !value.isTextual()
                ? null : Long.valueOf(value.asText());
    }

    private static Integer integerValue(JsonNode node, String field) {
        Long value = longValue(node, field);
        return value == null ? null : value.intValue();
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static Instant instantFromEpochMillis(JsonNode node) {
        Long value = longValue(node, "value");
        if (value == null && node != null && node.isNumber()) {
            value = node.longValue();
        }
        if (value == null && node != null && node.isTextual()) {
            value = Long.valueOf(node.asText());
        }
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private static Instant earlier(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static Timestamp sqlTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                result.append(String.format("%02x", valueByte));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ImportResult(Map<CoreDomain.Dataset, CoverageResult> datasets) {
    }

    public record CoverageResult(
            CoreDomain.Dataset dataset,
            CoreDomain.DataCoverageStatus status,
            int rawRows,
            int normalizedRows,
            String reason) {
    }

    private record AccountRow(String address, String accountMode) {
    }

    public static class UnsupportedAccountModeException extends RuntimeException {
        public UnsupportedAccountModeException(String accountMode) {
            super("Account mode is outside the MVP scope: " + accountMode);
        }
    }
}
