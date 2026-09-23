package jp.hyperliquid.taxtracker.costbasis;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class CostBasisSettingService {

    private final JdbcTemplate jdbcTemplate;

    public CostBasisSettingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CoreDomain.CostBasisSetting saveSetting(
            UUID userId,
            String asset,
            CoreDomain.CostBasisMethod method,
            LocalDate effectiveFrom) {
        validateSol(asset);
        if (method == null || effectiveFrom == null) {
            throw new IllegalArgumentException("Cost basis method and effective date are required");
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO cost_basis_settings (user_id, asset, method, effective_from)
                VALUES (?, 'SOL', ?, ?)
                RETURNING id, user_id, asset, method, effective_from, created_at
                """, (resultSet, rowNum) -> new CoreDomain.CostBasisSetting(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("asset"),
                CoreDomain.CostBasisMethod.valueOf(resultSet.getString("method")),
                resultSet.getDate("effective_from").toLocalDate(),
                resultSet.getTimestamp("created_at").toInstant()),
                userId, method.name(), Date.valueOf(effectiveFrom));
    }

    @Transactional
    public CoreDomain.CostBasisOpeningBalance saveOpeningBalance(
            UUID userId,
            String asset,
            LocalDate asOfDate,
            BigDecimal quantity,
            BigDecimal bookValueJpy,
            String inputSource) {
        validateSol(asset);
        if (asOfDate == null || inputSource == null || inputSource.isBlank()) {
            throw new IllegalArgumentException("Opening balance date and input source are required");
        }
        CostBasisValidation.validateNonNegative(quantity, "opening quantity");
        CostBasisValidation.validateNonNegative(bookValueJpy, "opening book value");
        return jdbcTemplate.queryForObject("""
                INSERT INTO cost_basis_opening_balances (
                    user_id, asset, as_of_date, quantity, book_value_jpy, input_source
                ) VALUES (?, 'SOL', ?, ?, ?, ?)
                ON CONFLICT (user_id, asset, as_of_date) DO UPDATE SET
                    quantity = EXCLUDED.quantity,
                    book_value_jpy = EXCLUDED.book_value_jpy,
                    input_source = EXCLUDED.input_source,
                    updated_at = now()
                RETURNING id, user_id, asset, as_of_date, quantity, book_value_jpy,
                          input_source, created_at, updated_at
                """, (resultSet, rowNum) -> new CoreDomain.CostBasisOpeningBalance(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("asset"),
                resultSet.getDate("as_of_date").toLocalDate(),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("book_value_jpy"),
                resultSet.getString("input_source"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()),
                userId, Date.valueOf(asOfDate), quantity, bookValueJpy, inputSource);
    }

    private static void validateSol(String asset) {
        if (asset == null || !"SOL".equalsIgnoreCase(asset)) {
            throw new IllegalArgumentException("Cost basis MVP supports SOL only");
        }
    }
}
