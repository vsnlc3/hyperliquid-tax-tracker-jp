package jp.hyperliquid.taxtracker.costbasis;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostBasisCalculatorTest {

    private static final CostBasisCalculator.OpeningBalance OPENING =
            new CostBasisCalculator.OpeningBalance(new BigDecimal("10"), new BigDecimal("1000"));

    @Test
    void totalAverageUsesOpeningBalanceAndAllAcquisitions() {
        CostBasisCalculator.CalculationInput input = new CostBasisCalculator.CalculationInput(
                "SOL", OPENING, List.of(
                event("acquisition", "2026-01-01T00:00:00Z", 10, "1000", "10",
                        CostBasisCalculator.EventType.ACQUISITION),
                event("disposal", "2026-02-01T00:00:00Z", 5, null, null,
                        CostBasisCalculator.EventType.DISPOSAL)));

        CostBasisCalculator.CalculationResult result = new TotalAverageCalculator().calculate(input);

        assertThat(result.method()).isEqualTo(CoreDomain.CostBasisMethod.TOTAL_AVERAGE);
        assertThat(result.eventResults().get(1).disposalCostBasisJpy())
                .isEqualByComparingTo("500");
        assertThat(result.endingQuantity()).isEqualByComparingTo("15");
        assertThat(result.endingBookValueJpy()).isEqualByComparingTo("1500");
        assertThat(result.eventResults().get(0).purchaseFeeJpy()).isEqualByComparingTo("10");
    }

    @Test
    void movingAverageUpdatesAfterEachAcquisitionAndDisposal() {
        CostBasisCalculator.CalculationInput input = new CostBasisCalculator.CalculationInput(
                "SOL", OPENING, List.of(
                event("acquisition", "2026-01-01T00:00:00Z", 10, "2000", null,
                        CostBasisCalculator.EventType.ACQUISITION),
                event("disposal", "2026-02-01T00:00:00Z", 5, null, null,
                        CostBasisCalculator.EventType.DISPOSAL),
                event("acquisition", "2026-03-01T00:00:00Z", 5, "1000", null,
                        CostBasisCalculator.EventType.ACQUISITION)));

        CostBasisCalculator.CalculationResult result = new MovingAverageCalculator().calculate(input);

        assertThat(result.eventResults().get(1).disposalCostBasisJpy())
                .isEqualByComparingTo("750");
        assertThat(result.endingQuantity()).isEqualByComparingTo("20");
        assertThat(result.endingBookValueJpy()).isEqualByComparingTo("3250");
    }

    @Test
    void unknownMethodCannotBeFinalizable() {
        CostBasisCalculator.CalculationResult result = new UnknownCostBasisCalculator().calculate(
                new CostBasisCalculator.CalculationInput("SOL", OPENING, List.of()));

        assertThat(result.method()).isEqualTo(CoreDomain.CostBasisMethod.UNKNOWN);
        assertThat(result.finalizable()).isFalse();
    }

    @Test
    void disposalCannotExceedAvailableQuantity() {
        CostBasisCalculator.CalculationInput input = new CostBasisCalculator.CalculationInput(
                "SOL", OPENING, List.of(event("disposal", "2026-01-01T00:00:00Z", 11, null, null,
                        CostBasisCalculator.EventType.DISPOSAL)));

        assertThatThrownBy(() -> new MovingAverageCalculator().calculate(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds available quantity");
    }

    private static CostBasisCalculator.Event event(
            String id,
            String timestamp,
            int quantity,
            String acquisitionCost,
            String purchaseFee,
            CostBasisCalculator.EventType type) {
        return new CostBasisCalculator.Event(
                UUID.nameUUIDFromBytes(id.getBytes()), Instant.parse(timestamp), quantity,
                type,
                BigDecimal.valueOf(quantity),
                acquisitionCost == null ? null : new BigDecimal(acquisitionCost),
                purchaseFee == null ? BigDecimal.ZERO : new BigDecimal(purchaseFee));
    }
}
