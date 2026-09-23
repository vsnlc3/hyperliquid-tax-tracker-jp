package jp.hyperliquid.taxtracker.matching;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferMatchingService {

    private final JdbcTemplate jdbcTemplate;

    public TransferMatchingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public MatchResult match(UUID userId) {
        List<UnifiedRow> allTransactions = transactions(userId);
        List<UnifiedRow> outgoing = allTransactions.stream()
                .filter(TransferMatchingService::isOutgoing)
                .toList();
        List<UnifiedRow> incoming = allTransactions.stream()
                .filter(TransferMatchingService::isIncoming)
                .toList();

        int matched = 0;
        int possible = 0;
        int unmatched = 0;
        for (UnifiedRow outgoingTransaction : outgoing) {
            List<ScoredCandidate> candidates = incoming.stream()
                    .map(incomingTransaction -> score(outgoingTransaction, incomingTransaction))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(ScoredCandidate::score).reversed())
                    .toList();
            if (candidates.isEmpty()) {
                persistLink(outgoingTransaction.id(), null, CoreDomain.MatchStatus.UNMATCHED,
                        BigDecimal.ZERO, "NO_CANDIDATE");
                unmatched++;
                continue;
            }

            ScoredCandidate best = candidates.get(0);
            long tiedBest = candidates.stream()
                    .filter(candidate -> candidate.score().compareTo(best.score()) == 0)
                    .count();
            if (best.strongMatch() && tiedBest == 1) {
                persistLink(outgoingTransaction.id(), best.incoming().id(), CoreDomain.MatchStatus.MATCHED,
                        best.score(), best.matchedBy());
                matched++;
            } else {
                for (ScoredCandidate candidate : candidates.subList(0, (int) tiedBest)) {
                    persistLink(outgoingTransaction.id(), candidate.incoming().id(), CoreDomain.MatchStatus.POSSIBLE,
                            candidate.score(), candidate.matchedBy());
                    possible++;
                }
            }
        }
        return new MatchResult(outgoing.size(), matched, possible, unmatched);
    }

    private Optional<ScoredCandidate> score(UnifiedRow outgoing, UnifiedRow incoming) {
        if (!routeMatches(outgoing, incoming)) {
            return Optional.empty();
        }
        boolean hashMatch = nonBlank(outgoing.transactionHash())
                && outgoing.transactionHash().equalsIgnoreCase(incoming.transactionHash());
        boolean addressMatch = addressMatch(outgoing, incoming);
        boolean amountMatch = exactAmount(outgoing, incoming);
        if (!hashMatch && !addressMatch && !amountMatch) {
            return Optional.empty();
        }

        BigDecimal score;
        String matchedBy;
        boolean strongMatch;
        if (hashMatch) {
            score = BigDecimal.ONE;
            matchedBy = "TRANSACTION_HASH";
            strongMatch = true;
        } else if (addressMatch && amountMatch) {
            score = new BigDecimal("0.95");
            matchedBy = "ADDRESS_AMOUNT_EXACT";
            strongMatch = true;
        } else if (amountMatch) {
            score = new BigDecimal("0.80");
            matchedBy = "AMOUNT_EXACT";
            strongMatch = false;
        } else {
            score = new BigDecimal("0.70");
            matchedBy = "ADDRESS_EXACT";
            strongMatch = false;
        }
        return Optional.of(new ScoredCandidate(incoming, score, matchedBy, strongMatch));
    }

    private boolean routeMatches(UnifiedRow outgoing, UnifiedRow incoming) {
        if ("BITBANK".equals(outgoing.source())) {
            return "SOLANA".equals(incoming.source())
                    && sameAsset(outgoing.asset(), incoming.asset());
        }
        if ("SOLANA".equals(outgoing.source())) {
            return "HYPERLIQUID".equals(incoming.source())
                    && "SOL".equalsIgnoreCase(outgoing.asset())
                    && ("SOL".equalsIgnoreCase(incoming.asset())
                    || "USOL".equalsIgnoreCase(incoming.asset()));
        }
        return false;
    }

    private boolean exactAmount(UnifiedRow outgoing, UnifiedRow incoming) {
        return amountCandidates(outgoing).stream()
                .anyMatch(outgoingAmount -> amountCandidates(incoming).stream()
                        .anyMatch(incomingAmount -> outgoingAmount.compareTo(incomingAmount) == 0));
    }

    private boolean addressMatch(UnifiedRow outgoing, UnifiedRow incoming) {
        List<String> outgoingAddresses = addresses(outgoing);
        List<String> incomingAddresses = addresses(incoming);
        return outgoingAddresses.stream().anyMatch(outgoingAddress -> incomingAddresses.stream()
                .anyMatch(incomingAddress -> sameAddress(outgoingAddress, incomingAddress)));
    }

    private List<UnifiedRow> transactions(UUID userId) {
        return jdbcTemplate.query("""
                SELECT id, source, dataset, transaction_type, asset, gross_amount, net_amount,
                       from_address, to_address, transaction_hash, occurred_at
                FROM unified_transactions
                WHERE user_id = ?
                ORDER BY occurred_at NULLS LAST, id
                """, (resultSet, rowNum) -> new UnifiedRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("dataset"),
                resultSet.getString("transaction_type"),
                resultSet.getString("asset"),
                resultSet.getBigDecimal("gross_amount"),
                resultSet.getBigDecimal("net_amount"),
                resultSet.getString("from_address"),
                resultSet.getString("to_address"),
                resultSet.getString("transaction_hash"),
                timestamp(resultSet.getTimestamp("occurred_at"))), userId);
    }

    private void persistLink(
            UUID outgoingId,
            UUID incomingId,
            CoreDomain.MatchStatus status,
            BigDecimal score,
            String matchedBy) {
        if (status == CoreDomain.MatchStatus.MATCHED && incomingId != null) {
            Integer outgoingMatches = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM transfer_links
                    WHERE outgoing_transaction_id = ? AND match_status = 'MATCHED'
                      AND incoming_transaction_id <> ?
                    """, Integer.class, outgoingId, incomingId);
            Integer incomingMatches = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM transfer_links
                    WHERE incoming_transaction_id = ? AND match_status = 'MATCHED'
                      AND outgoing_transaction_id <> ?
                    """, Integer.class, incomingId, outgoingId);
            if ((outgoingMatches != null && outgoingMatches > 0)
                    || (incomingMatches != null && incomingMatches > 0)) {
                status = CoreDomain.MatchStatus.POSSIBLE;
                matchedBy = matchedBy + "_CONFLICT";
            }
        }

        Optional<UUID> existingId = existingLink(outgoingId, incomingId);
        if (existingId.isPresent()) {
            CoreDomain.MatchStatus existingStatus = jdbcTemplate.queryForObject("""
                    SELECT match_status FROM transfer_links WHERE id = ?
                    """, (resultSet, rowNum) -> CoreDomain.MatchStatus.valueOf(resultSet.getString(1)),
                    existingId.get());
            if (existingStatus == CoreDomain.MatchStatus.MATCHED
                    && status != CoreDomain.MatchStatus.MATCHED) {
                return;
            }
            jdbcTemplate.update("""
                    UPDATE transfer_links
                    SET match_status = ?, match_score = ?, matched_by = ?
                    WHERE id = ?
                    """, status.name(), score, matchedBy, existingId.get());
            return;
        }

        CoreDomain.MatchStatus persistedStatus = status;
        String persistedMatchedBy = matchedBy;
        jdbcTemplate.update("""
                INSERT INTO transfer_links (
                    outgoing_transaction_id, incoming_transaction_id, match_status, match_score, matched_by
                ) VALUES (?, ?, ?, ?, ?)
                """, preparedStatement -> {
            preparedStatement.setObject(1, outgoingId);
            preparedStatement.setObject(2, incomingId);
            preparedStatement.setString(3, persistedStatus.name());
            preparedStatement.setBigDecimal(4, score);
            preparedStatement.setString(5, persistedMatchedBy);
        });
    }

    private Optional<UUID> existingLink(UUID outgoingId, UUID incomingId) {
        if (incomingId == null) {
            return jdbcTemplate.query("""
                    SELECT id FROM transfer_links
                    WHERE outgoing_transaction_id = ? AND incoming_transaction_id IS NULL
                    """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class), outgoingId)
                    .stream().findFirst();
        }
        return jdbcTemplate.query("""
                SELECT id FROM transfer_links
                WHERE outgoing_transaction_id = ? AND incoming_transaction_id = ?
                """, (resultSet, rowNum) -> resultSet.getObject("id", UUID.class), outgoingId, incomingId)
                .stream().findFirst();
    }

    private static boolean isOutgoing(UnifiedRow transaction) {
        return "TRANSFER_OUT".equals(transaction.transactionType())
                && ("BITBANK".equals(transaction.source()) || "SOLANA".equals(transaction.source()));
    }

    private static boolean isIncoming(UnifiedRow transaction) {
        return "TRANSFER_IN".equals(transaction.transactionType())
                && ("SOLANA".equals(transaction.source()) || "HYPERLIQUID".equals(transaction.source()));
    }

    private static List<BigDecimal> amountCandidates(UnifiedRow transaction) {
        List<BigDecimal> candidates = new ArrayList<>(2);
        if (transaction.grossAmount() != null) {
            candidates.add(transaction.grossAmount());
        }
        if (transaction.netAmount() != null
                && (transaction.grossAmount() == null
                || transaction.netAmount().compareTo(transaction.grossAmount()) != 0)) {
            candidates.add(transaction.netAmount());
        }
        return candidates;
    }

    private static List<String> addresses(UnifiedRow transaction) {
        List<String> addresses = new ArrayList<>(2);
        if (nonBlank(transaction.fromAddress())) {
            addresses.add(transaction.fromAddress());
        }
        if (nonBlank(transaction.toAddress())) {
            addresses.add(transaction.toAddress());
        }
        return addresses;
    }

    private static boolean sameAsset(String left, String right) {
        return nonBlank(left) && nonBlank(right) && left.equalsIgnoreCase(right);
    }

    private static boolean sameAddress(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.startsWith("0x") && right.startsWith("0x")) {
            return left.equalsIgnoreCase(right);
        }
        return left.equals(right);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant timestamp(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record MatchResult(int outgoingCount, int matchedCount, int possibleCount, int unmatchedCount) {
    }

    private record UnifiedRow(
            UUID id,
            String source,
            String dataset,
            String transactionType,
            String asset,
            BigDecimal grossAmount,
            BigDecimal netAmount,
            String fromAddress,
            String toAddress,
            String transactionHash,
            Instant occurredAt) {
    }

    private record ScoredCandidate(
            UnifiedRow incoming,
            BigDecimal score,
            String matchedBy,
            boolean strongMatch) {
    }
}
