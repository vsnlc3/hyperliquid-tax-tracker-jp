package jp.hyperliquid.taxtracker;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CoreDomainTest {

    @Test
    void keepsCostBasisUnknownAsAnExplicitMethod() {
        CoreDomain.CalculationRun run = new CoreDomain.CalculationRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2026,
                CoreDomain.CostBasisMethod.UNKNOWN,
                null,
                "tax-rule-v1",
                "normalization-v1",
                Map.of(CoreDomain.Dataset.BITBANK_TRADES, CoreDomain.DataCoverageStatus.COMPLETE),
                Set.of(),
                CoreDomain.CalculationRunStatus.BLOCKED,
                Set.of(CoreDomain.BlockedReason.COST_BASIS_METHOD_UNKNOWN),
                null);

        assertThat(run.costBasisMethod()).isEqualTo(CoreDomain.CostBasisMethod.UNKNOWN);
        assertThat(run.status()).isEqualTo(CoreDomain.CalculationRunStatus.BLOCKED);
        assertThat(run.blockedReasons()).containsExactly(CoreDomain.BlockedReason.COST_BASIS_METHOD_UNKNOWN);
    }

    @Test
    void keepsTransferAmountsAndFeeAssetAsSeparateValues() {
        CoreDomain.UnifiedTransaction transaction = new CoreDomain.UnifiedTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CoreDomain.DataSource.BITBANK,
                CoreDomain.Dataset.BITBANK_WITHDRAWALS,
                "txid-1",
                null,
                "2026/09/23 14:00:00",
                CoreDomain.TransactionType.TRANSFER_OUT,
                "SOL",
                null,
                null,
                null,
                "SOL",
                new BigDecimal("16.28000000"),
                new BigDecimal("16.27100000"),
                "SOL",
                new BigDecimal("0.00900000"),
                CoreDomain.FeeType.BITBANK_WITHDRAWAL,
                "bitbank",
                "phantom",
                "txid-1",
                UUID.randomUUID(),
                null,
                "bitbank-v1",
                null);

        assertThat(transaction.grossAmount()).isNotEqualTo(transaction.netAmount());
        assertThat(transaction.feeAmount()).isNotEqualTo(transaction.netAmount());
        assertThat(transaction.feeAsset()).isEqualTo("SOL");
    }

    @Test
    void exposesOnlyTheThreeCalculationRunStatuses() {
        assertThat(CoreDomain.CalculationRunStatus.values())
                .containsExactly(
                        CoreDomain.CalculationRunStatus.DRAFT,
                        CoreDomain.CalculationRunStatus.BLOCKED,
                        CoreDomain.CalculationRunStatus.FINAL);
    }

    @Test
    void supportsDatasetCoverageSnapshot() {
        EnumMap<CoreDomain.Dataset, CoreDomain.DataCoverageStatus> coverage =
                new EnumMap<>(CoreDomain.Dataset.class);
        coverage.put(CoreDomain.Dataset.SOLANA_TRANSACTIONS, CoreDomain.DataCoverageStatus.PARTIAL);
        coverage.put(CoreDomain.Dataset.PRICE_DATA, CoreDomain.DataCoverageStatus.COMPLETE);

        assertThat(coverage)
                .containsEntry(CoreDomain.Dataset.SOLANA_TRANSACTIONS, CoreDomain.DataCoverageStatus.PARTIAL)
                .containsEntry(CoreDomain.Dataset.PRICE_DATA, CoreDomain.DataCoverageStatus.COMPLETE);
    }
}
