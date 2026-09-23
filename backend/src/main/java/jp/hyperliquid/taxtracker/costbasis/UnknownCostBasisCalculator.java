package jp.hyperliquid.taxtracker.costbasis;

import jp.hyperliquid.taxtracker.domain.CoreDomain;

import java.math.BigDecimal;
import java.util.List;

public final class UnknownCostBasisCalculator implements CostBasisCalculator {

    @Override
    public CoreDomain.CostBasisMethod method() {
        return CoreDomain.CostBasisMethod.UNKNOWN;
    }

    @Override
    public CalculationResult calculate(CalculationInput input) {
        CostBasisValidation.validatedEvents(input);
        return new CalculationResult(
                method(), List.of(), input.openingBalance().quantity(),
                input.openingBalance().bookValueJpy(), BigDecimal.ZERO, false);
    }
}
