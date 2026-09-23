package jp.hyperliquid.taxtracker.setup;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SetupValidatorsConfig {

    @Bean
    SolanaAddressValidator solanaAddressValidator() {
        return new SolanaAddressValidator();
    }

    @Bean
    HyperliquidAccountAddressValidator hyperliquidAccountAddressValidator() {
        return new HyperliquidAccountAddressValidator();
    }
}
