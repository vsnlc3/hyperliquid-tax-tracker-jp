package jp.hyperliquid.taxtracker.api;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TimelineService {

    private final JdbcTemplate jdbcTemplate;

    public TimelineService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TimelineRow> find(UUID userId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, source, dataset, source_record_id, occurred_at, transaction_type,
                       asset_from, amount_from, asset_to, amount_to, asset, gross_amount,
                       net_amount, fee_asset, fee_amount, fee_type, from_address, to_address,
                       transaction_hash, raw_data_id, related_transaction_id
                FROM unified_transactions
                WHERE user_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(userId);
        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            arguments.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at <= ?");
            arguments.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY occurred_at NULLS LAST, id");
        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> new TimelineRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("dataset"),
                resultSet.getString("source_record_id"),
                instant(resultSet.getTimestamp("occurred_at")),
                CoreDomain.TransactionType.valueOf(resultSet.getString("transaction_type")),
                resultSet.getString("asset_from"), resultSet.getBigDecimal("amount_from"),
                resultSet.getString("asset_to"), resultSet.getBigDecimal("amount_to"),
                resultSet.getString("asset"), resultSet.getBigDecimal("gross_amount"),
                resultSet.getBigDecimal("net_amount"), resultSet.getString("fee_asset"),
                resultSet.getBigDecimal("fee_amount"), resultSet.getString("fee_type"),
                resultSet.getString("from_address"), resultSet.getString("to_address"),
                resultSet.getString("transaction_hash"), resultSet.getObject("raw_data_id", UUID.class),
                resultSet.getObject("related_transaction_id", UUID.class)), arguments.toArray());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record TimelineRow(
            UUID id,
            String source,
            String dataset,
            String sourceRecordId,
            Instant occurredAt,
            CoreDomain.TransactionType transactionType,
            String assetFrom,
            java.math.BigDecimal amountFrom,
            String assetTo,
            java.math.BigDecimal amountTo,
            String asset,
            java.math.BigDecimal grossAmount,
            java.math.BigDecimal netAmount,
            String feeAsset,
            java.math.BigDecimal feeAmount,
            String feeType,
            String fromAddress,
            String toAddress,
            String transactionHash,
            UUID rawDataId,
            UUID relatedTransactionId) {
    }
}
