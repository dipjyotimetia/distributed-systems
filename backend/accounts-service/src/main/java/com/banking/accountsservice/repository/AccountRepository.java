package com.banking.accountsservice.repository;

import com.banking.accountsservice.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {
    
    @Query("SELECT a FROM Account a WHERE a.userId = :userId ORDER BY a.createdAt DESC, a.id DESC")
    List<Account> findByUserId(@Param("userId") Long userId);

    interface SummaryGroup {
        String getAccountType();
        Account.Status getStatus();
        BigDecimal getBalance();
        long getTotal();
    }

    @Query("""
            SELECT a.accountType AS accountType, a.status AS status, SUM(a.balance) AS balance, COUNT(a) AS total
            FROM Account a WHERE a.userId = :userId GROUP BY a.accountType, a.status
            """)
    List<SummaryGroup> summarize(@Param("userId") Long userId);
    
    Optional<Account> findByAccountNumber(String accountNumber);
    
    Optional<Account> findByIdAndUserId(Long id, Long userId);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.userId = :userId")
    Optional<Account> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT a FROM Account a WHERE a.userId = :userId AND a.id = :accountId")
    Optional<Account> findByUserIdAndAccountId(@Param("userId") Long userId, @Param("accountId") Long accountId);
    
    boolean existsByAccountNumber(String accountNumber);
    
    boolean existsByIdAndUserId(Long id, Long userId);
}