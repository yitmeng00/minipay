package com.minipay.paymenthub.internal.persistence;

import com.minipay.paymenthub.internal.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}
