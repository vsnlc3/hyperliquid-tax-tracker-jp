package jp.hyperliquid.taxtracker.bitbank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class BitbankImportService {

    private final BitbankCsvParser parser;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public BitbankImportService(
            BitbankCsvParser parser,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ImportResult importCsv(UUID userId, String fileName, InputStream inputStream) throws IOException {
        BitbankCsvParser.ParsedFile parsedFile = parser.parse(inputStream);
        CoreDomain.Dataset dataset = parsedFile.type() == BitbankCsvParser.FileType.TRADES
                ? CoreDomain.Dataset.BITBANK_TRADES
                : CoreDomain.Dataset.BITBANK_WITHDRAWALS;
        UUID importBatchId = insertImportBatch(userId, dataset);

        int inserted = 0;
        int duplicates = 0;
        int solRows = 0;
        for (BitbankCsvParser.ParsedRow row : parsedFile.rows()) {
            String payload = payload(parsedFile.type(), fileName, row);
            String payloadHash = sha256(canonicalPayload(parsedFile.type(), row));
            Optional<UUID> rawDataId = insertRawData(
                    dataset,
                    row.sourceRecordId(),
                    row.occurredAtRaw(),
                    payload,
                    payloadHash,
                    importBatchId);
            if (rawDataId.isEmpty()) {
                duplicates++;
                continue;
            }

            inserted++;
            if (isMvpSolRow(row)) {
                solRows++;
            }
        }

        jdbcTemplate.update("""
                INSERT INTO data_import_statuses (
                    user_id, dataset, status, reason, import_batch_id
                ) VALUES (?, ?, 'COMPLETE', NULL, ?)
                """, userId, dataset.name(), importBatchId);

        return new ImportResult(importBatchId, dataset, parsedFile.rows().size(), inserted, duplicates, solRows);
    }

    private UUID insertImportBatch(UUID userId, CoreDomain.Dataset dataset) {
        String sql = """
                INSERT INTO import_batches (user_id, source, dataset)
                VALUES (?, 'BITBANK', ?)
                RETURNING id
                """;
        return jdbcTemplate.queryForObject(sql, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                userId, dataset.name());
    }

    private Optional<UUID> insertRawData(
            CoreDomain.Dataset dataset,
            String externalId,
            String occurredAtRaw,
            String payload,
            String payloadHash,
            UUID importBatchId) {
        String sql = """
                INSERT INTO raw_data (
                    source, dataset, external_id, occurred_at_raw, payload, payload_hash, import_batch_id
                )
                VALUES ('BITBANK', ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (source, dataset, payload_hash) DO NOTHING
                RETURNING id
                """;
        List<UUID> ids = jdbcTemplate.query(sql,
                preparedStatement -> {
                    preparedStatement.setString(1, dataset.name());
                    preparedStatement.setString(2, externalId);
                    preparedStatement.setString(3, occurredAtRaw);
                    preparedStatement.setString(4, payload);
                    preparedStatement.setString(5, payloadHash);
                    preparedStatement.setObject(6, importBatchId);
                },
                (resultSet, rowNum) -> resultSet.getObject("id", UUID.class));
        return ids.stream().findFirst();
    }

    private String payload(BitbankCsvParser.FileType type, String fileName, BitbankCsvParser.ParsedRow row) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "fileName", fileName,
                    "rowType", type.name(),
                    "columns", row.columns()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize bitbank CSV row", exception);
        }
    }

    private String canonicalPayload(BitbankCsvParser.FileType type, BitbankCsvParser.ParsedRow row) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "rowType", type.name(),
                    "columns", row.columns()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize canonical bitbank CSV row", exception);
        }
    }

    private static boolean isMvpSolRow(BitbankCsvParser.ParsedRow row) {
        if (row instanceof BitbankCsvParser.ParsedTrade trade) {
            return "sol_jpy".equalsIgnoreCase(trade.pair());
        }
        if (row instanceof BitbankCsvParser.ParsedWithdrawal withdrawal) {
            return "sol".equalsIgnoreCase(withdrawal.asset())
                    && "solana".equalsIgnoreCase(withdrawal.network());
        }
        return false;
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
            CoreDomain.Dataset dataset,
            int totalRows,
            int insertedRows,
            int duplicateRows,
            int mvpSolRows) {
    }
}
