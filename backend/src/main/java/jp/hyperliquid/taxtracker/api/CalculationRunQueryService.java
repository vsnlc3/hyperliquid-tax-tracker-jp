package jp.hyperliquid.taxtracker.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CalculationRunQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CalculationRunQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RunView find(UUID userId, UUID runId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, user_id, target_year, cost_basis_method, opening_balance_id,
                       tax_rule_version, normalization_version, status, blocked_reasons, calculated_at
                FROM calculation_runs
                WHERE id = ? AND user_id = ?
                """, (resultSet, rowNum) -> {
            List<String> blockedReasonNames = readBlockedReasons(resultSet.getString("blocked_reasons"));
            Map<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> coverage = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT dataset, status FROM calculation_run_coverage
                    WHERE calculation_run_id = ? ORDER BY dataset
                    """, (coverageResultSet, coverageRowNum) -> {
                coverage.put(CoreDomain.Dataset.valueOf(coverageResultSet.getString("dataset")),
                        CoreDomain.DataCoverageStatus.valueOf(coverageResultSet.getString("status")));
                return null;
            }, resultSet.getObject("id", UUID.class));
            Set<UUID> priceSnapshotIds = jdbcTemplate.query("""
                    SELECT price_snapshot_id FROM calculation_run_price_snapshots
                    WHERE calculation_run_id = ?
                    """, (priceResultSet, priceRowNum) -> priceResultSet.getObject("price_snapshot_id", UUID.class),
                    resultSet.getObject("id", UUID.class)).stream().collect(Collectors.toUnmodifiableSet());
            return new RunView(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("user_id", UUID.class),
                    resultSet.getInt("target_year"),
                    CoreDomain.CostBasisMethod.valueOf(resultSet.getString("cost_basis_method")),
                    resultSet.getObject("opening_balance_id", UUID.class),
                    resultSet.getString("tax_rule_version"),
                    resultSet.getString("normalization_version"),
                    coverage,
                    priceSnapshotIds,
                    CoreDomain.CalculationRunStatus.valueOf(resultSet.getString("status")),
                    blockedReasonNames,
                    resultSet.getTimestamp("calculated_at") == null
                            ? null : resultSet.getTimestamp("calculated_at").toInstant());
        }, runId, userId);
    }

    private List<String> readBlockedReasons(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid calculation blocked reasons", exception);
        }
    }

    public record RunView(
            UUID id,
            UUID userId,
            int targetYear,
            CoreDomain.CostBasisMethod costBasisMethod,
            UUID openingBalanceId,
            String taxRuleVersion,
            String normalizationVersion,
            Map<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> coverage,
            Set<UUID> priceSnapshotIds,
            CoreDomain.CalculationRunStatus status,
            List<String> blockedReasons,
            Instant calculatedAt) {
    }
}
