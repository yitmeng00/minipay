package com.minipay.compliance.api;

import java.time.Instant;
import java.util.Map;

/**
 * The queue's header figures; "today" is the bank's business day.
 *
 * @param pendingByList     open cases per list code (a case counts for each list it has hits on)
 * @param medianDecisionMinutes median time from hold to closing decision, over cases closed today; null if none
 */
public record ComplianceStats(int pending, int awaitingApproval, int escalated, Instant oldestHeldAt, int releasedToday,
                              int rejectedToday, Long medianDecisionMinutes, long slaMinutes, Map<String, Integer> pendingByList) {
}
