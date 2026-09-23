package jp.hyperliquid.taxtracker.setup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddressValidatorTest {

    private final SolanaAddressValidator solana = new SolanaAddressValidator();
    private final HyperliquidAccountAddressValidator hyperliquid = new HyperliquidAccountAddressValidator();

    @Test
    void acceptsStructurallyValidSolanaBase58Address() {
        assertThat(solana.isValid("11111111111111111111111111111111")).isTrue();
    }

    @Test
    void rejectsMalformedSolanaAddress() {
        assertThat(solana.isValid("0OIl")).isFalse();
        assertThat(solana.isValid("1111111111111111111111111111111")).isFalse();
    }

    @Test
    void acceptsHexHyperliquidAccountAddress() {
        assertThat(hyperliquid.isValid("0x0000000000000000000000000000000000000000")).isTrue();
        assertThat(hyperliquid.isValid("0X0000000000000000000000000000000000000000")).isFalse();
    }

    @Test
    void rejectsMalformedHyperliquidAccountAddress() {
        assertThat(hyperliquid.isValid("0x1234")).isFalse();
        assertThat(hyperliquid.isValid("not-an-address")).isFalse();
    }
}
