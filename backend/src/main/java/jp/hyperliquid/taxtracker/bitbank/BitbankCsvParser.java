package jp.hyperliquid.taxtracker.bitbank;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class BitbankCsvParser {

    private static final List<String> TRADE_HEADERS = List.of(
            "注文id", "取引id", "通貨ペア", "現物/信用", "タイプ", "売/買", "数量", "価格",
            "実現損益", "発生手数料", "実現手数料", "実現利息", "m/t", "取引日時");

    private static final List<String> WITHDRAWAL_HEADERS = List.of(
            "コイン", "日時", "数量", "手数料", "ラベル", "ネットワーク", "アドレス", "Txid", "ステータス");

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .build();

    public ParsedFile parse(InputStream inputStream) throws IOException {
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, CSV_FORMAT)) {
            List<String> sourceHeaders = parser.getHeaderNames();
            List<String> normalizedHeaders = sourceHeaders.stream()
                    .map(BitbankCsvParser::stripBom)
                    .toList();

            if (normalizedHeaders.equals(TRADE_HEADERS)) {
                return parseTrades(parser, sourceHeaders, normalizedHeaders);
            }
            if (normalizedHeaders.equals(WITHDRAWAL_HEADERS)) {
                return parseWithdrawals(parser, sourceHeaders, normalizedHeaders);
            }

            throw new IllegalArgumentException("Unsupported bitbank CSV header: " + normalizedHeaders);
        }
    }

    private ParsedFile parseTrades(
            CSVParser parser,
            List<String> sourceHeaders,
            List<String> normalizedHeaders) {
        List<ParsedTrade> rows = new ArrayList<>();
        for (CSVRecord record : parser) {
            Map<String, String> columns = columns(record, sourceHeaders, normalizedHeaders);
            rows.add(new ParsedTrade(
                    value(columns, "注文id"),
                    value(columns, "取引id"),
                    value(columns, "通貨ペア"),
                    value(columns, "現物/信用"),
                    value(columns, "タイプ"),
                    value(columns, "売/買"),
                    decimal(columns, "数量"),
                    decimal(columns, "価格"),
                    decimal(columns, "実現損益"),
                    decimal(columns, "発生手数料"),
                    decimal(columns, "実現手数料"),
                    decimal(columns, "実現利息"),
                    value(columns, "m/t"),
                    value(columns, "取引日時"),
                    columns));
        }
        return new ParsedFile(FileType.TRADES, rows);
    }

    private ParsedFile parseWithdrawals(
            CSVParser parser,
            List<String> sourceHeaders,
            List<String> normalizedHeaders) {
        List<ParsedWithdrawal> rows = new ArrayList<>();
        for (CSVRecord record : parser) {
            Map<String, String> columns = columns(record, sourceHeaders, normalizedHeaders);
            rows.add(new ParsedWithdrawal(
                    value(columns, "コイン"),
                    value(columns, "日時"),
                    decimal(columns, "数量"),
                    decimal(columns, "手数料"),
                    value(columns, "ラベル"),
                    value(columns, "ネットワーク"),
                    value(columns, "アドレス"),
                    value(columns, "Txid"),
                    value(columns, "ステータス"),
                    columns));
        }
        return new ParsedFile(FileType.WITHDRAWALS, rows);
    }

    private static Map<String, String> columns(
            CSVRecord record,
            List<String> sourceHeaders,
            List<String> normalizedHeaders) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (int index = 0; index < sourceHeaders.size(); index++) {
            columns.put(normalizedHeaders.get(index), record.get(sourceHeaders.get(index)));
        }
        return Collections.unmodifiableMap(columns);
    }

    private static String value(Map<String, String> columns, String name) {
        String value = columns.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static BigDecimal decimal(Map<String, String> columns, String name) {
        String value = value(columns, name);
        return value == null ? null : new BigDecimal(value);
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    public enum FileType {
        TRADES,
        WITHDRAWALS
    }

    public record ParsedFile(FileType type, List<? extends ParsedRow> rows) {
        public ParsedFile {
            rows = List.copyOf(rows);
        }
    }

    public sealed interface ParsedRow permits ParsedTrade, ParsedWithdrawal {
        Map<String, String> columns();

        String occurredAtRaw();

        String sourceRecordId();
    }

    public record ParsedTrade(
            String orderId,
            String tradeId,
            String pair,
            String spotOrMargin,
            String orderType,
            String side,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal realizedPnl,
            BigDecimal occurredFee,
            BigDecimal realizedFee,
            BigDecimal interest,
            String makerTaker,
            String occurredAtRaw,
            Map<String, String> columns) implements ParsedRow {

        @Override
        public String sourceRecordId() {
            return tradeId;
        }
    }

    public record ParsedWithdrawal(
            String asset,
            String occurredAtRaw,
            BigDecimal quantity,
            BigDecimal fee,
            String label,
            String network,
            String address,
            String txid,
            String status,
            Map<String, String> columns) implements ParsedRow {

        @Override
        public String sourceRecordId() {
            return txid;
        }
    }
}
