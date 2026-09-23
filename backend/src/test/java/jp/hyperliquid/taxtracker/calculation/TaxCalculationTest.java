package jp.hyperliquid.taxtracker.calculation;

import jp.hyperliquid.taxtracker.costbasis.CostBasisCalculator;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaxCalculationTest {

    private static final UUID ACQUISITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DISPOSAL_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void keepsSpotPerpetualFundingAndFeeAsSeparateAggregates() {
        CostBasisCalculator.CalculationInput costBasisInput = new CostBasisCalculator.CalculationInput(
                "SOL", new CostBasisCalculator.OpeningBalance(new BigDecimal("10"), new BigDecimal("1000")),
                List.of(
                        new CostBasisCalculator.Event(ACQUISITION_ID, Instant.parse("2026-01-01T00:00:00Z"), 1,
                                CostBasisCalculator.EventType.ACQUISITION, new BigDecimal("10"),
                                new BigDecimal("1000"), BigDecimal.ZERO),
                        new CostBasisCalculator.Event(DISPOSAL_ID, Instant.parse("2026-02-01T00:00:00Z"), 2,
                                CostBasisCalculator.EventType.DISPOSAL, new BigDecimal("10"), null, BigDecimal.ZERO)));
        TaxCalculator.Result result = new TaxCalculator().calculate(new TaxCalculator.Input(
                CoreDomain.CostBasisMethod.MOVING_AVERAGE,
                costBasisInput,
                List.of(new TaxCalculator.SpotExchange(DISPOSAL_ID, new BigDecimal("10"),
                        new BigDecimal("1500"), new BigDecimal("2000"), new BigDecimal("5"))),
                List.of(new TaxCalculator.PerpetualPnl(UUID.randomUUID(), new BigDecimal("300"))),
                List.of(new TaxCalculator.Funding(UUID.randomUUID(), new BigDecimal("100")),
                        new TaxCalculator.Funding(UUID.randomUUID(), new BigDecimal("-20"))),
                List.of(new TaxCalculator.Fee(UUID.randomUUID(), CoreDomain.FeeType.HYPERLIQUID_SPOT,
                        new BigDecimal("50"), new BigDecimal("-50")))));

        assertThat(result.spotExchangePnlJpy()).isEqualByComparingTo("1000");
        assertThat(result.perpetualClosedPnlJpy()).isEqualByComparingTo("300");
        assertThat(result.fundingReceivedJpy()).isEqualByComparingTo("100");
        assertThat(result.fundingPaidJpy()).isEqualByComparingTo("20");
        assertThat(result.feeJpy()).isEqualByComparingTo("50");
        assertThat(result.calculatedPnlJpy()).isEqualByComparingTo("1330");
    }

    @Test
    void calculationRunStatusUsesOnlyDraftBlockedFinal() {
        CalculationRunEvaluator evaluator = new CalculationRunEvaluator();
        Map<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> complete = new EnumMap<>(CoreDomain.Dataset.class);
        complete.put(CoreDomain.Dataset.SOLANA_TRANSACTIONS, CoreDomain.DataCoverageStatus.COMPLETE);

        CalculationRunEvaluator.EvaluationInput draftInput = new CalculationRunEvaluator.EvaluationInput(
                CoreDomain.CostBasisMethod.MOVING_AVERAGE,
                Set.of(CoreDomain.Dataset.SOLANA_TRANSACTIONS), complete,
                false, false, false, false, false);
        CalculationRunEvaluator.EvaluationInput finalInput = new CalculationRunEvaluator.EvaluationInput(
                draftInput.costBasisMethod(), draftInput.requiredDatasets(), draftInput.coverage(),
                false, false, false, false, true);
        CalculationRunEvaluator.EvaluationInput blockedInput = new CalculationRunEvaluator.EvaluationInput(
                CoreDomain.CostBasisMethod.UNKNOWN,
                draftInput.requiredDatasets(), Map.of(CoreDomain.Dataset.SOLANA_TRANSACTIONS,
                CoreDomain.DataCoverageStatus.PARTIAL), true, true, true, false, true);

        assertThat(evaluator.evaluate(draftInput).status()).isEqualTo(CoreDomain.CalculationRunStatus.DRAFT);
        assertThat(evaluator.evaluate(finalInput).status()).isEqualTo(CoreDomain.CalculationRunStatus.FINAL);
        CalculationRunEvaluator.EvaluationResult blocked = evaluator.evaluate(blockedInput);
        assertThat(blocked.status()).isEqualTo(CoreDomain.CalculationRunStatus.BLOCKED);
        assertThat(blocked.blockedReasons()).contains(
                CoreDomain.BlockedReason.COST_BASIS_METHOD_UNKNOWN,
                CoreDomain.BlockedReason.REQUIRED_DATASET_PARTIAL,
                CoreDomain.BlockedReason.REQUIRED_PRICE_MISSING,
                CoreDomain.BlockedReason.UNRESOLVED_TAX_EVENT_REVIEW,
                CoreDomain.BlockedReason.UNRESOLVED_CALCULATION_ERROR);
    }
}
