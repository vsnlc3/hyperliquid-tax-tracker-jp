package jp.hyperliquid.taxtracker.setup;

import jp.hyperliquid.taxtracker.domain.CoreDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SetupService {

    private final JdbcTemplate jdbcTemplate;
    private final SolanaAddressValidator solanaAddressValidator;
    private final HyperliquidAccountAddressValidator accountAddressValidator;

    public SetupService(
            JdbcTemplate jdbcTemplate,
            SolanaAddressValidator solanaAddressValidator,
            HyperliquidAccountAddressValidator accountAddressValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.solanaAddressValidator = solanaAddressValidator;
        this.accountAddressValidator = accountAddressValidator;
    }

    @Transactional
    public CoreDomain.Wallet registerWallet(UUID userId, String address, String label) {
        requireSolanaAddress(address);
        return jdbcTemplate.queryForObject("""
                INSERT INTO wallets (user_id, network, address, label, wallet_type)
                VALUES (?, 'SOLANA', ?, ?, 'USER_WALLET')
                RETURNING id, user_id, network, address, label, wallet_type, created_at
                """, (resultSet, rowNum) -> new CoreDomain.Wallet(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                CoreDomain.Network.valueOf(resultSet.getString("network")),
                resultSet.getString("address"),
                resultSet.getString("label"),
                CoreDomain.WalletType.valueOf(resultSet.getString("wallet_type")),
                resultSet.getTimestamp("created_at").toInstant()),
                userId, address, label);
    }

    @Transactional
    public CoreDomain.HyperliquidAccount registerHyperliquidAccount(
            UUID userId,
            String accountAddress,
            String solanaDepositAddress,
            String accountMode) {
        requireAccountAddress(accountAddress);
        requireSolanaAddress(solanaDepositAddress);
        CoreDomain.AccountMode mode = parseAccountMode(accountMode);
        if (mode != CoreDomain.AccountMode.UNIFIED) {
            throw new UnsupportedAccountModeException(mode.name());
        }

        return jdbcTemplate.queryForObject("""
                INSERT INTO hyperliquid_accounts (
                    user_id, account_address, solana_deposit_address, account_mode
                )
                VALUES (?, ?, ?, ?)
                RETURNING id, user_id, account_address, solana_deposit_address, account_mode, created_at
                """, (resultSet, rowNum) -> new CoreDomain.HyperliquidAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("account_address"),
                resultSet.getString("solana_deposit_address"),
                CoreDomain.AccountMode.valueOf(resultSet.getString("account_mode")),
                resultSet.getTimestamp("created_at").toInstant()),
                userId, accountAddress, solanaDepositAddress, mode.name());
    }

    private void requireSolanaAddress(String address) {
        if (!solanaAddressValidator.isValid(address)) {
            throw new InvalidAddressException("Invalid Solana address");
        }
    }

    private void requireAccountAddress(String address) {
        if (!accountAddressValidator.isValid(address)) {
            throw new InvalidAddressException("Invalid Hyperliquid account address");
        }
    }

    private CoreDomain.AccountMode parseAccountMode(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidAddressException("Account mode is required");
        }
        try {
            return CoreDomain.AccountMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new InvalidAddressException("Unsupported account mode value");
        }
    }

    public static class InvalidAddressException extends RuntimeException {
        public InvalidAddressException(String message) {
            super(message);
        }
    }

    public static class UnsupportedAccountModeException extends RuntimeException {
        public UnsupportedAccountModeException(String mode) {
            super("Account mode is outside the MVP scope: " + mode);
        }
    }
}
