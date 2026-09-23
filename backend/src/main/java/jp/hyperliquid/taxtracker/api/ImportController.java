package jp.hyperliquid.taxtracker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jp.hyperliquid.taxtracker.bitbank.BitbankImportService;
import jp.hyperliquid.taxtracker.bitbank.BitbankNormalizationService;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import jp.hyperliquid.taxtracker.hyperliquid.HyperliquidImportService;
import jp.hyperliquid.taxtracker.hyperliquid.HyperliquidNormalizationService;
import jp.hyperliquid.taxtracker.matching.TransferMatchingService;
import jp.hyperliquid.taxtracker.price.JpyRateService;
import jp.hyperliquid.taxtracker.solana.SolanaImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final BitbankImportService bitbankImportService;
    private final BitbankNormalizationService bitbankNormalizationService;
    private final SolanaImportService solanaImportService;
    private final HyperliquidImportService hyperliquidImportService;
    private final HyperliquidNormalizationService hyperliquidNormalizationService;
    private final JpyRateService jpyRateService;
    private final TransferMatchingService transferMatchingService;

    public ImportController(
            BitbankImportService bitbankImportService,
            BitbankNormalizationService bitbankNormalizationService,
            SolanaImportService solanaImportService,
            HyperliquidImportService hyperliquidImportService,
            HyperliquidNormalizationService hyperliquidNormalizationService,
            JpyRateService jpyRateService,
            TransferMatchingService transferMatchingService) {
        this.bitbankImportService = bitbankImportService;
        this.bitbankNormalizationService = bitbankNormalizationService;
        this.solanaImportService = solanaImportService;
        this.hyperliquidImportService = hyperliquidImportService;
        this.hyperliquidNormalizationService = hyperliquidNormalizationService;
        this.jpyRateService = jpyRateService;
        this.transferMatchingService = transferMatchingService;
    }

    @PostMapping(value = "/bitbank", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BitbankImportService.ImportResult> importBitbank(
            @RequestParam UUID userId,
            @RequestPart MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                bitbankImportService.importCsv(userId, file.getOriginalFilename(), file.getInputStream()));
    }

    @PostMapping("/bitbank/{userId}/normalize")
    public BitbankNormalizationService.NormalizationResult normalizeBitbank(@PathVariable UUID userId) {
        return bitbankNormalizationService.normalize(userId);
    }

    @PostMapping("/solana")
    public SolanaImportService.ImportResult importSolana(@Valid @RequestBody SolanaImportRequest request) {
        return solanaImportService.importWallet(
                request.userId(), request.walletId(), request.from(), request.to());
    }

    @PostMapping("/hyperliquid")
    public HyperliquidImportService.ImportResult importHyperliquid(
            @Valid @RequestBody HyperliquidImportRequest request) {
        return hyperliquidImportService.importAccount(
                request.userId(), request.accountId(), request.from(), request.to());
    }

    @PostMapping("/hyperliquid/{userId}/normalize")
    public HyperliquidNormalizationService.NormalizationResult normalizeHyperliquid(
            @PathVariable UUID userId) {
        return hyperliquidNormalizationService.normalize(userId);
    }

    @PostMapping("/prices")
    public JpyRateService.ImportResult importPrices(@Valid @RequestBody PriceImportRequest request) {
        return jpyRateService.importPrices(request.userId(), request.from(), request.to());
    }

    @PostMapping("/matching/{userId}")
    public TransferMatchingService.MatchResult matchTransfers(@PathVariable UUID userId) {
        return transferMatchingService.match(userId);
    }

    public record SolanaImportRequest(
            @NotNull UUID userId,
            @NotNull UUID walletId,
            @NotNull Instant from,
            @NotNull Instant to) {
    }

    public record HyperliquidImportRequest(
            @NotNull UUID userId,
            @NotNull UUID accountId,
            @NotNull Instant from,
            @NotNull Instant to) {
    }

    public record PriceImportRequest(
            @NotNull UUID userId,
            @NotNull Instant from,
            @NotNull Instant to) {
    }
}
