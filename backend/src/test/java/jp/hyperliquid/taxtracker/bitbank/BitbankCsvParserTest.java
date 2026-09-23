package jp.hyperliquid.taxtracker.bitbank;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BitbankCsvParserTest {

    private final BitbankCsvParser parser = new BitbankCsvParser();

    @Test
    void parsesTradeCsvAndKeepsNonSolRowsForRawImport() throws Exception {
        String csv = "注文id,取引id,通貨ペア,現物/信用,タイプ,売/買,数量,価格,実現損益,発生手数料,実現手数料,実現利息,m/t,取引日時\n"
                + "order-sol,trade-sol,sol_jpy,現物,limit,buy,1.25000000,100000,,,,,taker,2026-09-10 22:45:07.989\n"
                + "order-xrp,trade-xrp,xrp_jpy,現物,market,sell,2.00000000,100,,,,,taker,2026-09-11 12:00:00.000\n";

        BitbankCsvParser.ParsedFile result = parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.type()).isEqualTo(BitbankCsvParser.FileType.TRADES);
        assertThat(result.rows()).hasSize(2);
        BitbankCsvParser.ParsedTrade solRow = (BitbankCsvParser.ParsedTrade) result.rows().get(0);
        BitbankCsvParser.ParsedTrade xrpRow = (BitbankCsvParser.ParsedTrade) result.rows().get(1);
        assertThat(solRow.pair()).isEqualTo("sol_jpy");
        assertThat(solRow.quantity()).hasToString("1.25000000");
        assertThat(solRow.makerTaker()).isEqualTo("taker");
        assertThat(xrpRow.pair()).isEqualTo("xrp_jpy");
        assertThat(xrpRow.columns()).containsEntry("通貨ペア", "xrp_jpy");
    }

    @Test
    void parsesWithdrawalCsvIncludingFeeNetworkAddressTxidAndStatus() throws Exception {
        String csv = "コイン,日時,数量,手数料,ラベル,ネットワーク,アドレス,Txid,ステータス\n"
                + "sol,2026/09/23 14:47:22,16.28000000,0.00900000,Phantom,solana,Destination,Signature,DONE\n";

        BitbankCsvParser.ParsedFile result = parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.type()).isEqualTo(BitbankCsvParser.FileType.WITHDRAWALS);
        BitbankCsvParser.ParsedWithdrawal row = (BitbankCsvParser.ParsedWithdrawal) result.rows().get(0);
        assertThat(row.asset()).isEqualTo("sol");
        assertThat(row.fee()).hasToString("0.00900000");
        assertThat(row.network()).isEqualTo("solana");
        assertThat(row.address()).isEqualTo("Destination");
        assertThat(row.txid()).isEqualTo("Signature");
        assertThat(row.status()).isEqualTo("DONE");
    }

    @Test
    void rejectsUnknownHeadersInsteadOfGuessingTheContract() {
        String csv = "unknown,header\nvalue,value\n";

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported bitbank CSV header");
    }

}
