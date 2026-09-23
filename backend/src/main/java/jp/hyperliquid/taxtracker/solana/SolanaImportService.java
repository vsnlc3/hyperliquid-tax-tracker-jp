package jp.hyperliquid.taxtracker.solana;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SolanaImportService {

    private static final String NORMALIZATION_VERSION = "solana-v1";
    private static final BigDecimal LAMPORTS_PER_SOL = new BigDecimal("1000000000");
    private static final int HISTORY_PAGE_LIMIT = 1_000;

    private final SolanaRpcClient rpcClient;
    private final SolanaTransactionParser transactionParser;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public SolanaImportService(
            SolanaRpcClient rpcClient,
            SolanaTransactionParser transactionParser,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.rpcClient = rpcClient;
        this.transactionParser = transactionParser;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ImportResult importWallet(UUID userId, UUID walletId, Instant requestedFrom, Instant requestedTo) {
        if (requestedFrom == null || requestedTo == null || requestedFrom.isAfter(requestedTo)) {
            throw new IllegalArgumentException("Solana import requires a valid requested time range");
        }
        WalletRow wallet = wallet(walletId, userId);
        UUID importBatchId = insertImportBatch(userId, requestedFrom, requestedTo);
        HistoryResult history;
        try {
            history = fetchHistory(wallet.address(), requestedFrom, requestedTo);
        } catch (RuntimeException exception) {
            persistCoverage(userId, requestedFrom, requestedTo, null, null,
                    CoreDomain.DataCoverageStatus.FAILED, exception.getMessage(), importBatchId);
            return new ImportResult(importBatchId, CoreDomain.DataCoverageStatus.FAILED, 0, 0, 0,
                    exception.getMessage());
        }

        int rawInserted = 0;
        int transactionInserted = 0;
        int failedTransactions = 0;
        Instant actualFrom = null;
        Instant actualTo = null;
        List<String> errors = new ArrayList<>(history.errors());
        for (SignatureRow signatureRow : history.rows()) {
            JsonNode transactionResponse;
            try {
                transactionResponse = rpcClient.getTransaction(signatureRow.signature());
            } catch (RuntimeException exception) {
                errors.add(signatureRow.signature() + ": " + exception.getMessage());
                continue;
            }

            SolanaTransactionParser.ParsedTransaction parsed = transactionParser.parse(
                    signatureRow.signature(), transactionResponse, signatureRow.raw());
            if (parsed.blockTime() != null) {
                actualFrom = earlier(actualFrom, parsed.blockTime());
                actualTo = later(actualTo, parsed.blockTime());
            }
            if (parsed.status() == CoreDomain.SolanaTransactionStatus.NOT_FOUND
                    || parsed.status() == CoreDomain.SolanaTransactionStatus.UNKNOWN) {
                errors.add(signatureRow.signature() + ": transaction body was not available");
            }
            if (parsed.status() == CoreDomain.SolanaTransactionStatus.FAILED) {
                failedTransactions++;
            }

            String payload = payload(signatureRow.raw(), transactionResponse);
            Optional<UUID> rawDataId = insertRawData(
                    signatureRow.signature(),
                    parsed.blockTime(),
                    payload,
                    importBatchId);
            if (rawDataId.isEmpty()) {
                continue;
            }
            rawInserted++;
            if (saveTransaction(userId, wallet.address(), rawDataId.get(), parsed)) {
                transactionInserted++;
            }
        }

        CoreDomain.DataCoverageStatus coverageStatus;
        if (history.rows().isEmpty() && !history.errors().isEmpty()) {
            coverageStatus = CoreDomain.DataCoverageStatus.FAILED;
        } else if (!errors.isEmpty()) {
            coverageStatus = CoreDomain.DataCoverageStatus.PARTIAL;
        } else {
            coverageStatus = CoreDomain.DataCoverageStatus.COMPLETE;
        }
        String reason = errors.isEmpty() ? null : String.join("; ", errors);
        persistCoverage(userId, requestedFrom, requestedTo, actualFrom, actualTo,
                coverageStatus, reason, importBatchId);
        return new ImportResult(importBatchId, coverageStatus, rawInserted, transactionInserted,
                failedTransactions, reason);
    }

    private HistoryResult fetchHistory(String address, Instant requestedFrom, Instant requestedTo) {
        List<SignatureRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String before = null;
        boolean reachedRequestedFrom = false;
        while (!reachedRequestedFrom) {
            JsonNode response = rpcClient.getSignaturesForAddress(address, before);
            JsonNode result = response.path("result");
            if (!result.isArray() || result.isEmpty()) {
                break;
            }
            String lastSignature = null;
            for (JsonNode summary : result) {
                String signature = text(summary, "signature");
                if (signature == null) {
                    errors.add("Signature history contained a row without signature");
                    continue;
                }
                lastSignature = signature;
                Instant blockTime = instantFromEpochSeconds(summary.get("blockTime"));
                if (blockTime == null) {
                    errors.add(signature + ": signature history blockTime was missing");
                }
                if (blockTime != null && blockTime.isBefore(requestedFrom)) {
                    reachedRequestedFrom = true;
                    break;
                }
                if (blockTime == null || !blockTime.isAfter(requestedTo)) {
                    rows.add(new SignatureRow(signature, blockTime, summary));
                }
            }
            if (reachedRequestedFrom || result.size() < HISTORY_PAGE_LIMIT) {
                break;
            }
            if (lastSignature == null || lastSignature.equals(before)) {
                errors.add("Signature history pagination did not advance");
                break;
            }
            before = lastSignature;
        }
        return new HistoryResult(rows, errors);
    }

    private boolean saveTransaction(
            UUID userId,
            String walletAddress,
            UUID rawDataId,
            SolanaTransactionParser.ParsedTransaction parsed) {
        String signerAddresses = json(parsed.signerAddresses());
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO solana_transactions (
                    raw_data_id, signature, slot, block_time, status, signer_addresses,
                    version, network_fee_lamports, priority_fee_lamports, normalization_version
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (signature) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setObject(1, rawDataId);
            preparedStatement.setString(2, parsed.signature());
            preparedStatement.setObject(3, parsed.slot());
            preparedStatement.setObject(4, parsed.blockTime());
            preparedStatement.setString(5, parsed.status().name());
            preparedStatement.setString(6, signerAddresses);
            preparedStatement.setString(7, parsed.version());
            preparedStatement.setObject(8, parsed.networkFeeLamports());
            preparedStatement.setObject(9, parsed.priorityFeeLamports());
            preparedStatement.setString(10, NORMALIZATION_VERSION);
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        Optional<UUID> transactionId = ids.stream().findFirst();
        if (transactionId.isEmpty()) {
            return false;
        }

        int instructionIndex = 0;
        for (CoreDomain.SystemTransfer transfer : parsed.systemTransfers()) {
            jdbcTemplate.update("""
                    INSERT INTO solana_system_transfers (
                        solana_transaction_id, instruction_index, source_address, destination_address, lamports
                    ) VALUES (?, ?, ?, ?, ?)
                    """, transactionId.get(), instructionIndex++, transfer.sourceAddress(),
                    transfer.destinationAddress(), transfer.lamports());
            if (parsed.status() == CoreDomain.SolanaTransactionStatus.SUCCESS) {
                saveNativeTransfer(userId, walletAddress, rawDataId, parsed, transfer, instructionIndex - 1);
            }
        }
        for (CoreDomain.TokenTransfer transfer : parsed.tokenTransfers()) {
            jdbcTemplate.update("""
                    INSERT INTO solana_token_transfers (
                        solana_transaction_id, instruction_index, source_address, destination_address,
                        mint, raw_amount, decimals, ui_amount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, transactionId.get(), instructionIndex++, transfer.sourceAddress(),
                    transfer.destinationAddress(), transfer.mint(), transfer.rawAmount(), transfer.decimals(),
                    transfer.uiAmount());
        }
        if (parsed.networkFeeLamports() != null && parsed.networkFeeLamports() > 0
                && parsed.signerAddresses().contains(walletAddress)) {
            saveNetworkFee(userId, walletAddress, rawDataId, parsed);
        }
        return true;
    }

    private void saveNativeTransfer(
            UUID userId,
            String walletAddress,
            UUID rawDataId,
            SolanaTransactionParser.ParsedTransaction parsed,
            CoreDomain.SystemTransfer transfer,
            int instructionIndex) {
        boolean incoming = walletAddress.equals(transfer.destinationAddress());
        boolean outgoing = walletAddress.equals(transfer.sourceAddress());
        if (!incoming && !outgoing) {
            return;
        }
        String sourceRecordId = parsed.signature() + "#system-" + instructionIndex;
        jdbcTemplate.update("""
                INSERT INTO unified_transactions (
                    user_id, source, dataset, source_record_id, occurred_at, occurred_at_raw,
                    transaction_type, asset, gross_amount, net_amount, from_address, to_address,
                    transaction_hash, raw_data_id, normalization_version
                ) VALUES (?, 'SOLANA', 'SOLANA_TRANSACTIONS', ?, ?, ?, ?, 'SOL', ?, NULL, ?, ?, ?, ?, ?)
                ON CONFLICT (source, dataset, source_record_id) DO NOTHING
                """, userId, sourceRecordId, parsed.blockTime(), rawTimestamp(parsed.blockTime()),
                incoming ? "TRANSFER_IN" : "TRANSFER_OUT",
                new BigDecimal(transfer.lamports()).divide(LAMPORTS_PER_SOL),
                transfer.sourceAddress(), transfer.destinationAddress(), parsed.signature(), rawDataId,
                NORMALIZATION_VERSION);
    }

    private void saveNetworkFee(
            UUID userId,
            String walletAddress,
            UUID rawDataId,
            SolanaTransactionParser.ParsedTransaction parsed) {
        jdbcTemplate.update("""
                INSERT INTO unified_transactions (
                    user_id, source, dataset, source_record_id, occurred_at, occurred_at_raw,
                    transaction_type, asset, fee_asset, fee_amount, fee_type, from_address,
                    transaction_hash, raw_data_id, normalization_version
                ) VALUES (?, 'SOLANA', 'SOLANA_TRANSACTIONS', ?, ?, ?, 'FEE', 'SOL', 'SOL', ?,
                    'SOLANA_NETWORK', ?, ?, ?, ?)
                ON CONFLICT (source, dataset, source_record_id) DO NOTHING
                """, userId,
                parsed.signature() + "#network-fee",
                parsed.blockTime(),
                rawTimestamp(parsed.blockTime()),
                walletAddress,
                new BigDecimal(parsed.networkFeeLamports()).divide(LAMPORTS_PER_SOL),
                CoreDomain.FeeType.SOLANA_NETWORK.name(),
                walletAddress,
                parsed.signature(),
                rawDataId,
                NORMALIZATION_VERSION);
    }

    private UUID insertImportBatch(UUID userId, Instant requestedFrom, Instant requestedTo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO import_batches (user_id, source, dataset, requested_from, requested_to)
                VALUES (?, 'SOLANA', 'SOLANA_TRANSACTIONS', ?, ?)
                RETURNING id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                userId, requestedFrom, requestedTo);
    }

    private Optional<UUID> insertRawData(
            String signature,
            Instant blockTime,
            String payload,
            UUID importBatchId) {
        String hash = sha256(payload);
        List<UUID> ids = jdbcTemplate.query("""
                INSERT INTO raw_data (
                    source, dataset, external_id, occurred_at, occurred_at_raw,
                    timezone_confidence, payload, payload_hash, import_batch_id
                ) VALUES ('SOLANA', 'SOLANA_TRANSACTIONS', ?, ?, ?, 'UTC_FROM_BLOCK_TIME', ?::jsonb, ?, ?)
                ON CONFLICT (source, dataset, payload_hash) DO NOTHING
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setString(1, signature);
            preparedStatement.setObject(2, blockTime);
            preparedStatement.setString(3, rawTimestamp(blockTime));
            preparedStatement.setString(4, payload);
            preparedStatement.setString(5, hash);
            preparedStatement.setObject(6, importBatchId);
        }, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        if (!ids.isEmpty()) {
            return ids.stream().findFirst();
        }
        return jdbcTemplate.query("""
                SELECT id FROM raw_data
                WHERE source = 'SOLANA' AND dataset = 'SOLANA_TRANSACTIONS' AND payload_hash = ?
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class), hash)
                .stream().findFirst();
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
                ) VALUES (?, 'SOLANA_TRANSACTIONS', ?, ?, ?, ?, ?, ?, ?)
                """, userId, requestedFrom, requestedTo, actualFrom, actualTo, status.name(), reason, importBatchId);
    }

    private WalletRow wallet(UUID walletId, UUID userId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, address FROM wallets WHERE id = ? AND user_id = ?
                """, (resultSet, rowNum) -> new WalletRow(
                resultSet.getObject("id", UUID.class), resultSet.getString("address")), walletId, userId);
    }

    private String payload(JsonNode summary, JsonNode transactionResponse) {
        Map<String, JsonNode> value = new LinkedHashMap<>();
        value.put("signatureSummary", summary);
        value.put("transactionResponse", transactionResponse);
        return json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Solana data", exception);
        }
    }

    private static String rawTimestamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instantFromEpochSeconds(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return Instant.ofEpochSecond(node.longValue());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static Instant earlier(Instant current, Instant candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
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

    public record ImportResult(
            UUID importBatchId,
            CoreDomain.DataCoverageStatus coverageStatus,
            int rawRows,
            int transactionRows,
            int failedTransactions,
            String coverageReason) {
    }

    private record WalletRow(UUID id, String address) {
    }

    private record SignatureRow(String signature, Instant blockTime, JsonNode raw) {
    }

    private record HistoryResult(List<SignatureRow> rows, List<String> errors) {
    }
}
