package com.lifeos.security.approval;

import java.util.Map;

/**
 * How risky one request is (architecture doc §6).
 *
 * @param tier    2 = impactful (an agent may request it; the owner approves with a passkey),
 *                3 = admin (never with an API key; owner session with a fresh passkey only)
 * @param action  stable name, e.g. {@code receipt.confirm}, {@code ops.prices.update}
 * @param summary what will happen, rendered by the server from its own data (shown on the approval page)
 */
public record RiskAssessment(int tier, String action, String headline, Map<String, Object> summary) {

    public static final int IMPACTFUL = 2, ADMIN = 3;
}
