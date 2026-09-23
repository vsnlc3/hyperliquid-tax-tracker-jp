package jp.hyperliquid.taxtracker.classification;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void transactionAndTaxClassificationAreSeparate() {
        TransactionClassifier.Result transaction = new TransactionClassifier()
                .classify(CoreDomain.TransactionType.FUNDING);
        TaxEventClassifier.Result tax = new TaxEventClassifier().classify(new TaxEventClassifier.TransactionInput(
                ID, CoreDomain.TransactionType.FUNDING, null, null, null, null,
                "USDC", new BigDecimal("-1.2"), null, null, null, null, null));

        assertThat(transaction.classification()).isEqualTo(CoreDomain.TransactionClassification.OTHER);
        assertThat(transaction.needsReview()).isFalse();
        assertThat(tax.events().get(0).eventType()).isEqualTo(CoreDomain.TaxEventType.FUNDING_PAID);
        assertThat(tax.events().get(0).taxStatus()).isEqualTo(CoreDomain.TaxStatus.NEEDS_REVIEW);
    }

    @Test
    void confirmedTransferIsSelfTransferButUnconfirmedTransferNeedsReview() {
        TaxEventClassifier classifier = new TaxEventClassifier();
        TaxEventClassifier.TransactionInput input = new TaxEventClassifier.TransactionInput(
                ID, CoreDomain.TransactionType.TRANSFER_OUT, null, null, null, null,
                "SOL", null, new BigDecimal("2"), null, null, null, null);

        TaxEventClassifier.Result unconfirmed = classifier.classify(input);
        TaxEventClassifier.Result confirmed = classifier.classify(new TaxEventClassifier.TransactionInput(
                ID, input.transactionType(), input.assetFrom(), input.amountFrom(), input.assetTo(),
                input.amountTo(), input.asset(), input.amount(), input.grossAmount(), input.netAmount(),
                input.feeAsset(), input.feeAmount(), CoreDomain.MatchStatus.MATCHED));

        assertThat(unconfirmed.needsReview()).isTrue();
        assertThat(confirmed.needsReview()).isFalse();
        assertThat(confirmed.events().get(0).taxStatus()).isEqualTo(CoreDomain.TaxStatus.NON_TAXABLE);
    }

    @Test
    void usolSwapIsKeptAsTaxEventReview() {
        TaxEventClassifier.Result result = new TaxEventClassifier().classify(new TaxEventClassifier.TransactionInput(
                ID, CoreDomain.TransactionType.SWAP, "USOL", new BigDecimal("1"),
                "USDC", new BigDecimal("140"), null, null, null, null, null, null, null));

        assertThat(result.needsReview()).isTrue();
        assertThat(result.events()).extracting(TaxEventClassifier.ClassifiedEvent::eventType)
                .containsExactly(CoreDomain.TaxEventType.ASSET_DISPOSAL,
                        CoreDomain.TaxEventType.ASSET_ACQUISITION);
        assertThat(result.events()).allMatch(event ->
                event.taxStatus() == CoreDomain.TaxStatus.NEEDS_REVIEW);
    }
}
