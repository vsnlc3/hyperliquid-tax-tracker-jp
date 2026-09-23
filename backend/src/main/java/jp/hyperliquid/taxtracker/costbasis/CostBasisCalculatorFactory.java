package jp.hyperliquid.taxtracker.costbasis;

import jp.hyperliquid.taxtracker.domain.CoreDomain;

public final class CostBasisCalculatorFactory {

    private CostBasisCalculatorFactory() {
    }

    public static CostBasisCalculator forMethod(CoreDomain.CostBasisMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("Cost basis method is required");
        }
        return switch (method) {
            case TOTAL_AVERAGE -> new TotalAverageCalculator();
            case MOVING_AVERAGE -> new MovingAverageCalculator();
            case UNKNOWN -> new UnknownCostBasisCalculator();
        };
    }
}
