package jp.hyperliquid.taxtracker.review;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RecalculationRequestService {

    private final JdbcTemplate jdbcTemplate;

    public RecalculationRequestService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CoreDomain.RecalculationRequest request(
            UUID userId, String sourceType, UUID sourceId, String reason) {
        if (userId == null || sourceType == null || sourceType.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Recalculation request fields are required");
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO recalculation_requests (user_id, source_type, source_id, reason)
                VALUES (?, ?, ?, ?)
                RETURNING id, user_id, source_type, source_id, reason, status, created_at, processed_at
                """, (resultSet, rowNum) -> map(resultSet), userId, sourceType, sourceId, reason);
    }

    @Transactional
    public CoreDomain.RecalculationRequest markProcessed(UUID userId, UUID requestId) {
        int updated = jdbcTemplate.update("""
                UPDATE recalculation_requests
                SET status = 'PROCESSED', processed_at = now()
                WHERE id = ? AND user_id = ? AND status = 'PENDING'
                """, requestId, userId);
        if (updated == 0) {
            throw new IllegalArgumentException("Pending recalculation request was not found");
        }
        return jdbcTemplate.queryForObject("""
                SELECT id, user_id, source_type, source_id, reason, status, created_at, processed_at
                FROM recalculation_requests WHERE id = ?
                """, (resultSet, rowNum) -> map(resultSet), requestId);
    }

    private static CoreDomain.RecalculationRequest map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new CoreDomain.RecalculationRequest(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("source_type"),
                resultSet.getObject("source_id", UUID.class),
                resultSet.getString("reason"),
                CoreDomain.RecalculationStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("processed_at") == null
                        ? null : resultSet.getTimestamp("processed_at").toInstant());
    }
}
