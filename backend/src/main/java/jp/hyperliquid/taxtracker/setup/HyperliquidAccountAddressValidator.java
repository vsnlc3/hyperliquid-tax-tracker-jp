package jp.hyperliquid.taxtracker.setup;

import java.util.regex.Pattern;

public class HyperliquidAccountAddressValidator {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

    public boolean isValid(String address) {
        return address != null && ADDRESS.matcher(address).matches();
    }
}
