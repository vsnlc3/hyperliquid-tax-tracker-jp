package jp.hyperliquid.taxtracker.api;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CoverageQueryService {

    private final JdbcTemplate jdbcTemplate;

    public CoverageQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CoreDomain.DataImportStatus> findLatest(UUID userId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (dataset)
                       id, user_id, dataset, requested_from, requested_to, actual_from, actual_to,
                       status, reason, import_batch_id, checked_at
                FROM data_import_statuses
                WHERE user_id = ?
                ORDER BY dataset, checked_at DESC, id DESC
                """, (resultSet, rowNum) -> new CoreDomain.DataImportStatus(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                CoreDomain.Dataset.valueOf(resultSet.getString("dataset")),
                instant(resultSet.getTimestamp("requested_from")),
                instant(resultSet.getTimestamp("requested_to")),
                instant(resultSet.getTimestamp("actual_from")),
                instant(resultSet.getTimestamp("actual_to")),
                CoreDomain.DataCoverageStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("reason"),
                resultSet.getObject("import_batch_id", UUID.class),
                instant(resultSet.getTimestamp("checked_at"))), userId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
