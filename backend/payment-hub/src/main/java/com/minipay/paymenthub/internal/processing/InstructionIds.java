package com.minipay.paymenthub.internal.processing;

import com.minipay.paymenthub.internal.persistence.PaymentRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Our instruction ids: "<BIC4><yyMMdd><6-digit sequence>", e.g. MNPA261124000042. Unique per sending bank
 * and day, and 16 characters: CBPR+ limits InstrId to 16 during the MT/MX coexistence period, so it
 * still fits MT103 :20: when a bank on the route translates the payment. An InstrId is point-to-point:
 * a bank that forwards a payment gives it its own.
 */
@Component
public class InstructionIds {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private final PaymentRepository payments;
    private final String bic4;

    public InstructionIds(PaymentRepository payments, @Value("${minipay.bank.bic}") String bankBic) {
        this.payments = payments;
        this.bic4 = bankBic.substring(0, 4);
    }

    public String next(LocalDate businessDate) {
        return bic4 + businessDate.format(YYMMDD) + String.format("%06d", payments.nextInstructionSeq() % 1_000_000);
    }
}
