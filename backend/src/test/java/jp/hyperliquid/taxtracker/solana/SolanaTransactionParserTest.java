package jp.hyperliquid.taxtracker.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SolanaTransactionParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SolanaTransactionParser parser = new SolanaTransactionParser();

    @Test
    void separatesNativeSystemTransferFromTokenTransferAndKeepsNetworkFee() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "jsonrpc":"2.0",
                  "result":{
                    "slot":123,
                    "blockTime":1727000000,
                    "transaction":{
                      "signatures":["sig"],
                      "message":{
                        "accountKeys":[
                          {"pubkey":"Wallet","signer":true,"writable":true},
                          {"pubkey":"Receiver","signer":false,"writable":true}
                        ],
                        "instructions":[
                          {"program":"system","parsed":{"type":"transfer","info":{"source":"Wallet","destination":"Receiver","lamports":1500000000}}},
                          {"program":"spl-token","parsed":{"type":"transferChecked","info":{"source":"TokenSource","destination":"TokenDestination","mint":"Mint","tokenAmount":{"amount":"42","decimals":6,"uiAmountString":"0.000042"}}}}
                        ]
                      }
                    },
                    "meta":{"err":null,"fee":5000,"innerInstructions":[]}
                  }
                }
                """);

        SolanaTransactionParser.ParsedTransaction parsed = parser.parse(
                "sig", response, objectMapper.readTree("{\"signature\":\"sig\",\"err\":null}"));

        assertThat(parsed.status()).isEqualTo(CoreDomain.SolanaTransactionStatus.SUCCESS);
        assertThat(parsed.signerAddresses()).containsExactly("Wallet");
        assertThat(parsed.systemTransfers()).singleElement()
                .satisfies(transfer -> {
                    assertThat(transfer.sourceAddress()).isEqualTo("Wallet");
                    assertThat(transfer.destinationAddress()).isEqualTo("Receiver");
                    assertThat(transfer.lamports()).isEqualTo(1_500_000_000L);
                });
        assertThat(parsed.tokenTransfers()).singleElement()
                .satisfies(transfer -> {
                    assertThat(transfer.mint()).isEqualTo("Mint");
                    assertThat(transfer.rawAmount()).hasToString("42");
                    assertThat(transfer.uiAmount()).hasToString("0.000042");
                });
        assertThat(parsed.networkFeeLamports()).isEqualTo(5000L);
        assertThat(parsed.priorityFeeLamports()).isNull();
    }

    @Test
    void keepsFailedAndNotFoundTransactionsAsNonSuccessStatuses() throws Exception {
        JsonNode failedResponse = objectMapper.readTree("""
                {"result":{"slot":1,"transaction":{"message":{"accountKeys":[]}},"meta":{"err":{"InstructionError":[0,"Custom"]},"fee":5000}}}
                """);
        SolanaTransactionParser.ParsedTransaction failed = parser.parse(
                "failed", failedResponse, objectMapper.readTree("{\"err\":{}}"));
        SolanaTransactionParser.ParsedTransaction notFound = parser.parse(
                "missing", objectMapper.readTree("{\"result\":null}"), objectMapper.readTree("{\"err\":null}"));

        assertThat(failed.status()).isEqualTo(CoreDomain.SolanaTransactionStatus.FAILED);
        assertThat(notFound.status()).isEqualTo(CoreDomain.SolanaTransactionStatus.NOT_FOUND);
    }
}
