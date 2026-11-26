package com.minipay.compliance.api;

import java.util.List;

/**
 * @param holdThreshold     a score at or above this holds the payment
 * @param fourEyesThreshold a hit at or above this needs two people to release
 * @param lists             the list codes this bank screens against
 */
public record ComplianceSettings(int holdThreshold, int fourEyesThreshold, long slaMinutes, List<String> lists) {
}
