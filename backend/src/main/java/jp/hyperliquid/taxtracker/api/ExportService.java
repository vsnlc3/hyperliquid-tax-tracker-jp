package jp.hyperliquid.taxtracker.api;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import jp.hyperliquid.taxtracker.review.ClassificationReviewService;
import jp.hyperliquid.taxtracker.review.DataErrorService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ExportService {

    private final TimelineService timelineService;
    private final CoverageQueryService coverageQueryService;
    private final ClassificationReviewService reviewService;
    private final DataErrorService errorService;
    private final JdbcTemplate jdbcTemplate;

    public ExportService(
            TimelineService timelineService,
            CoverageQueryService coverageQueryService,
            ClassificationReviewService reviewService,
            DataErrorService errorService,
            JdbcTemplate jdbcTemplate) {
        this.timelineService = timelineService;
        this.coverageQueryService = coverageQueryService;
        this.reviewService = reviewService;
        this.errorService = errorService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public String transactionsCsv(UUID userId, Instant from, Instant to) {
        StringBuilder csv = new StringBuilder("id,source,dataset,source_record_id,occurred_at,transaction_type,asset_from,amount_from,asset_to,amount_to,asset,gross_amount,net_amount,fee_asset,fee_amount,fee_type,from_address,to_address,transaction_hash,raw_data_id,related_transaction_id\n");
        for (TimelineService.TimelineRow row : timelineService.find(userId, from, to)) {
            csv.append(csvRow(
                    row.id(), row.source(), row.dataset(), row.sourceRecordId(), row.occurredAt(),
                    row.transactionType(), row.assetFrom(), row.amountFrom(), row.assetTo(), row.amountTo(),
                    row.asset(), row.grossAmount(), row.netAmount(), row.feeAsset(), row.feeAmount(),
                    row.feeType(), row.fromAddress(), row.toAddress(), row.transactionHash(),
                    row.rawDataId(), row.relatedTransactionId()));
        }
        return csv.toString();
    }

    public String coverageCsv(UUID userId) {
        StringBuilder csv = new StringBuilder("id,user_id,dataset,requested_from,requested_to,actual_from,actual_to,status,reason,import_batch_id,checked_at\n");
        for (CoreDomain.DataImportStatus status : coverageQueryService.findLatest(userId)) {
            csv.append(csvRow(status.id(), status.userId(), status.dataset(), status.requestedFrom(),
                    status.requestedTo(), status.actualFrom(), status.actualTo(), status.status(),
                    status.reason(), status.importBatchId(), status.checkedAt()));
        }
        return csv.toString();
    }

    public String needsReviewCsv(UUID userId) {
        StringBuilder csv = new StringBuilder("kind,id,review_type_or_error_type,target_id,status,reason_or_message,created_at,resolved_at\n");
        reviewService.openReviews(userId).forEach(review -> csv.append(csvRow(
                "REVIEW", review.id(), review.reviewType(), review.targetId(), review.status(),
                review.reason(), review.createdAt(), review.resolvedAt())));
        errorService.openErrors(userId).forEach(error -> csv.append(csvRow(
                "ERROR", error.id(), error.errorType(), error.targetId(), error.status(),
                error.message(), error.createdAt(), error.resolvedAt())));
        return csv.toString();
    }

    public String annualSummaryCsv(UUID userId, int targetYear) {
        StringBuilder csv = new StringBuilder(
                "target_year,calculation_run_id,status,cost_basis_method,blocked_reasons,event_type,total_jpy_value,total_profit_loss_jpy\n");
        List<SummaryRow> rows = jdbcTemplate.query("""
                SELECT cr.id, cr.status, cr.cost_basis_method, cr.blocked_reasons,
                       te.event_type,
                       COALESCE(SUM(te.jpy_value), 0) AS total_jpy_value,
                       COALESCE(SUM(te.profit_loss_jpy), 0) AS total_profit_loss_jpy
                FROM calculation_runs cr
                LEFT JOIN tax_events te ON te.calculation_run_id = cr.id
                WHERE cr.user_id = ? AND cr.target_year = ?
                GROUP BY cr.id, cr.status, cr.cost_basis_method, cr.blocked_reasons, te.event_type
                ORDER BY cr.calculated_at DESC, te.event_type
                LIMIT 100
                """, (resultSet, rowNum) -> new SummaryRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("status"),
                resultSet.getString("cost_basis_method"),
                resultSet.getString("blocked_reasons"),
                resultSet.getString("event_type"),
                resultSet.getBigDecimal("total_jpy_value"),
                resultSet.getBigDecimal("total_profit_loss_jpy")), userId, targetYear);
        if (rows.isEmpty()) {
            csv.append(csvRow(targetYear, "", "", "", "", "NO_CALCULATION_RUN", "", ""));
            return csv.toString();
        }
        for (SummaryRow row : rows) {
            csv.append(csvRow(targetYear, row.runId(), row.status(), row.costBasisMethod(),
                    row.blockedReasons(), row.eventType() == null ? "NO_TAX_EVENTS" : row.eventType(),
                    row.totalJpyValue(), row.totalProfitLossJpy()));
        }
        return csv.toString();
    }

    private static String csvRow(Object... values) {
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                row.append(',');
            }
            row.append(quote(values[index]));
        }
        return row.append('\n').toString();
    }

    private static String quote(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private record SummaryRow(
            UUID runId,
            String status,
            String costBasisMethod,
            String blockedReasons,
            String eventType,
            java.math.BigDecimal totalJpyValue,
            java.math.BigDecimal totalProfitLossJpy) {
    }
}
