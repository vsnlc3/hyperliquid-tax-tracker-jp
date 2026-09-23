package jp.hyperliquid.taxtracker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jp.hyperliquid.taxtracker.costbasis.CostBasisSettingService;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/users/{userId}/cost-basis")
public class CostBasisController {

    private final CostBasisSettingService settingService;

    public CostBasisController(CostBasisSettingService settingService) {
        this.settingService = settingService;
    }

    @PostMapping("/settings")
    public ResponseEntity<CoreDomain.CostBasisSetting> saveSetting(
            @PathVariable UUID userId,
            @Valid @RequestBody CostBasisSettingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settingService.saveSetting(
                userId, request.asset(), request.method(), request.effectiveFrom()));
    }

    @PostMapping("/opening-balance")
    public ResponseEntity<CoreDomain.CostBasisOpeningBalance> saveOpeningBalance(
            @PathVariable UUID userId,
            @Valid @RequestBody OpeningBalanceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settingService.saveOpeningBalance(
                userId, request.asset(), request.asOfDate(), request.quantity(),
                request.bookValueJpy(), request.inputSource()));
    }

    public record CostBasisSettingRequest(
            @NotBlank String asset,
            @NotNull CoreDomain.CostBasisMethod method,
            @NotNull LocalDate effectiveFrom) {
    }

    public record OpeningBalanceRequest(
            @NotBlank String asset,
            @NotNull LocalDate asOfDate,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal bookValueJpy,
            @NotBlank String inputSource) {
    }
}
