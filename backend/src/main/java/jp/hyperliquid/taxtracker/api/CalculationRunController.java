package jp.hyperliquid.taxtracker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jp.hyperliquid.taxtracker.calculation.CalculationRunService;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/calculation-runs")
public class CalculationRunController {

    private final CalculationRunService calculationRunService;
    private final CalculationRunQueryService queryService;

    public CalculationRunController(
            CalculationRunService calculationRunService,
            CalculationRunQueryService queryService) {
        this.calculationRunService = calculationRunService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<CalculationRunService.RunResult> create(
            @Valid @RequestBody CreateCalculationRunRequest request) {
        CalculationRunService.RunInput input = new CalculationRunService.RunInput(
                request.targetYear(), request.costBasisMethod(), request.openingBalanceId(),
                request.taxRuleVersion(), request.normalizationVersion(), request.requiredDatasets(),
                request.coverage(), request.priceSnapshotIds(), request.requiredPriceMissing(),
                request.unresolvedTaxEventReview(), request.unresolvedCalculationError(),
                request.unresolvedImportError(), request.finalizeRequested());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(calculationRunService.createRun(request.userId(), input));
    }

    @GetMapping("/{runId}")
    public CalculationRunQueryService.RunView get(
            @PathVariable UUID runId,
            @org.springframework.web.bind.annotation.RequestParam UUID userId) {
        return queryService.find(userId, runId);
    }

    public record CreateCalculationRunRequest(
            @NotNull UUID userId,
            int targetYear,
            @NotNull CoreDomain.CostBasisMethod costBasisMethod,
            UUID openingBalanceId,
            @NotNull String taxRuleVersion,
            @NotNull String normalizationVersion,
            java.util.Set<CoreDomain.Dataset> requiredDatasets,
            java.util.Map<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> coverage,
            java.util.Set<UUID> priceSnapshotIds,
            boolean requiredPriceMissing,
            boolean unresolvedTaxEventReview,
            boolean unresolvedCalculationError,
            boolean unresolvedImportError,
            boolean finalizeRequested) {
    }
}
