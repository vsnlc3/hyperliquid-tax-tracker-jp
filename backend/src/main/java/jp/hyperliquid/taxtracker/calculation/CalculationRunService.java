package jp.hyperliquid.taxtracker.calculation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CalculationRunService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CalculationRunEvaluator evaluator;

    public CalculationRunService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CalculationRunEvaluator evaluator) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.evaluator = evaluator;
    }

    @Transactional
    public RunResult createRun(UUID userId, RunInput input) {
        validateInput(userId, input);
        CalculationRunEvaluator.EvaluationResult evaluation = evaluator.evaluate(
                new CalculationRunEvaluator.EvaluationInput(
                        input.costBasisMethod(), input.requiredDatasets(), input.coverage(),
                        input.requiredPriceMissing(), input.unresolvedTaxEventReview(),
                        input.unresolvedCalculationError(), input.unresolvedImportError(),
                        input.finalizeRequested()));
        UUID runId = jdbcTemplate.queryForObject("""
                INSERT INTO calculation_runs (
                    user_id, target_year, cost_basis_method, opening_balance_id,
                    tax_rule_version, normalization_version, status, blocked_reasons, calculated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                userId, input.targetYear(), input.costBasisMethod().name(), input.openingBalanceId(),
                input.taxRuleVersion(), input.normalizationVersion(), evaluation.status().name(),
                blockedReasonsJson(evaluation.blockedReasons()), Timestamp.from(Instant.now()));
        persistCoverage(runId, input);
        persistPriceSnapshots(runId, input.priceSnapshotIds());
        return new RunResult(runId, evaluation.status(), evaluation.blockedReasons());
    }

    @Transactional
    public void persistTaxEvents(UUID runId, String taxRuleVersion, List<TaxEventInput> events) {
        if (runId == null || taxRuleVersion == null || taxRuleVersion.isBlank()) {
            throw new IllegalArgumentException("Tax event persistence requires run and tax rule version");
        }
        for (TaxEventInput event : events == null ? List.<TaxEventInput>of() : events) {
            jdbcTemplate.update("""
                    INSERT INTO tax_events (
                        calculation_run_id, source_record_id, event_type, tax_status, asset,
                        quantity, jpy_value, cost_basis_jpy, profit_loss_jpy, tax_rule_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, runId, event.sourceRecordId(), event.eventType().name(), event.taxStatus().name(),
                    event.asset(), event.quantity(), event.jpyValue(), event.costBasisJpy(),
                    event.profitLossJpy(), taxRuleVersion);
        }
    }

    private void persistCoverage(UUID runId, RunInput input) {
        for (Map.Entry<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> entry : input.coverage().entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO calculation_run_coverage (calculation_run_id, dataset, status)
                    VALUES (?, ?, ?)
                    ON CONFLICT (calculation_run_id, dataset) DO UPDATE SET status = EXCLUDED.status
                    """, runId, entry.getKey().name(), entry.getValue().name());
        }
    }

    private void persistPriceSnapshots(UUID runId, Set<UUID> priceSnapshotIds) {
        for (UUID priceSnapshotId : priceSnapshotIds == null ? Set.<UUID>of() : priceSnapshotIds) {
            jdbcTemplate.update("""
                    INSERT INTO calculation_run_price_snapshots (calculation_run_id, price_snapshot_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, runId, priceSnapshotId);
        }
    }

    private String blockedReasonsJson(Set<CoreDomain.BlockedReason> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons.stream().map(Enum::name).toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize calculation blocked reasons", exception);
        }
    }

    private static void validateInput(UUID userId, RunInput input) {
        if (userId == null || input == null || input.targetYear() < 2000 || input.targetYear() > 2200
                || input.costBasisMethod() == null || input.taxRuleVersion() == null
                || input.taxRuleVersion().isBlank() || input.normalizationVersion() == null
                || input.normalizationVersion().isBlank()) {
            throw new IllegalArgumentException("Calculation run input is invalid");
        }
    }

    public record RunInput(
            int targetYear,
            CoreDomain.CostBasisMethod costBasisMethod,
            UUID openingBalanceId,
            String taxRuleVersion,
            String normalizationVersion,
            Set<CoreDomain.Dataset> requiredDatasets,
            Map<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> coverage,
            Set<UUID> priceSnapshotIds,
            boolean requiredPriceMissing,
            boolean unresolvedTaxEventReview,
            boolean unresolvedCalculationError,
            boolean unresolvedImportError,
            boolean finalizeRequested) {

        public RunInput {
            requiredDatasets = requiredDatasets == null ? Set.of() : Set.copyOf(requiredDatasets);
            coverage = coverage == null ? Map.of() : Map.copyOf(coverage);
            priceSnapshotIds = priceSnapshotIds == null ? Set.of() : Set.copyOf(priceSnapshotIds);
        }
    }

    public record RunResult(
            UUID runId,
            CoreDomain.CalculationRunStatus status,
            Set<CoreDomain.BlockedReason> blockedReasons) {
    }

    public record TaxEventInput(
            UUID sourceRecordId,
            CoreDomain.TaxEventType eventType,
            CoreDomain.TaxStatus taxStatus,
            String asset,
            java.math.BigDecimal quantity,
            java.math.BigDecimal jpyValue,
            java.math.BigDecimal costBasisJpy,
            java.math.BigDecimal profitLossJpy) {
    }
}
