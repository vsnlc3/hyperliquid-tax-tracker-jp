package jp.hyperliquid.taxtracker.review;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DataErrorService {

    private final JdbcTemplate jdbcTemplate;

    public DataErrorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CoreDomain.DataError open(
            UUID userId,
            CoreDomain.ErrorType errorType,
            CoreDomain.Dataset dataset,
            UUID targetId,
            String code,
            String message) {
        if (userId == null || errorType == null || code == null || code.isBlank()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Data error user, type, code, and message are required");
        }
        List<CoreDomain.DataError> existing = jdbcTemplate.query("""
                SELECT id, user_id, error_type, dataset, target_id, code, message,
                       status, created_at, resolved_at
                FROM data_errors
                WHERE user_id = ? AND error_type = ? AND code = ?
                  AND status = 'OPEN'
                  AND dataset IS NOT DISTINCT FROM ?
                  AND target_id IS NOT DISTINCT FROM ?
                ORDER BY created_at DESC
                LIMIT 1
                """, (resultSet, rowNum) -> map(resultSet), userId, errorType.name(), code,
                dataset == null ? null : dataset.name(), targetId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO data_errors (user_id, error_type, dataset, target_id, code, message)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, user_id, error_type, dataset, target_id, code, message,
                          status, created_at, resolved_at
                """, (resultSet, rowNum) -> map(resultSet), userId, errorType.name(),
                dataset == null ? null : dataset.name(), targetId, code, message);
    }

    public CoreDomain.DataError openDataCoverageError(
            UUID userId, CoreDomain.Dataset dataset, String code, String message) {
        return open(userId, CoreDomain.ErrorType.DATA_COVERAGE, dataset, null, code, message);
    }

    public CoreDomain.DataError openCalculationError(
            UUID userId, UUID targetId, String code, String message) {
        return open(userId, CoreDomain.ErrorType.CALCULATION, null, targetId, code, message);
    }

    public CoreDomain.DataError openImportError(
            UUID userId, CoreDomain.Dataset dataset, UUID targetId, String code, String message) {
        return open(userId, CoreDomain.ErrorType.IMPORT, dataset, targetId, code, message);
    }

    @Transactional
    public CoreDomain.DataError resolve(UUID userId, UUID errorId) {
        int updated = jdbcTemplate.update("""
                UPDATE data_errors
                SET status = 'RESOLVED', resolved_at = now()
                WHERE id = ? AND user_id = ? AND status = 'OPEN'
                """, errorId, userId);
        if (updated == 0) {
            throw new IllegalArgumentException("Open data error was not found");
        }
        return jdbcTemplate.queryForObject("""
                SELECT id, user_id, error_type, dataset, target_id, code, message,
                       status, created_at, resolved_at
                FROM data_errors WHERE id = ?
                """, (resultSet, rowNum) -> map(resultSet), errorId);
    }

    public List<CoreDomain.DataError> openErrors(UUID userId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, error_type, dataset, target_id, code, message,
                       status, created_at, resolved_at
                FROM data_errors
                WHERE user_id = ? AND status = 'OPEN'
                ORDER BY created_at, id
                """, (resultSet, rowNum) -> map(resultSet), userId);
    }

    private static CoreDomain.DataError map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        String dataset = resultSet.getString("dataset");
        return new CoreDomain.DataError(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                CoreDomain.ErrorType.valueOf(resultSet.getString("error_type")),
                dataset == null ? null : CoreDomain.Dataset.valueOf(dataset),
                resultSet.getObject("target_id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("message"),
                CoreDomain.ErrorStatus.valueOf(resultSet.getString("status")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("resolved_at")));
    }

    private static java.time.Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
