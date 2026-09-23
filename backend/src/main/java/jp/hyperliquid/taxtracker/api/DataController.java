package jp.hyperliquid.taxtracker.api;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import jp.hyperliquid.taxtracker.review.ClassificationReviewService;
import jp.hyperliquid.taxtracker.review.DataErrorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users/{userId}")
public class DataController {

    private final TimelineService timelineService;
    private final CoverageQueryService coverageQueryService;
    private final ClassificationReviewService reviewService;
    private final DataErrorService errorService;

    public DataController(
            TimelineService timelineService,
            CoverageQueryService coverageQueryService,
            ClassificationReviewService reviewService,
            DataErrorService errorService) {
        this.timelineService = timelineService;
        this.coverageQueryService = coverageQueryService;
        this.reviewService = reviewService;
        this.errorService = errorService;
    }

    @GetMapping("/timeline")
    public List<TimelineService.TimelineRow> timeline(
            @PathVariable UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return timelineService.find(userId, from, to);
    }

    @GetMapping("/coverage")
    public List<CoreDomain.DataImportStatus> coverage(@PathVariable UUID userId) {
        return coverageQueryService.findLatest(userId);
    }

    @GetMapping("/reviews")
    public List<CoreDomain.ClassificationReview> reviews(@PathVariable UUID userId) {
        return reviewService.openReviews(userId);
    }

    @GetMapping("/errors")
    public List<CoreDomain.DataError> errors(@PathVariable UUID userId) {
        return errorService.openErrors(userId);
    }
}
