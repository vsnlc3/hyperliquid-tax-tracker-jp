package jp.hyperliquid.taxtracker.classification;

import jp.hyperliquid.taxtracker.domain.CoreDomain;

public final class TransactionClassifier {

    public Result classify(CoreDomain.TransactionType transactionType) {
        if (transactionType == null) {
            return new Result(CoreDomain.TransactionClassification.OTHER, true,
                    "Transaction type is missing");
        }
        return switch (transactionType) {
            case BUY, SELL, SWAP -> known(CoreDomain.TransactionClassification.SWAP);
            case TRANSFER_IN, TRANSFER_OUT -> known(CoreDomain.TransactionClassification.TRANSFER);
            case DEPOSIT -> known(CoreDomain.TransactionClassification.DEPOSIT);
            case WITHDRAWAL -> known(CoreDomain.TransactionClassification.WITHDRAWAL);
            case FEE -> known(CoreDomain.TransactionClassification.FEE);
            case PERP_FILL, FUNDING -> known(CoreDomain.TransactionClassification.OTHER);
        };
    }

    private static Result known(CoreDomain.TransactionClassification classification) {
        return new Result(classification, false, null);
    }

    public record Result(
            CoreDomain.TransactionClassification classification,
            boolean needsReview,
            String reviewReason) {
    }
}
