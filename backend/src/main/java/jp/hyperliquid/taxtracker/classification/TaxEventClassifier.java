package jp.hyperliquid.taxtracker.classification;

import jp.hyperliquid.taxtracker.domain.CoreDomain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TaxEventClassifier {

    public Result classify(TransactionInput input) {
        if (input == null || input.transactionId() == null || input.transactionType() == null) {
            return review(null, "Transaction type or ID is missing");
        }

        return switch (input.transactionType()) {
            case BUY -> acquisition(input, input.assetTo(), input.amountTo(), null);
            case SELL -> disposal(input, input.assetFrom(), input.amountFrom(), null);
            case SWAP -> swap(input);
            case TRANSFER_IN, TRANSFER_OUT, DEPOSIT, WITHDRAWAL -> transfer(input);
            case PERP_FILL -> review(input.transactionId(),
                    "Perpetual fill requires closedPnl for tax event classification");
            case FUNDING -> funding(input);
            case FEE -> fee(input);
        };
    }

    public Result classifyClosedPnl(ClosedPnlInput input) {
        if (input == null || input.sourceRecordId() == null
                || input.amount() == null || input.asset() == null || input.asset().isBlank()) {
            return review(input == null ? null : input.sourceRecordId(),
                    "closedPnl asset or amount is missing");
        }
        return new Result(
                List.of(new ClassifiedEvent(
                        input.sourceRecordId(), CoreDomain.TaxEventType.PERP_REALIZED_PNL,
                        CoreDomain.TaxStatus.NEEDS_REVIEW, input.asset(), input.amount(),
                        "Perpetual tax treatment is not confirmed")),
                true, "Perpetual tax treatment is not confirmed");
    }

    private Result swap(TransactionInput input) {
        if (blank(input.assetFrom()) || blank(input.assetTo())
                || input.amountFrom() == null || input.amountTo() == null) {
            return review(input.transactionId(), "Swap asset or amount is missing");
        }
        CoreDomain.TaxStatus status = "USOL".equalsIgnoreCase(input.assetFrom())
                || "USOL".equalsIgnoreCase(input.assetTo())
                ? CoreDomain.TaxStatus.NEEDS_REVIEW
                : CoreDomain.TaxStatus.TAXABLE;
        String reason = status == CoreDomain.TaxStatus.NEEDS_REVIEW
                ? "SOL and Hyperliquid USOL tax identity requires confirmation" : null;
        List<ClassifiedEvent> events = new ArrayList<>();
        events.add(new ClassifiedEvent(
                input.transactionId(), CoreDomain.TaxEventType.ASSET_DISPOSAL,
                status, input.assetFrom(), input.amountFrom(), reason));
        events.add(new ClassifiedEvent(
                input.transactionId(), CoreDomain.TaxEventType.ASSET_ACQUISITION,
                status == CoreDomain.TaxStatus.NEEDS_REVIEW
                        ? CoreDomain.TaxStatus.NEEDS_REVIEW : CoreDomain.TaxStatus.NON_TAXABLE,
                input.assetTo(), input.amountTo(), reason));
        return new Result(events, status == CoreDomain.TaxStatus.NEEDS_REVIEW, reason);
    }

    private Result transfer(TransactionInput input) {
        boolean confirmed = input.transferMatchStatus() == CoreDomain.MatchStatus.MATCHED;
        return new Result(
                List.of(new ClassifiedEvent(
                        input.transactionId(), CoreDomain.TaxEventType.SELF_TRANSFER,
                        confirmed ? CoreDomain.TaxStatus.NON_TAXABLE : CoreDomain.TaxStatus.NEEDS_REVIEW,
                        input.asset(), transferQuantity(input),
                        confirmed ? null : "Transfer match is not confirmed")),
                !confirmed, confirmed ? null : "Transfer match is not confirmed");
    }

    private Result funding(TransactionInput input) {
        if (input.amount() == null || blank(input.asset())) {
            return review(input.transactionId(), "Funding asset or amount is missing");
        }
        CoreDomain.TaxEventType type = input.amount().signum() >= 0
                ? CoreDomain.TaxEventType.FUNDING_RECEIVED
                : CoreDomain.TaxEventType.FUNDING_PAID;
        return new Result(
                List.of(new ClassifiedEvent(input.transactionId(), type,
                        CoreDomain.TaxStatus.NEEDS_REVIEW, input.asset(), input.amount().abs(),
                        "Funding tax treatment is not confirmed")),
                true, "Funding tax treatment is not confirmed");
    }

    private Result fee(TransactionInput input) {
        if (input.feeAmount() == null || input.feeAsset() == null || input.feeAsset().isBlank()) {
            return review(input.transactionId(), "Fee asset or amount is missing");
        }
        return new Result(
                List.of(new ClassifiedEvent(input.transactionId(), CoreDomain.TaxEventType.FEE,
                        CoreDomain.TaxStatus.NEEDS_REVIEW, input.feeAsset(), input.feeAmount(),
                        "Fee tax treatment is not confirmed")),
                true, "Fee tax treatment is not confirmed");
    }

    private Result acquisition(TransactionInput input, String asset, BigDecimal quantity, String reason) {
        if (blank(asset) || quantity == null) {
            return review(input.transactionId(), "Acquisition asset or amount is missing");
        }
        return new Result(List.of(new ClassifiedEvent(input.transactionId(),
                CoreDomain.TaxEventType.ASSET_ACQUISITION, CoreDomain.TaxStatus.NON_TAXABLE,
                asset, quantity, reason)), false, reason);
    }

    private Result disposal(TransactionInput input, String asset, BigDecimal quantity, String reason) {
        if (blank(asset) || quantity == null) {
            return review(input.transactionId(), "Disposal asset or amount is missing");
        }
        return new Result(List.of(new ClassifiedEvent(input.transactionId(),
                CoreDomain.TaxEventType.ASSET_DISPOSAL, CoreDomain.TaxStatus.TAXABLE,
                asset, quantity, reason)), false, reason);
    }

    private static Result review(UUID transactionId, String reason) {
        return new Result(List.of(), true, reason);
    }

    private static BigDecimal transferQuantity(TransactionInput input) {
        return input.amount() != null ? input.amount()
                : input.grossAmount() != null ? input.grossAmount() : input.netAmount();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record TransactionInput(
            UUID transactionId,
            CoreDomain.TransactionType transactionType,
            String assetFrom,
            BigDecimal amountFrom,
            String assetTo,
            BigDecimal amountTo,
            String asset,
            BigDecimal amount,
            BigDecimal grossAmount,
            BigDecimal netAmount,
            String feeAsset,
            BigDecimal feeAmount,
            CoreDomain.MatchStatus transferMatchStatus) {
    }

    public record ClosedPnlInput(UUID sourceRecordId, String asset, BigDecimal amount) {
    }

    public record ClassifiedEvent(
            UUID sourceRecordId,
            CoreDomain.TaxEventType eventType,
            CoreDomain.TaxStatus taxStatus,
            String asset,
            BigDecimal quantity,
            String reviewReason) {
    }

    public record Result(
            List<ClassifiedEvent> events,
            boolean needsReview,
            String reviewReason) {
    }
}
