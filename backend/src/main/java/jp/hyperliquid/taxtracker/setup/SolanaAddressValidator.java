package jp.hyperliquid.taxtracker.setup;

import java.math.BigInteger;

public class SolanaAddressValidator {

    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    public boolean isValid(String address) {
        if (address == null || address.isBlank() || !address.equals(address.trim())) {
            return false;
        }
        try {
            return decodeBase58(address).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] decodeBase58(String value) {
        BigInteger number = BigInteger.ZERO;
        for (char character : value.toCharArray()) {
            int digit = BASE58_ALPHABET.indexOf(character);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base58 character");
            }
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }

        byte[] numberBytes = number.equals(BigInteger.ZERO) ? new byte[0] : number.toByteArray();
        int firstNonZero = 0;
        while (firstNonZero < numberBytes.length - 1 && numberBytes[firstNonZero] == 0) {
            firstNonZero++;
        }
        int leadingZeroBytes = 0;
        while (leadingZeroBytes < value.length() && value.charAt(leadingZeroBytes) == '1') {
            leadingZeroBytes++;
        }

        int numberLength = numberBytes.length == 0 ? 0 : numberBytes.length - firstNonZero;
        byte[] decoded = new byte[leadingZeroBytes + numberLength];
        if (numberLength > 0) {
            System.arraycopy(numberBytes, firstNonZero, decoded, leadingZeroBytes, numberLength);
        }
        return decoded;
    }
}
