package jp.hyperliquid.taxtracker.calculation;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public final class CalculationRunEvaluator {

    public EvaluationResult evaluate(EvaluationInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Calculation run input is required");
        }
        Set<CoreDomain.BlockedReason> blockedReasons = new LinkedHashSet<>();
        if (input.costBasisMethod() == null
                || input.costBasisMethod() == CoreDomain.CostBasisMethod.UNKNOWN) {
            blockedReasons.add(CoreDomain.BlockedReason.COST_BASIS_METHOD_UNKNOWN);
        }
        for (CoreDomain.Dataset dataset : input.requiredDatasets()) {
            CoreDomain.DataCoverageStatus status = input.coverage().get(dataset);
            if (status == null) {
                blockedReasons.add(CoreDomain.BlockedReason.REQUIRED_DATASET_MISSING);
            } else if (status == CoreDomain.DataCoverageStatus.PARTIAL) {
                blockedReasons.add(CoreDomain.BlockedReason.REQUIRED_DATASET_PARTIAL);
            } else if (status == CoreDomain.DataCoverageStatus.FAILED) {
                blockedReasons.add(CoreDomain.BlockedReason.REQUIRED_DATASET_FAILED);
            }
        }
        if (input.requiredPriceMissing()) {
            blockedReasons.add(CoreDomain.BlockedReason.REQUIRED_PRICE_MISSING);
        }
        if (input.unresolvedTaxEventReview()) {
            blockedReasons.add(CoreDomain.BlockedReason.UNRESOLVED_TAX_EVENT_REVIEW);
        }
        if (input.unresolvedCalculationError()) {
            blockedReasons.add(CoreDomain.BlockedReason.UNRESOLVED_CALCULATION_ERROR);
        }
        if (input.unresolvedImportError()) {
            blockedReasons.add(CoreDomain.BlockedReason.UNRESOLVED_IMPORT_ERROR);
        }

        CoreDomain.CalculationRunStatus status = blockedReasons.isEmpty()
                ? input.finalizeRequested()
                ? CoreDomain.CalculationRunStatus.FINAL
                : CoreDomain.CalculationRunStatus.DRAFT
                : CoreDomain.CalculationRunStatus.BLOCKED;
        return new EvaluationResult(status, blockedReasons);
    }

    public record EvaluationInput(
            CoreDomain.CostBasisMethod costBasisMethod,
            Set<CoreDomain.Dataset> requiredDatasets,
            Map<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> coverage,
            boolean requiredPriceMissing,
            boolean unresolvedTaxEventReview,
            boolean unresolvedCalculationError,
            boolean unresolvedImportError,
            boolean finalizeRequested) {

        public EvaluationInput {
            requiredDatasets = requiredDatasets == null ? Set.of() : Set.copyOf(requiredDatasets);
            coverage = coverage == null ? Map.of() : Map.copyOf(coverage);
        }
    }

    public record EvaluationResult(
            CoreDomain.CalculationRunStatus status,
            Set<CoreDomain.BlockedReason> blockedReasons) {
    }
}
