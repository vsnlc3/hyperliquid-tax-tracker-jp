package jp.hyperliquid.taxtracker.bitbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class BitbankNormalizationService {

    private static final String NORMALIZATION_VERSION = "bitbank-v1";

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public BitbankNormalizationService(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public NormalizationResult normalize(UUID userId) {
        List<RawRow> rawRows = jdbcTemplate.query("""
                SELECT r.id, r.dataset, r.external_id, r.occurred_at_raw, r.payload
                FROM raw_data r
                JOIN import_batches b ON b.id = r.import_batch_id
                WHERE r.source = 'BITBANK'
                  AND b.user_id = ?
                ORDER BY r.imported_at, r.id
                """, (resultSet, rowNum) -> new RawRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("dataset"),
                resultSet.getString("external_id"),
                resultSet.getString("occurred_at_raw"),
                resultSet.getString("payload")), userId);

        int normalizedRows = 0;
        int skippedRows = 0;
        for (RawRow rawRow : rawRows) {
            JsonNode payload = readPayload(rawRow.payload());
            JsonNode columns = payload.path("columns");
            String rowType = text(payload, "rowType");
            if ("TRADES".equals(rowType) && isSolBuy(columns)) {
                if (alreadyNormalized(rawRow, rawRow.externalId())) {
                    skippedRows++;
                    continue;
                }
                UUID buyId = insertBuy(userId, rawRow, columns);
                insertFeeIfPresent(userId, rawRow, columns, buyId, "発生手数料", CoreDomain.FeeType.BITBANK_PURCHASE);
                normalizedRows++;
            } else if ("WITHDRAWALS".equals(rowType) && isCompletedSolWithdrawal(columns)) {
                if (alreadyNormalized(rawRow, rawRow.externalId())) {
                    skippedRows++;
                    continue;
                }
                UUID transferId = insertWithdrawal(userId, rawRow, columns);
                insertFeeIfPresent(userId, rawRow, columns, transferId, "手数料", CoreDomain.FeeType.BITBANK_WITHDRAWAL);
                normalizedRows++;
            }
        }

        return new NormalizationResult(rawRows.size(), normalizedRows, skippedRows, NORMALIZATION_VERSION);
    }

    private UUID insertBuy(UUID userId, RawRow rawRow, JsonNode columns) {
        BigDecimal quantity = decimal(columns, "数量");
        BigDecimal price = decimal(columns, "価格");
        String sourceRecordId = sourceRecordId(rawRow);
        return jdbcTemplate.queryForObject("""
                INSERT INTO unified_transactions (
                    user_id, source, dataset, source_record_id, occurred_at_raw, transaction_type,
                    asset_from, amount_from, asset_to, amount_to, asset, fee_asset, fee_amount,
                    fee_type, raw_data_id, normalization_version
                )
                VALUES (?, 'BITBANK', 'BITBANK_TRADES', ?, ?, 'BUY',
                        'JPY', ?, 'SOL', ?, 'SOL', NULL, ?, 'BITBANK_PURCHASE', ?, ?)
                RETURNING id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                userId,
                sourceRecordId,
                rawRow.occurredAtRaw(),
                quantity.multiply(price),
                quantity,
                decimal(columns, "発生手数料"),
                rawRow.rawDataId(),
                NORMALIZATION_VERSION);
    }

    private UUID insertWithdrawal(UUID userId, RawRow rawRow, JsonNode columns) {
        String sourceRecordId = sourceRecordId(rawRow);
        return jdbcTemplate.queryForObject("""
                INSERT INTO unified_transactions (
                    user_id, source, dataset, source_record_id, occurred_at_raw, transaction_type,
                    asset, gross_amount, net_amount, fee_asset, fee_amount, fee_type,
                    to_address, transaction_hash, raw_data_id, normalization_version
                )
                VALUES (?, 'BITBANK', 'BITBANK_WITHDRAWALS', ?, ?, 'TRANSFER_OUT',
                        'SOL', ?, NULL, NULL, ?, 'BITBANK_WITHDRAWAL', ?, ?, ?, ?)
                RETURNING id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                userId,
                sourceRecordId,
                rawRow.occurredAtRaw(),
                decimal(columns, "数量"),
                decimal(columns, "手数料"),
                text(columns, "アドレス"),
                text(columns, "Txid"),
                rawRow.rawDataId(),
                NORMALIZATION_VERSION);
    }

    private void insertFeeIfPresent(
            UUID userId,
            RawRow rawRow,
            JsonNode columns,
            UUID relatedTransactionId,
            String feeColumn,
            CoreDomain.FeeType feeType) {
        BigDecimal feeAmount = decimal(columns, feeColumn);
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO unified_transactions (
                    user_id, source, dataset, source_record_id, occurred_at_raw, transaction_type,
                    asset, fee_asset, fee_amount, fee_type, raw_data_id,
                    related_transaction_id, normalization_version
                )
                VALUES (?, 'BITBANK', ?, ?, ?, 'FEE', NULL, NULL, ?, ?, ?, ?, ?)
                """,
                userId,
                rawRow.dataset(),
                sourceRecordId(rawRow) + "#fee",
                rawRow.occurredAtRaw(),
                feeAmount,
                feeType.name(),
                rawRow.rawDataId(),
                relatedTransactionId,
                NORMALIZATION_VERSION);
    }

    private boolean alreadyNormalized(RawRow rawRow, String externalId) {
        String sourceRecordId = externalId == null || externalId.isBlank()
                ? rawRow.rawDataId().toString()
                : externalId;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM unified_transactions
                WHERE source = 'BITBANK'
                  AND dataset = ?
                  AND source_record_id = ?
                  AND normalization_version = ?
                """, Integer.class, rawRow.dataset(), sourceRecordId, NORMALIZATION_VERSION);
        return count != null && count > 0;
    }

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid bitbank RawData payload", exception);
        }
    }

    private static boolean isSolBuy(JsonNode columns) {
        return "sol_jpy".equalsIgnoreCase(text(columns, "通貨ペア"))
                && "buy".equalsIgnoreCase(text(columns, "売/買"));
    }

    private static boolean isCompletedSolWithdrawal(JsonNode columns) {
        return "sol".equalsIgnoreCase(text(columns, "コイン"))
                && "solana".equalsIgnoreCase(text(columns, "ネットワーク"))
                && "DONE".equalsIgnoreCase(text(columns, "ステータス"));
    }

    private static String sourceRecordId(RawRow rawRow) {
        return rawRow.externalId() == null || rawRow.externalId().isBlank()
                ? rawRow.rawDataId().toString()
                : rawRow.externalId();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private record RawRow(
            UUID rawDataId,
            String dataset,
            String externalId,
            String occurredAtRaw,
            String payload) {
    }

    public record NormalizationResult(
            int rawRows,
            int normalizedRows,
            int skippedRows,
            String normalizationVersion) {
    }
}
