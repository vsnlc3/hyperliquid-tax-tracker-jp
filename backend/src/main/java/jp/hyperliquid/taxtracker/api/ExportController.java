package jp.hyperliquid.taxtracker.api;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/annual-summary.csv")
    public ResponseEntity<byte[]> annualSummary(
            @RequestParam UUID userId, @RequestParam int targetYear) {
        return csv("annual-summary.csv", exportService.annualSummaryCsv(userId, targetYear));
    }

    @GetMapping("/transactions.csv")
    public ResponseEntity<byte[]> transactions(
            @RequestParam UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return csv("transactions.csv", exportService.transactionsCsv(userId, from, to));
    }

    @GetMapping("/coverage.csv")
    public ResponseEntity<byte[]> coverage(@RequestParam UUID userId) {
        return csv("coverage.csv", exportService.coverageCsv(userId));
    }

    @GetMapping("/needs-review.csv")
    public ResponseEntity<byte[]> needsReview(@RequestParam UUID userId) {
        return csv("needs-review.csv", exportService.needsReviewCsv(userId));
    }

    private static ResponseEntity<byte[]> csv(String fileName, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        return ResponseEntity.ok().headers(headers).body(body.getBytes(StandardCharsets.UTF_8));
    }
}
