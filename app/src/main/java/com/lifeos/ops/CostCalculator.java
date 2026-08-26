package com.lifeos.ops;

/**
 * AI money is USD micros (1 USD = 1_000_000), never a float column.
 * Price card is "USD micros per 1 million tokens".
 */
public final class CostCalculator {

    public static final long MICROS_PER_USD = 1_000_000L;
    public static final long TOKENS_PER_MTOK = 1_000_000L;

    private CostCalculator() {}

    public static long costMicros(long promptTokens, long completionTokens,
                                  long inputUsdMicrosPerMtok, long outputUsdMicrosPerMtok) {
        long in = Math.max(0, promptTokens) * Math.max(0, inputUsdMicrosPerMtok) / TOKENS_PER_MTOK;
        long out = Math.max(0, completionTokens) * Math.max(0, outputUsdMicrosPerMtok) / TOKENS_PER_MTOK;
        return in + out;
    }

    public static String usdDisplay(long micros) {
        long abs = Math.abs(micros);
        long dollars = abs / MICROS_PER_USD;
        long rest = abs % MICROS_PER_USD;
        String sign = micros < 0 ? "-" : "";
        return sign + "$" + dollars + "." + String.format("%06d", rest);
    }
}
