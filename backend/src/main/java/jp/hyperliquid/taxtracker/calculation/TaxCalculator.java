package jp.hyperliquid.taxtracker.calculation;

import jp.hyperliquid.taxtracker.costbasis.CostBasisCalculator;
import jp.hyperliquid.taxtracker.costbasis.CostBasisCalculatorFactory;
import jp.hyperliquid.taxtracker.domain.CoreDomain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TaxCalculator {

    public Result calculate(Input input) {
        if (input == null || input.costBasisInput() == null) {
            throw new IllegalArgumentException("Tax calculation input is required");
        }
        CostBasisCalculator calculator = CostBasisCalculatorFactory.forMethod(input.costBasisMethod());
        CostBasisCalculator.CalculationResult costBasis = calculator.calculate(input.costBasisInput());
        List<SpotResult> spotResults = new ArrayList<>();
        BigDecimal spotPnl = BigDecimal.ZERO;
        for (SpotExchange exchange : input.spotExchanges()) {
            CostBasisCalculator.EventResult disposal = costBasis.eventResults().stream()
                    .filter(event -> Objects.equals(event.sourceRecordId(), exchange.sourceRecordId())
                            && event.type() == CostBasisCalculator.EventType.DISPOSAL)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Spot exchange is not present in cost basis events: " + exchange.sourceRecordId()));
            BigDecimal pnl = exchange.proceedsJpy().subtract(disposal.disposalCostBasisJpy());
            spotPnl = spotPnl.add(pnl);
            spotResults.add(new SpotResult(
                    exchange.sourceRecordId(), exchange.solAmount(), exchange.usdcAmount(),
                    exchange.proceedsJpy(), disposal.disposalCostBasisJpy(), exchange.spotFeeJpy(), pnl));
        }

        BigDecimal closedPnl = input.perpetualPnls().stream()
                .map(PerpetualPnl::closedPnlJpy)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fundingReceived = BigDecimal.ZERO;
        BigDecimal fundingPaid = BigDecimal.ZERO;
        for (Funding funding : input.fundings()) {
            if (funding.jpyValue().signum() >= 0) {
                fundingReceived = fundingReceived.add(funding.jpyValue());
            } else {
                fundingPaid = fundingPaid.add(funding.jpyValue().abs());
            }
        }
        BigDecimal feeValue = BigDecimal.ZERO;
        BigDecimal feeAdjustment = BigDecimal.ZERO;
        for (Fee fee : input.fees()) {
            feeValue = feeValue.add(fee.jpyValue().abs());
            if (fee.taxAdjustmentJpy() != null) {
                feeAdjustment = feeAdjustment.add(fee.taxAdjustmentJpy());
            }
        }
        BigDecimal calculatedPnl = spotPnl
                .add(closedPnl)
                .add(fundingReceived)
                .subtract(fundingPaid)
                .add(feeAdjustment);
        return new Result(costBasis, spotResults, spotPnl, closedPnl,
                fundingReceived, fundingPaid, feeValue, feeAdjustment, calculatedPnl);
    }

    public record Input(
            CoreDomain.CostBasisMethod costBasisMethod,
            CostBasisCalculator.CalculationInput costBasisInput,
            List<SpotExchange> spotExchanges,
            List<PerpetualPnl> perpetualPnls,
            List<Funding> fundings,
            List<Fee> fees) {

        public Input {
            spotExchanges = spotExchanges == null ? List.of() : List.copyOf(spotExchanges);
            perpetualPnls = perpetualPnls == null ? List.of() : List.copyOf(perpetualPnls);
            fundings = fundings == null ? List.of() : List.copyOf(fundings);
            fees = fees == null ? List.of() : List.copyOf(fees);
        }
    }

    public record SpotExchange(
            UUID sourceRecordId,
            BigDecimal solAmount,
            BigDecimal usdcAmount,
            BigDecimal proceedsJpy,
            BigDecimal spotFeeJpy) {
    }

    public record PerpetualPnl(UUID sourceRecordId, BigDecimal closedPnlJpy) {
    }

    public record Funding(UUID sourceRecordId, BigDecimal jpyValue) {
    }

    public record Fee(
            UUID sourceRecordId,
            CoreDomain.FeeType feeType,
            BigDecimal jpyValue,
            BigDecimal taxAdjustmentJpy) {
    }

    public record SpotResult(
            UUID sourceRecordId,
            BigDecimal solAmount,
            BigDecimal usdcAmount,
            BigDecimal proceedsJpy,
            BigDecimal costBasisJpy,
            BigDecimal spotFeeJpy,
            BigDecimal profitLossJpy) {
    }

    public record Result(
            CostBasisCalculator.CalculationResult costBasis,
            List<SpotResult> spotResults,
            BigDecimal spotExchangePnlJpy,
            BigDecimal perpetualClosedPnlJpy,
            BigDecimal fundingReceivedJpy,
            BigDecimal fundingPaidJpy,
            BigDecimal feeJpy,
            BigDecimal feeAdjustmentJpy,
            BigDecimal calculatedPnlJpy) {
    }
}
