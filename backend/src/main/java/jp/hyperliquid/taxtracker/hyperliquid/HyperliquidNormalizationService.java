package jp.hyperliquid.taxtracker.hyperliquid;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class HyperliquidNormalizationService {

    private static final String NORMALIZATION_VERSION = "hyperliquid-v1";

    private final JdbcTemplate jdbcTemplate;

    public HyperliquidNormalizationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public NormalizationResult normalize(UUID userId) {
        Map<UUID, UUID> fillTransactionIds = new HashMap<>();
        Set<String> accountAddresses = accountAddresses(userId);
        int normalized = 0;
        int skipped = 0;

        for (FillRow fill : fills(userId)) {
            Optional<SpotPair> pair = spotPair(fill.coin());
            UnifiedValues values = isSpot(fill.coin())
                    ? spotFill(fill, pair.orElse(null))
                    : perpetualFill(fill);
            InsertResult result = insertUnified(userId, values);
            fillTransactionIds.put(fill.id(), result.id());
            if (result.inserted()) {
                normalized++;
            } else {
                skipped++;
            }
        }

        for (FundingRow funding : fundings(userId)) {
            InsertResult result = insertUnified(userId, new UnifiedValues(
                    CoreDomain.Dataset.HYPERLIQUID_FUNDING,
                    "funding:" + funding.id(),
                    funding.occurredAt(),
                    CoreDomain.TransactionType.FUNDING,
                    null,
                    null,
                    null,
                    null,
                    funding.fundingAsset(),
                    funding.fundingAmount(),
                    null,
                    null,
                    null,
                    null,
                    funding.hash(),
                    funding.rawDataId(),
                    null,
                    null));
            if (result.inserted()) {
                normalized++;
            } else {
                skipped++;
            }
        }

        Map<UUID, UUID> ledgerTransactionIds = new HashMap<>();
        for (LedgerRow ledger : ledgers(userId)) {
            CoreDomain.TransactionType transactionType = ledgerTransactionType(ledger, accountAddresses);
            if (transactionType == null) {
                continue;
            }
            InsertResult result = insertUnified(userId, new UnifiedValues(
                    CoreDomain.Dataset.HYPERLIQUID_LEDGER,
                    "ledger:" + ledger.id(),
                    ledger.occurredAt(),
                    transactionType,
                    null,
                    null,
                    null,
                    null,
                    ledger.token(),
                    ledger.amount(),
                    null,
                    null,
                    ledger.userAddress(),
                    ledger.destinationAddress(),
                    ledger.hash(),
                    ledger.rawDataId(),
                    null,
                    null));
            ledgerTransactionIds.put(ledger.id(), result.id());
            if (result.inserted()) {
                normalized++;
            } else {
                skipped++;
            }
        }

        for (FeeRow fee : fees(userId)) {
            UUID relatedTransactionId = fee.relatedFillId() == null
                    ? ledgerTransactionIds.get(fee.relatedLedgerUpdateId())
                    : fillTransactionIds.get(fee.relatedFillId());
            InsertResult result = insertUnified(userId, new UnifiedValues(
                    fee.dataset(),
                    "fee:" + fee.id(),
                    fee.occurredAt(),
                    CoreDomain.TransactionType.FEE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    fee.feeAsset(),
                    fee.feeAmount(),
                    null,
                    null,
                    null,
                    fee.rawDataId(),
                    fee.feeType(),
                    relatedTransactionId));
            if (result.inserted()) {
                normalized++;
            } else {
                skipped++;
            }
        }

        return new NormalizationResult(normalized, skipped, NORMALIZATION_VERSION);
    }

    private UnifiedValues spotFill(FillRow fill, SpotPair pair) {
        boolean buy = "B".equalsIgnoreCase(fill.side()) || "BUY".equalsIgnoreCase(fill.side());
        BigDecimal quoteAmount = multiply(fill.size(), fill.price());
        String baseAsset = pair == null ? null : pair.baseAsset();
        String quoteAsset = pair == null ? null : pair.quoteAsset();
        return new UnifiedValues(
                CoreDomain.Dataset.HYPERLIQUID_FILLS,
                "fill:" + fill.id(),
                fill.occurredAt(),
                CoreDomain.TransactionType.SWAP,
                buy ? quoteAsset : baseAsset,
                buy ? quoteAmount : fill.size(),
                buy ? baseAsset : quoteAsset,
                buy ? fill.size() : quoteAmount,
                null,
                null,
                null,
                null,
                null,
                null,
                fill.hash(),
                fill.rawDataId(),
                null,
                null);
    }

    private UnifiedValues perpetualFill(FillRow fill) {
        return new UnifiedValues(
                CoreDomain.Dataset.HYPERLIQUID_FILLS,
                "fill:" + fill.id(),
                fill.occurredAt(),
                CoreDomain.TransactionType.PERP_FILL,
                null,
                null,
                null,
                null,
                fill.coin(),
                fill.size(),
                null,
                null,
                null,
                null,
                fill.hash(),
                fill.rawDataId(),
                null,
                null);
    }

    private InsertResult insertUnified(UUID userId, UnifiedValues values) {
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO unified_transactions (
                    user_id, source, dataset, source_record_id, occurred_at, occurred_at_raw,
                    transaction_type, asset_from, amount_from, asset_to, amount_to, asset,
                    gross_amount, net_amount, fee_asset, fee_amount, fee_type,
                    from_address, to_address, transaction_hash, raw_data_id,
                    related_transaction_id, normalization_version
                ) VALUES (?, 'HYPERLIQUID', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, dataset, source_record_id, normalization_version) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setObject(1, userId);
            preparedStatement.setString(2, values.dataset().name());
            preparedStatement.setString(3, values.sourceRecordId());
            preparedStatement.setObject(4, sqlTimestamp(values.occurredAt()));
            preparedStatement.setString(5, values.occurredAt() == null ? null : values.occurredAt().toString());
            preparedStatement.setString(6, values.transactionType().name());
            preparedStatement.setString(7, values.assetFrom());
            preparedStatement.setBigDecimal(8, values.amountFrom());
            preparedStatement.setString(9, values.assetTo());
            preparedStatement.setBigDecimal(10, values.amountTo());
            preparedStatement.setString(11, values.asset());
            preparedStatement.setBigDecimal(12, values.grossAmount());
            preparedStatement.setString(13, values.feeAsset());
            preparedStatement.setBigDecimal(14, values.feeAmount());
            preparedStatement.setString(15, values.feeType() == null ? null : values.feeType().name());
            preparedStatement.setString(16, values.fromAddress());
            preparedStatement.setString(17, values.toAddress());
            preparedStatement.setString(18, values.transactionHash());
            preparedStatement.setObject(19, values.rawDataId());
            preparedStatement.setObject(20, values.relatedTransactionId());
            preparedStatement.setString(21, NORMALIZATION_VERSION);
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        if (!ids.isEmpty()) {
            return new InsertResult(ids.get(0), true);
        }
        UUID existingId = jdbcTemplate.queryForObject("""
                SELECT id FROM unified_transactions
                WHERE source = 'HYPERLIQUID' AND dataset = ? AND source_record_id = ?
                  AND normalization_version = ?
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                values.dataset().name(), values.sourceRecordId(), NORMALIZATION_VERSION);
        return new InsertResult(existingId, false);
    }

    private List<FillRow> fills(UUID userId) {
        return jdbcTemplate.query("""
                SELECT f.id, f.raw_data_id, f.coin, f.side, f.size, f.price, f.occurred_at, f.hash
                FROM hyperliquid_fills f
                JOIN raw_data r ON r.id = f.raw_data_id
                JOIN import_batches b ON b.id = r.import_batch_id
                WHERE b.user_id = ?
                ORDER BY f.occurred_at, f.id
                """, (resultSet, rowNum) -> new FillRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("raw_data_id", UUID.class),
                resultSet.getString("coin"),
                resultSet.getString("side"),
                resultSet.getBigDecimal("size"),
                resultSet.getBigDecimal("price"),
                instant(resultSet.getTimestamp("occurred_at")),
                resultSet.getString("hash")), userId);
    }

    private List<FundingRow> fundings(UUID userId) {
        return jdbcTemplate.query("""
                SELECT f.id, f.raw_data_id, f.hash, f.occurred_at, f.funding_amount, f.funding_asset
                FROM hyperliquid_funding f
                JOIN raw_data r ON r.id = f.raw_data_id
                JOIN import_batches b ON b.id = r.import_batch_id
                WHERE b.user_id = ?
                ORDER BY f.occurred_at, f.id
                """, (resultSet, rowNum) -> new FundingRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("raw_data_id", UUID.class),
                resultSet.getString("hash"),
                instant(resultSet.getTimestamp("occurred_at")),
                resultSet.getBigDecimal("funding_amount"),
                resultSet.getString("funding_asset")), userId);
    }

    private List<LedgerRow> ledgers(UUID userId) {
        return jdbcTemplate.query("""
                SELECT l.id, l.raw_data_id, l.hash, l.subtype, l.token, l.amount,
                       l.user_address, l.destination_address, l.occurred_at
                FROM hyperliquid_ledger_updates l
                JOIN raw_data r ON r.id = l.raw_data_id
                JOIN import_batches b ON b.id = r.import_batch_id
                WHERE b.user_id = ?
                ORDER BY l.occurred_at, l.id
                """, (resultSet, rowNum) -> new LedgerRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("raw_data_id", UUID.class),
                resultSet.getString("hash"),
                resultSet.getString("subtype"),
                resultSet.getString("token"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("user_address"),
                resultSet.getString("destination_address"),
                instant(resultSet.getTimestamp("occurred_at"))), userId);
    }

    private List<FeeRow> fees(UUID userId) {
        return jdbcTemplate.query("""
                SELECT f.id, f.raw_data_id, f.related_fill_id, f.related_ledger_update_id,
                       r.dataset, f.fee_type, f.fee_asset, f.fee_amount, f.occurred_at
                FROM hyperliquid_fees f
                JOIN raw_data r ON r.id = f.raw_data_id
                JOIN import_batches b ON b.id = r.import_batch_id
                WHERE b.user_id = ?
                ORDER BY f.occurred_at, f.id
                """, (resultSet, rowNum) -> new FeeRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("raw_data_id", UUID.class),
                resultSet.getObject("related_fill_id", UUID.class),
                resultSet.getObject("related_ledger_update_id", UUID.class),
                CoreDomain.Dataset.valueOf(resultSet.getString("dataset")),
                CoreDomain.FeeType.valueOf(resultSet.getString("fee_type")),
                resultSet.getString("fee_asset"),
                resultSet.getBigDecimal("fee_amount"),
                instant(resultSet.getTimestamp("occurred_at"))), userId);
    }

    private Optional<SpotPair> spotPair(String coin) {
        if (!isSpot(coin) || !coin.startsWith("@")) {
            return Optional.empty();
        }
        int pairIndex;
        try {
            pairIndex = Integer.parseInt(coin.substring(1));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        List<SpotPair> pairs = jdbcTemplate.query("""
                SELECT p.base_token_index, p.quote_token_index,
                       base_token.name AS base_asset, quote_token.name AS quote_asset
                FROM hyperliquid_spot_pairs p
                JOIN hyperliquid_spot_metadata_snapshots s ON s.id = p.metadata_snapshot_id
                LEFT JOIN hyperliquid_spot_tokens base_token
                    ON base_token.metadata_snapshot_id = s.id
                   AND base_token.token_index = p.base_token_index
                LEFT JOIN hyperliquid_spot_tokens quote_token
                    ON quote_token.metadata_snapshot_id = s.id
                   AND quote_token.token_index = p.quote_token_index
                WHERE p.pair_index = ?
                ORDER BY s.created_at DESC
                LIMIT 1
                """, (resultSet, rowNum) -> new SpotPair(
                resultSet.getInt("base_token_index"),
                resultSet.getInt("quote_token_index"),
                resultSet.getString("base_asset"),
                resultSet.getString("quote_asset")), pairIndex);
        return pairs.stream().findFirst();
    }

    private static CoreDomain.TransactionType ledgerTransactionType(LedgerRow ledger, Set<String> accountAddresses) {
        return switch (ledger.subtype()) {
            case "DEPOSIT" -> CoreDomain.TransactionType.DEPOSIT;
            case "WITHDRAWAL" -> CoreDomain.TransactionType.WITHDRAWAL;
            case "TRANSFER" -> accountAddresses.contains(addressKey(ledger.userAddress()))
                    ? CoreDomain.TransactionType.TRANSFER_OUT
                    : accountAddresses.contains(addressKey(ledger.destinationAddress()))
                    ? CoreDomain.TransactionType.TRANSFER_IN
                    : null;
            default -> null;
        };
    }

    private Set<String> accountAddresses(UUID userId) {
        Set<String> addresses = new HashSet<>();
        for (String address : jdbcTemplate.query("""
                SELECT account_address FROM hyperliquid_accounts WHERE user_id = ?
                """, (resultSet, rowNum) -> resultSet.getString("account_address"), userId)) {
            addresses.add(addressKey(address));
        }
        return addresses;
    }

    private static String addressKey(String address) {
        return address == null ? null : address.toLowerCase(Locale.ROOT);
    }

    private static boolean isSpot(String coin) {
        return coin != null && (coin.startsWith("@") || coin.endsWith("/USDC"));
    }

    private static BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.multiply(right);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp sqlTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public record NormalizationResult(int normalizedRows, int skippedRows, String normalizationVersion) {
    }

    private record InsertResult(UUID id, boolean inserted) {
    }

    private record UnifiedValues(
            CoreDomain.Dataset dataset,
            String sourceRecordId,
            Instant occurredAt,
            CoreDomain.TransactionType transactionType,
            String assetFrom,
            BigDecimal amountFrom,
            String assetTo,
            BigDecimal amountTo,
            String asset,
            BigDecimal grossAmount,
            String feeAsset,
            BigDecimal feeAmount,
            String fromAddress,
            String toAddress,
            String transactionHash,
            UUID rawDataId,
            CoreDomain.FeeType feeType,
            UUID relatedTransactionId) {
    }

    private record FillRow(
            UUID id,
            UUID rawDataId,
            String coin,
            String side,
            BigDecimal size,
            BigDecimal price,
            Instant occurredAt,
            String hash) {
    }

    private record FundingRow(
            UUID id,
            UUID rawDataId,
            String hash,
            Instant occurredAt,
            BigDecimal fundingAmount,
            String fundingAsset) {
    }

    private record LedgerRow(
            UUID id,
            UUID rawDataId,
            String hash,
            String subtype,
            String token,
            BigDecimal amount,
            String userAddress,
            String destinationAddress,
            Instant occurredAt) {
    }

    private record FeeRow(
            UUID id,
            UUID rawDataId,
            UUID relatedFillId,
            UUID relatedLedgerUpdateId,
            CoreDomain.Dataset dataset,
            CoreDomain.FeeType feeType,
            String feeAsset,
            BigDecimal feeAmount,
            Instant occurredAt) {
    }

    private record SpotPair(
            int baseTokenIndex,
            int quoteTokenIndex,
            String baseAsset,
            String quoteAsset) {
    }
}
