package jp.hyperliquid.taxtracker.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CoreDomain {

    private CoreDomain() {
    }

    public enum Network {
        SOLANA
    }

    public enum WalletType {
        USER_WALLET
    }

    public enum AccountMode {
        UNIFIED,
        STANDARD,
        PORTFOLIO_MARGIN,
        DISABLED,
        DEFAULT,
        DEX_ABSTRACTION,
        UNKNOWN
    }

    public enum DataSource {
        BITBANK,
        SOLANA,
        HYPERLIQUID,
        COINGECKO
    }

    public enum Dataset {
        BITBANK_TRADES,
        BITBANK_WITHDRAWALS,
        SOLANA_TRANSACTIONS,
        HYPERLIQUID_FILLS,
        HYPERLIQUID_FUNDING,
        HYPERLIQUID_LEDGER,
        HYPERLIQUID_SPOT_METADATA,
        PRICE_DATA
    }

    public enum DataCoverageStatus {
        COMPLETE,
        PARTIAL,
        FAILED
    }

    public enum CostBasisMethod {
        TOTAL_AVERAGE,
        MOVING_AVERAGE,
        UNKNOWN
    }

    public enum CalculationRunStatus {
        DRAFT,
        BLOCKED,
        FINAL
    }

    public enum BlockedReason {
        COST_BASIS_METHOD_UNKNOWN,
        REQUIRED_DATASET_PARTIAL,
        REQUIRED_DATASET_FAILED,
        REQUIRED_PRICE_MISSING,
        UNRESOLVED_TAX_EVENT_REVIEW,
        UNRESOLVED_CALCULATION_ERROR
    }

    public enum MatchStatus {
        MATCHED,
        POSSIBLE,
        UNMATCHED,
        REJECTED
    }

    public enum TransactionType {
        BUY,
        SELL,
        SWAP,
        TRANSFER_IN,
        TRANSFER_OUT,
        DEPOSIT,
        WITHDRAWAL,
        PERP_FILL,
        FUNDING,
        FEE
    }

    public enum TransactionClassification {
        SWAP,
        TRANSFER,
        DEPOSIT,
        WITHDRAWAL,
        FEE,
        IGNORE,
        OTHER
    }

    public enum TaxEventType {
        ASSET_ACQUISITION,
        ASSET_DISPOSAL,
        SELF_TRANSFER,
        PERP_REALIZED_PNL,
        FUNDING_RECEIVED,
        FUNDING_PAID,
        FEE
    }

    public enum TaxStatus {
        TAXABLE,
        NON_TAXABLE,
        NEEDS_REVIEW
    }

    public enum FeeType {
        BITBANK_PURCHASE,
        BITBANK_WITHDRAWAL,
        SOLANA_NETWORK,
        HYPERLIQUID_SPOT,
        HYPERLIQUID_PERPETUAL,
        HYPERLIQUID_WITHDRAWAL,
        OTHER
    }

    public enum LedgerUpdateSubtype {
        DEPOSIT,
        WITHDRAWAL,
        TRANSFER,
        OTHER
    }

    public enum ReviewType {
        TRANSACTION_CLASSIFICATION,
        TAX_EVENT_CLASSIFICATION
    }

    public enum ReviewStatus {
        OPEN,
        RESOLVED
    }

    public enum ErrorType {
        DATA_COVERAGE,
        CALCULATION,
        IMPORT
    }

    public enum ErrorStatus {
        OPEN,
        RESOLVED
    }

    public enum RecalculationStatus {
        PENDING,
        PROCESSED
    }

    public enum SolanaTransactionStatus {
        SUCCESS,
        FAILED,
        NOT_FOUND,
        UNKNOWN
    }

    public record Wallet(
            UUID id,
            UUID userId,
            Network network,
            String address,
            String label,
            WalletType walletType,
            Instant createdAt) {
    }

    public record HyperliquidAccount(
            UUID id,
            UUID userId,
            String accountAddress,
            String solanaDepositAddress,
            AccountMode accountMode,
            Instant createdAt) {
    }

    public record ImportBatch(
            UUID id,
            UUID userId,
            DataSource source,
            Dataset dataset,
            Instant requestedFrom,
            Instant requestedTo,
            Instant createdAt) {
    }

    public record RawData(
            UUID id,
            DataSource source,
            Dataset dataset,
            String externalId,
            Instant occurredAt,
            String occurredAtRaw,
            String timezoneConfidence,
            String payload,
            String payloadHash,
            UUID importBatchId,
            Instant importedAt) {
    }

    public record UnifiedTransaction(
            UUID id,
            UUID userId,
            DataSource source,
            Dataset dataset,
            String sourceRecordId,
            Instant occurredAt,
            String occurredAtRaw,
            TransactionType transactionType,
            String assetFrom,
            BigDecimal amountFrom,
            String assetTo,
            BigDecimal amountTo,
            String asset,
            BigDecimal grossAmount,
            BigDecimal netAmount,
            String feeAsset,
            BigDecimal feeAmount,
            FeeType feeType,
            String fromAddress,
            String toAddress,
            String transactionHash,
            UUID rawDataId,
            UUID relatedTransactionId,
            String normalizationVersion,
            Instant createdAt) {
    }

    public record SolanaTransaction(
            UUID id,
            String signature,
            Long slot,
            Instant blockTime,
            SolanaTransactionStatus status,
            String version,
            Set<String> signerAddresses,
            Set<SystemTransfer> systemTransfers,
            Set<TokenTransfer> tokenTransfers,
            Long networkFeeLamports,
            Long priorityFeeLamports,
            UUID rawDataId) {
    }

    public record SystemTransfer(
            String sourceAddress,
            String destinationAddress,
            Long lamports) {
    }

    public record TokenTransfer(
            String sourceAddress,
            String destinationAddress,
            String mint,
            BigDecimal rawAmount,
            Integer decimals,
            BigDecimal uiAmount) {
    }

    public record HyperliquidFill(
            UUID id,
            UUID rawDataId,
            String coin,
            String side,
            String direction,
            BigDecimal size,
            BigDecimal price,
            Instant occurredAt,
            BigDecimal startPosition,
            boolean crossed,
            String orderId,
            String tradeId,
            String hash,
            String twapId) {
    }

    public record HyperliquidClosedPnl(
            UUID id,
            UUID rawDataId,
            UUID fillId,
            String coin,
            Instant occurredAt,
            BigDecimal closedPnl,
            String pnlAsset) {
    }

    public record HyperliquidFee(
            UUID id,
            UUID rawDataId,
            UUID relatedFillId,
            UUID relatedLedgerUpdateId,
            FeeType feeType,
            String feeAsset,
            BigDecimal feeAmount,
            Instant occurredAt) {
    }

    public record HyperliquidFunding(
            UUID id,
            UUID rawDataId,
            String hash,
            String coin,
            Instant occurredAt,
            BigDecimal fundingRate,
            BigDecimal fundingAmount,
            BigDecimal positionSize,
            String fundingAsset,
            Integer sampleCount) {
    }

    public record HyperliquidLedgerUpdate(
            UUID id,
            UUID rawDataId,
            String hash,
            LedgerUpdateSubtype subtype,
            String ledgerUpdateType,
            String token,
            BigDecimal amount,
            BigDecimal usdcValue,
            String userAddress,
            String destinationAddress,
            BigDecimal fee,
            BigDecimal nativeTokenFee,
            Long nonce,
            String feeAsset,
            Instant occurredAt) {
    }

    public record HyperliquidSpotMetadata(
            UUID id,
            UUID rawDataId,
            List<HyperliquidSpotToken> tokens,
            List<HyperliquidSpotPair> pairs,
            Instant occurredAt) {
    }

    public record HyperliquidSpotToken(
            int tokenIndex,
            String name,
            Integer sizeDecimals,
            Integer weiDecimals,
            String tokenId,
            boolean canonical,
            String fullName) {
    }

    public record HyperliquidSpotPair(
            int pairIndex,
            String name,
            int baseTokenIndex,
            int quoteTokenIndex,
            boolean canonical) {
    }

    public record TransferLink(
            UUID id,
            UUID outgoingTransactionId,
            UUID incomingTransactionId,
            MatchStatus matchStatus,
            BigDecimal matchScore,
            String matchedBy,
            Instant createdAt) {
    }

    public record TaxEvent(
            UUID id,
            UUID calculationRunId,
            UUID sourceRecordId,
            TaxEventType eventType,
            TaxStatus taxStatus,
            String asset,
            BigDecimal quantity,
            BigDecimal jpyValue,
            BigDecimal costBasisJpy,
            BigDecimal profitLossJpy,
            String taxRuleVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PriceSnapshot(
            UUID id,
            String asset,
            String currency,
            BigDecimal price,
            Instant priceTimestamp,
            String source,
            String providerAssetId,
            Instant requestFrom,
            Instant requestTo,
            String interval,
            UUID rawDataId,
            Instant createdAt) {
    }

    public record CostBasisSetting(
            UUID id,
            UUID userId,
            String asset,
            CostBasisMethod method,
            LocalDate effectiveFrom,
            Instant createdAt) {
    }

    public record CostBasisOpeningBalance(
            UUID id,
            UUID userId,
            String asset,
            LocalDate asOfDate,
            BigDecimal quantity,
            BigDecimal bookValueJpy,
            String inputSource,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record UserClassification(
            UUID id,
            UUID userId,
            String targetType,
            UUID targetId,
            String beforeClassification,
            String afterClassification,
            String reason,
            Instant createdAt) {
    }

    public record DataImportStatus(
            UUID id,
            UUID userId,
            Dataset dataset,
            Instant requestedFrom,
            Instant requestedTo,
            Instant actualFrom,
            Instant actualTo,
            DataCoverageStatus status,
            String reason,
            UUID importBatchId,
            Instant checkedAt) {
    }

    public record ClassificationReview(
            UUID id,
            UUID userId,
            ReviewType reviewType,
            UUID targetId,
            ReviewStatus status,
            String reason,
            Instant createdAt,
            Instant resolvedAt) {
    }

    public record DataError(
            UUID id,
            UUID userId,
            ErrorType errorType,
            Dataset dataset,
            UUID targetId,
            String code,
            String message,
            ErrorStatus status,
            Instant createdAt,
            Instant resolvedAt) {
    }

    public record RecalculationRequest(
            UUID id,
            UUID userId,
            String sourceType,
            UUID sourceId,
            String reason,
            RecalculationStatus status,
            Instant createdAt,
            Instant processedAt) {
    }

    public record CalculationRun(
            UUID id,
            UUID userId,
            int targetYear,
            CostBasisMethod costBasisMethod,
            UUID openingBalanceId,
            String taxRuleVersion,
            String normalizationVersion,
            Map<Dataset, DataCoverageStatus> dataCoverageSnapshot,
            Set<UUID> priceSnapshotIds,
            CalculationRunStatus status,
            Set<BlockedReason> blockedReasons,
            Instant calculatedAt) {
    }
}
