package com.minipay.paymenthub.internal.persistence;

import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.internal.domain.Payment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByUetr(UUID uetr);

    @Query(value = "SELECT nextval('payment.instruction_seq')", nativeQuery = true)
    long nextInstructionSeq();

    /** Payments the recovery sweeper should push on: stuck mid-pipeline, or warehoused and now due. */
    @Query("""
            SELECT p.id FROM Payment p
             WHERE p.status IN :inFlight
               AND (p.updatedAt < :staleBefore OR (p.status = :reserved AND p.requestedExecDate <= :today))
             ORDER BY p.createdAt
            """)
    List<UUID> findResumable(@Param("inFlight") Collection<PaymentStatus> inFlight,
                             @Param("reserved") PaymentStatus reserved,
                             @Param("staleBefore") Instant staleBefore,
                             @Param("today") LocalDate today,
                             Pageable limit);

    @Query("SELECT p.status, count(p) FROM Payment p GROUP BY p.status")
    List<Object[]> countGroupedByStatus();
}
