package com.minipay.paymenthub.internal.persistence;

import com.minipay.paymenthub.api.PaymentQuery;
import com.minipay.paymenthub.internal.domain.Payment;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Translates the payments-list filters into a JPA criteria query. */
public final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    public static Specification<Payment> matching(PaymentQuery q) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (!q.statuses().isEmpty()) {
                where.add(root.get("status").in(q.statuses()));
            }
            if (q.direction() != null) {
                where.add(cb.equal(root.get("direction"), q.direction()));
            }
            if (q.currency() != null && !q.currency().isBlank()) {
                where.add(cb.equal(root.get("currency"), q.currency().toUpperCase(Locale.ROOT)));
            }
            // A payment not yet sent has no value date: filter on the requested execution date then.
            var effectiveDate = cb.coalesce(root.<java.time.LocalDate>get("valueDate"), root.<java.time.LocalDate>get("requestedExecDate"));
            if (q.valueDateFrom() != null) {
                where.add(cb.greaterThanOrEqualTo(effectiveDate, q.valueDateFrom()));
            }
            if (q.valueDateTo() != null) {
                where.add(cb.lessThanOrEqualTo(effectiveDate, q.valueDateTo()));
            }
            if (q.minAmount() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("amount"), q.minAmount()));
            }
            if (q.maxAmount() != null) {
                where.add(cb.lessThanOrEqualTo(root.get("amount"), q.maxAmount()));
            }
            if (q.text() != null && !q.text().isBlank()) {
                String t = q.text().trim();
                String like = "%" + t.toLowerCase(Locale.ROOT) + "%";
                List<Predicate> any = new ArrayList<>();
                for (String field : List.of("endToEndId", "instructionId", "debtorName", "debtorAccount",
                        "creditorName", "creditorAccount", "creditorAgentBic")) {
                    any.add(cb.like(cb.lower(root.get(field)), like));
                }
                try {
                    any.add(cb.equal(root.get("uetr"), UUID.fromString(t)));
                } catch (IllegalArgumentException notAUuid) {
                    // not a UETR: text match only
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
