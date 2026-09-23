package jp.hyperliquid.taxtracker.costbasis;

import jp.hyperliquid.taxtracker.domain.CoreDomain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CostBasisCalculator {

    CoreDomain.CostBasisMethod method();

    CalculationResult calculate(CalculationInput input);

    record CalculationInput(
            String asset,
            OpeningBalance openingBalance,
            List<Event> events) {
    }

    record OpeningBalance(BigDecimal quantity, BigDecimal bookValueJpy) {
    }

    record Event(
            UUID sourceRecordId,
            Instant occurredAt,
            int sequence,
            EventType type,
            BigDecimal quantity,
            BigDecimal acquisitionCostJpy,
            BigDecimal purchaseFeeJpy) {
    }

    enum EventType {
        ACQUISITION,
        DISPOSAL
    }

    record EventResult(
            UUID sourceRecordId,
            EventType type,
            BigDecimal quantity,
            BigDecimal purchaseFeeJpy,
            BigDecimal disposalCostBasisJpy,
            BigDecimal quantityAfter,
            BigDecimal bookValueAfter,
            BigDecimal averageUnitCostAfter) {
    }

    record CalculationResult(
            CoreDomain.CostBasisMethod method,
            List<EventResult> eventResults,
            BigDecimal endingQuantity,
            BigDecimal endingBookValueJpy,
            BigDecimal averageUnitCostJpy,
            boolean finalizable) {
    }
}
