package jp.hyperliquid.taxtracker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import jp.hyperliquid.taxtracker.review.ClassificationReviewService;
import jp.hyperliquid.taxtracker.review.DataErrorService;
import jp.hyperliquid.taxtracker.review.RecalculationRequestService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users/{userId}")
public class ReviewController {

    private final ClassificationReviewService reviewService;
    private final DataErrorService errorService;
    private final RecalculationRequestService recalculationRequestService;

    public ReviewController(
            ClassificationReviewService reviewService,
            DataErrorService errorService,
            RecalculationRequestService recalculationRequestService) {
        this.reviewService = reviewService;
        this.errorService = errorService;
        this.recalculationRequestService = recalculationRequestService;
    }

    @PostMapping("/reviews/{reviewId}/resolve")
    public CoreDomain.ClassificationReview resolveReview(
            @PathVariable UUID userId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody ResolveReviewRequest request) {
        return reviewService.resolve(userId, reviewId, request.beforeClassification(),
                request.afterClassification(), request.reason());
    }

    @PostMapping("/errors/{errorId}/resolve")
    public CoreDomain.DataError resolveError(
            @PathVariable UUID userId,
            @PathVariable UUID errorId) {
        return errorService.resolve(userId, errorId);
    }

    @PostMapping("/recalculation-requests")
    public CoreDomain.RecalculationRequest requestRecalculation(
            @PathVariable UUID userId,
            @Valid @RequestBody RecalculationRequest request) {
        return recalculationRequestService.request(userId, request.sourceType(),
                request.sourceId(), request.reason());
    }

    public record ResolveReviewRequest(
            String beforeClassification,
            @NotBlank String afterClassification,
            String reason) {
    }

    public record RecalculationRequest(
            @NotBlank String sourceType,
            UUID sourceId,
            @NotBlank String reason) {
    }
}
