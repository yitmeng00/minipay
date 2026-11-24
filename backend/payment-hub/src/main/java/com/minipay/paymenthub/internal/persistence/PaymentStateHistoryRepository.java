package com.minipay.paymenthub.internal.persistence;

import com.minipay.paymenthub.internal.domain.PaymentStateHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentStateHistoryRepository extends JpaRepository<PaymentStateHistory, Long> {

    List<PaymentStateHistory> findByPaymentIdOrderByIdAsc(UUID paymentId);
}
