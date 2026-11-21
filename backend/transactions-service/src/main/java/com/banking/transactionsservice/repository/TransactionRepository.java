package com.banking.transactionsservice.repository;

import com.banking.transactionsservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {
    
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId ORDER BY t.createdAt DESC, t.id DESC")
    List<Transaction> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
    
    List<Transaction> findByFromAccountIdOrToAccountIdOrderByCreatedAtDesc(Long fromAccountId, Long toAccountId);
    
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND (t.fromAccountId = :accountId OR t.toAccountId = :accountId) ORDER BY t.createdAt DESC")
    List<Transaction> findByUserIdAndAccountIdOrderByCreatedAtDesc(@Param("userId") Long userId, @Param("accountId") Long accountId);
    
    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    Optional<Transaction> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    interface SummaryCount {
        Transaction.TransactionType getType();
        Transaction.TransactionStatus getStatus();
        long getTotal();
    }

    @Query("""
            SELECT t.type AS type, t.status AS status, COUNT(t) AS total
            FROM Transaction t WHERE t.userId = :userId
            AND (:accountId IS NULL OR t.fromAccountId = :accountId OR t.toAccountId = :accountId)
            GROUP BY t.type, t.status
            """)
    List<SummaryCount> summarize(@Param("userId") Long userId, @Param("accountId") Long accountId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Transaction t SET t.status = :cancelled, t.processedAt = :processedAt
            WHERE t.id = :id AND t.userId = :userId AND t.status = :pending AND t.movementPosting = false
            """)
    int cancelPending(@Param("id") Long id, @Param("userId") Long userId,
                      @Param("pending") Transaction.TransactionStatus pending,
                      @Param("cancelled") Transaction.TransactionStatus cancelled,
                      @Param("processedAt") LocalDateTime processedAt);
    
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.status = :status ORDER BY t.createdAt DESC")
    List<Transaction> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") Transaction.TransactionStatus status);
    
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.type = :type ORDER BY t.createdAt DESC")
    List<Transaction> findByUserIdAndType(@Param("userId") Long userId, @Param("type") Transaction.TransactionType type);
}