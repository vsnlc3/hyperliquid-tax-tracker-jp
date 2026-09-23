package jp.hyperliquid.taxtracker.solana;

import com.fasterxml.jackson.databind.JsonNode;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class SolanaTransactionParser {

    private static final String SYSTEM_PROGRAM = "11111111111111111111111111111111";

    public ParsedTransaction parse(String signature, JsonNode response, JsonNode signatureSummary) {
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            CoreDomain.SolanaTransactionStatus status = hasError(signatureSummary)
                    ? CoreDomain.SolanaTransactionStatus.FAILED
                    : CoreDomain.SolanaTransactionStatus.NOT_FOUND;
            return new ParsedTransaction(signature, null, null, status, null, Set.of(), List.of(), List.of(), null,
                    null);
        }

        JsonNode meta = result.path("meta");
        CoreDomain.SolanaTransactionStatus status = meta.has("err") && !meta.get("err").isNull()
                ? CoreDomain.SolanaTransactionStatus.FAILED
                : CoreDomain.SolanaTransactionStatus.SUCCESS;
        Set<String> signers = signers(result);
        List<CoreDomain.SystemTransfer> systemTransfers = new ArrayList<>();
        List<CoreDomain.TokenTransfer> tokenTransfers = new ArrayList<>();
        int instructionIndex = 0;
        JsonNode message = result.path("transaction").path("message");
        for (JsonNode instruction : message.path("instructions")) {
            parseInstruction(instruction, instructionIndex++, systemTransfers, tokenTransfers);
        }
        for (JsonNode innerInstructionGroup : meta.path("innerInstructions")) {
            for (JsonNode instruction : innerInstructionGroup.path("instructions")) {
                parseInstruction(instruction, instructionIndex++, systemTransfers, tokenTransfers);
            }
        }

        return new ParsedTransaction(
                signature,
                longValue(result, "slot"),
                instantFromEpochSeconds(result.get("blockTime")),
                status,
                version(result.get("version")),
                signers,
                systemTransfers,
                tokenTransfers,
                longValue(meta, "fee"),
                null);
    }

    private static void parseInstruction(
            JsonNode instruction,
            int instructionIndex,
            List<CoreDomain.SystemTransfer> systemTransfers,
            List<CoreDomain.TokenTransfer> tokenTransfers) {
        String program = text(instruction, "program");
        String programId = text(instruction, "programId");
        JsonNode parsed = instruction.get("parsed");
        if (parsed == null || !parsed.isObject()) {
            return;
        }
        JsonNode info = parsed.path("info");
        String type = text(parsed, "type");
        if (("system".equals(program) || SYSTEM_PROGRAM.equals(programId)) && "transfer".equals(type)) {
            Long lamports = longValue(info, "lamports");
            if (lamports != null) {
                systemTransfers.add(new CoreDomain.SystemTransfer(
                        text(info, "source"), text(info, "destination"), lamports));
            }
            return;
        }
        if (("spl-token".equals(program) || "spl-token-2022".equals(program))
                && ("transfer".equals(type) || "transferChecked".equals(type))) {
            JsonNode tokenAmount = info.path("tokenAmount");
            String amountText = text(info, "amount");
            if (amountText == null) {
                amountText = text(tokenAmount, "amount");
            }
            BigDecimal rawAmount = decimal(amountText);
            Integer decimals = integerValue(tokenAmount, "decimals");
            BigDecimal uiAmount = decimal(text(tokenAmount, "uiAmountString"));
            tokenTransfers.add(new CoreDomain.TokenTransfer(
                    text(info, "source"),
                    text(info, "destination"),
                    text(info, "mint"),
                    rawAmount,
                    decimals,
                    uiAmount));
        }
    }

    private static Set<String> signers(JsonNode result) {
        Set<String> signers = new LinkedHashSet<>();
        JsonNode accountKeys = result.path("transaction").path("message").path("accountKeys");
        int requiredSignatures = result.path("transaction").path("signatures").size();
        int index = 0;
        for (JsonNode accountKey : accountKeys) {
            String address = accountKey.isTextual() ? accountKey.asText() : text(accountKey, "pubkey");
            boolean isSigner = accountKey.isObject()
                    ? accountKey.path("signer").asBoolean(false)
                    : index < requiredSignatures;
            if (isSigner && address != null) {
                signers.add(address);
            }
            index++;
        }
        return signers;
    }

    private static boolean hasError(JsonNode summary) {
        return summary != null && summary.has("err") && !summary.get("err").isNull();
    }

    private static String version(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.longValue();
    }

    private static Integer integerValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.intValue();
    }

    private static Instant instantFromEpochSeconds(JsonNode node) {
        Long epochSeconds = node == null || node.isNull() || !node.isNumber() ? null : node.longValue();
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    public record ParsedTransaction(
            String signature,
            Long slot,
            Instant blockTime,
            CoreDomain.SolanaTransactionStatus status,
            String version,
            Set<String> signerAddresses,
            List<CoreDomain.SystemTransfer> systemTransfers,
            List<CoreDomain.TokenTransfer> tokenTransfers,
            Long networkFeeLamports,
            Long priorityFeeLamports) {
    }
}
