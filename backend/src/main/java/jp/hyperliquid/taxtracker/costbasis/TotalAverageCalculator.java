package jp.hyperliquid.taxtracker.costbasis;

import jp.hyperliquid.taxtracker.domain.CoreDomain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class TotalAverageCalculator implements CostBasisCalculator {

    @Override
    public CoreDomain.CostBasisMethod method() {
        return CoreDomain.CostBasisMethod.TOTAL_AVERAGE;
    }

    @Override
    public CalculationResult calculate(CalculationInput input) {
        List<Event> events = CostBasisValidation.validatedEvents(input);
        BigDecimal totalQuantity = input.openingBalance().quantity();
        BigDecimal totalBookValue = input.openingBalance().bookValueJpy();
        for (Event event : events) {
            if (event.type() == EventType.ACQUISITION) {
                totalQuantity = totalQuantity.add(event.quantity());
                totalBookValue = totalBookValue.add(event.acquisitionCostJpy());
            }
        }
        BigDecimal averageUnitCost = CostBasisValidation.average(totalBookValue, totalQuantity);
        BigDecimal quantityAfter = input.openingBalance().quantity();
        BigDecimal bookValueAfter = input.openingBalance().bookValueJpy();
        List<EventResult> results = new ArrayList<>();
        for (Event event : events) {
            BigDecimal disposalCost = BigDecimal.ZERO;
            if (event.type() == EventType.ACQUISITION) {
                quantityAfter = quantityAfter.add(event.quantity());
                bookValueAfter = bookValueAfter.add(event.acquisitionCostJpy());
            } else {
                CostBasisValidation.ensureSufficientQuantity(quantityAfter, event.quantity());
                disposalCost = event.quantity().multiply(averageUnitCost,
                        CostBasisValidation.CALCULATION_CONTEXT);
                quantityAfter = quantityAfter.subtract(event.quantity());
                bookValueAfter = bookValueAfter.subtract(disposalCost,
                        CostBasisValidation.CALCULATION_CONTEXT);
            }
            results.add(new EventResult(
                    event.sourceRecordId(), event.type(), event.quantity(), event.purchaseFeeJpy(),
                    event.type() == EventType.DISPOSAL ? disposalCost : null,
                    quantityAfter, bookValueAfter,
                    CostBasisValidation.average(bookValueAfter, quantityAfter)));
        }
        return new CalculationResult(
                method(), results, quantityAfter, bookValueAfter,
                CostBasisValidation.average(bookValueAfter, quantityAfter), true);
    }
}
