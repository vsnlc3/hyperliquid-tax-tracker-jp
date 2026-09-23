package jp.hyperliquid.taxtracker.review;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ClassificationReviewService {

    private final JdbcTemplate jdbcTemplate;

    public ClassificationReviewService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CoreDomain.ClassificationReview open(
            UUID userId,
            CoreDomain.ReviewType reviewType,
            UUID targetId,
            String reason) {
        if (userId == null || reviewType == null || targetId == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Review user, type, target, and reason are required");
        }
        List<CoreDomain.ClassificationReview> existing = jdbcTemplate.query("""
                SELECT id, user_id, review_type, target_id, status, reason, created_at, resolved_at
                FROM classification_reviews
                WHERE user_id = ? AND review_type = ? AND target_id = ? AND status = 'OPEN'
                ORDER BY created_at DESC
                LIMIT 1
                """, (resultSet, rowNum) -> map(resultSet), userId, reviewType.name(), targetId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO classification_reviews (user_id, review_type, target_id, reason)
                VALUES (?, ?, ?, ?)
                RETURNING id, user_id, review_type, target_id, status, reason, created_at, resolved_at
                """, (resultSet, rowNum) -> map(resultSet), userId, reviewType.name(), targetId, reason);
    }

    @Transactional
    public CoreDomain.ClassificationReview resolve(
            UUID userId,
            UUID reviewId,
            String beforeClassification,
            String afterClassification,
            String reason) {
        if (userId == null || reviewId == null || afterClassification == null || afterClassification.isBlank()) {
            throw new IllegalArgumentException("Review resolution requires user, review, and classification");
        }
        ReviewTarget target = jdbcTemplate.queryForObject("""
                SELECT review_type, target_id
                FROM classification_reviews
                WHERE id = ? AND user_id = ? AND status = 'OPEN'
                """, (resultSet, rowNum) -> new ReviewTarget(
                CoreDomain.ReviewType.valueOf(resultSet.getString("review_type")),
                resultSet.getObject("target_id", UUID.class)), reviewId, userId);
        jdbcTemplate.update("""
                INSERT INTO user_classifications (
                    user_id, target_type, target_id, before_classification, after_classification, reason
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, userId, target.reviewType() == CoreDomain.ReviewType.TRANSACTION_CLASSIFICATION
                        ? "TRANSACTION" : "TAX_EVENT", target.targetId(), beforeClassification,
                afterClassification, reason);
        jdbcTemplate.update("""
                UPDATE classification_reviews
                SET status = 'RESOLVED', resolved_at = now()
                WHERE id = ? AND user_id = ? AND status = 'OPEN'
                """, reviewId, userId);
        return jdbcTemplate.queryForObject("""
                SELECT id, user_id, review_type, target_id, status, reason, created_at, resolved_at
                FROM classification_reviews WHERE id = ?
                """, (resultSet, rowNum) -> map(resultSet), reviewId);
    }

    public List<CoreDomain.ClassificationReview> openReviews(UUID userId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, review_type, target_id, status, reason, created_at, resolved_at
                FROM classification_reviews
                WHERE user_id = ? AND status = 'OPEN'
                ORDER BY created_at, id
                """, (resultSet, rowNum) -> map(resultSet), userId);
    }

    private static CoreDomain.ClassificationReview map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new CoreDomain.ClassificationReview(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                CoreDomain.ReviewType.valueOf(resultSet.getString("review_type")),
                resultSet.getObject("target_id", UUID.class),
                CoreDomain.ReviewStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("reason"),
                timestamp(resultSet.getTimestamp("created_at")),
                timestamp(resultSet.getTimestamp("resolved_at")));
    }

    private static java.time.Instant timestamp(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ReviewTarget(CoreDomain.ReviewType reviewType, UUID targetId) {
    }
}
