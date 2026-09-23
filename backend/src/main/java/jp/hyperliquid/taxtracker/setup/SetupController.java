package jp.hyperliquid.taxtracker.setup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @PostMapping("/wallets")
    public ResponseEntity<CoreDomain.Wallet> registerWallet(
            @Valid @RequestBody RegisterWalletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                setupService.registerWallet(request.userId(), request.address(), request.label()));
    }

    @PostMapping("/hyperliquid-accounts")
    public ResponseEntity<CoreDomain.HyperliquidAccount> registerHyperliquidAccount(
            @Valid @RequestBody RegisterHyperliquidAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                setupService.registerHyperliquidAccount(
                        request.userId(),
                        request.accountAddress(),
                        request.solanaDepositAddress(),
                        request.accountMode()));
    }

    public record RegisterWalletRequest(
            @NotNull UUID userId,
            @NotBlank String address,
            String label) {
    }

    public record RegisterHyperliquidAccountRequest(
            @NotNull UUID userId,
            @NotBlank String accountAddress,
            @NotBlank String solanaDepositAddress,
            @NotBlank String accountMode) {
    }
}
