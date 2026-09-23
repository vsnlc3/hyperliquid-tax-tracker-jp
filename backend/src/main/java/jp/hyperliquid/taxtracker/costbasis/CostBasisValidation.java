package jp.hyperliquid.taxtracker.costbasis;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

final class CostBasisValidation {

    static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private CostBasisValidation() {
    }

    static List<CostBasisCalculator.Event> validatedEvents(CostBasisCalculator.CalculationInput input) {
        if (input == null || input.asset() == null || !"SOL".equalsIgnoreCase(input.asset())) {
            throw new IllegalArgumentException("Cost basis MVP supports SOL only");
        }
        if (input.openingBalance() == null) {
            throw new IllegalArgumentException("Opening balance is required");
        }
        validateNonNegative(input.openingBalance().quantity(), "opening quantity");
        validateNonNegative(input.openingBalance().bookValueJpy(), "opening book value");
        if (input.events() == null) {
            throw new IllegalArgumentException("Cost basis events are required");
        }

        List<CostBasisCalculator.Event> events = input.events().stream()
                .sorted(Comparator.comparing(CostBasisCalculator.Event::occurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(CostBasisCalculator.Event::sequence))
                .toList();
        for (CostBasisCalculator.Event event : events) {
            if (event == null || event.type() == null || event.occurredAt() == null) {
                throw new IllegalArgumentException("Cost basis event type and timestamp are required");
            }
            validateNonNegative(event.quantity(), "event quantity");
            validateNonNegative(event.purchaseFeeJpy(), "purchase fee");
            if (event.type() == CostBasisCalculator.EventType.ACQUISITION) {
                if (event.acquisitionCostJpy() == null) {
                    throw new IllegalArgumentException("Acquisition cost is required");
                }
                validateNonNegative(event.acquisitionCostJpy(), "acquisition cost");
            } else if (event.acquisitionCostJpy() != null
                    && event.acquisitionCostJpy().compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("Disposal cannot have acquisition cost");
            }
        }
        return events;
    }

    static void validateNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    static BigDecimal average(BigDecimal bookValue, BigDecimal quantity) {
        if (quantity.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return bookValue.divide(quantity, CALCULATION_CONTEXT);
    }

    static void ensureSufficientQuantity(BigDecimal available, BigDecimal requested) {
        if (requested.compareTo(available) > 0) {
            throw new IllegalArgumentException(
                    "SOL disposal exceeds available quantity: " + requested + " > " + available);
        }
    }
}
