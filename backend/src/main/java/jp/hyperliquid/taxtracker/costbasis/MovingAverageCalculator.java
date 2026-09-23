package jp.hyperliquid.taxtracker.costbasis;

import jp.hyperliquid.taxtracker.domain.CoreDomain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class MovingAverageCalculator implements CostBasisCalculator {

    @Override
    public CoreDomain.CostBasisMethod method() {
        return CoreDomain.CostBasisMethod.MOVING_AVERAGE;
    }

    @Override
    public CalculationResult calculate(CalculationInput input) {
        List<Event> events = CostBasisValidation.validatedEvents(input);
        BigDecimal quantity = input.openingBalance().quantity();
        BigDecimal bookValue = input.openingBalance().bookValueJpy();
        List<EventResult> results = new ArrayList<>();
        for (Event event : events) {
            BigDecimal disposalCost = null;
            if (event.type() == EventType.ACQUISITION) {
                quantity = quantity.add(event.quantity());
                bookValue = bookValue.add(event.acquisitionCostJpy());
            } else {
                CostBasisValidation.ensureSufficientQuantity(quantity, event.quantity());
                BigDecimal averageUnitCost = CostBasisValidation.average(bookValue, quantity);
                disposalCost = event.quantity().multiply(averageUnitCost,
                        CostBasisValidation.CALCULATION_CONTEXT);
                quantity = quantity.subtract(event.quantity());
                bookValue = bookValue.subtract(disposalCost,
                        CostBasisValidation.CALCULATION_CONTEXT);
            }
            results.add(new EventResult(
                    event.sourceRecordId(), event.type(), event.quantity(), event.purchaseFeeJpy(),
                    disposalCost, quantity, bookValue,
                    CostBasisValidation.average(bookValue, quantity)));
        }
        return new CalculationResult(
                method(), results, quantity, bookValue,
                CostBasisValidation.average(bookValue, quantity), true);
    }
}
