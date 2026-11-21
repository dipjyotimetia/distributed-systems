package com.banking.transactionsservice.integration;

import com.banking.transactionsservice.client.AccountsServiceClient;
import com.banking.transactionsservice.client.FraudServiceClient;
import com.banking.transactionsservice.dto.TransactionQuery;
import com.banking.transactionsservice.generated.TransactionResponse;
import com.banking.transactionsservice.generated.TransactionStatus;
import com.banking.transactionsservice.generated.TransferRequest;
import com.banking.transactionsservice.entity.Transaction;
import com.banking.transactionsservice.repository.TransactionRepository;
import com.banking.transactionsservice.service.PaymentComplianceService;
import com.banking.transactionsservice.service.TransactionService;
import com.banking.transactionsservice.service.MovementCoordinator.Action;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.Page;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import fraud.v1.Fraud.FraudCheckResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionRollbackTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private AccountsServiceClient accountsServiceClient;

    @MockitoBean
    private FraudServiceClient fraudServiceClient;

    @MockitoBean
    private PaymentComplianceService paymentComplianceService;

    @MockitoBean
    private HttpServletRequest httpServletRequest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void cancellationIsOwnedConditionalAndIdempotent() {
        Transaction pending = new Transaction(501L, 11L, 12L, 25.00,
                Transaction.TransactionType.TRANSFER, "Seeded pending");
        Long pendingId = transactionRepository.saveAndFlush(pending).getId();

        assertEquals(TransactionStatus.PENDING,
                transactionService.getTransaction(pendingId, 501L).getStatus());
        var notFound = assertThrows(ResponseStatusException.class,
                () -> transactionService.cancelTransaction(pendingId, 502L));
        assertEquals(404, notFound.getStatusCode().value());
        assertEquals(TransactionStatus.PENDING,
                transactionService.getTransaction(pendingId, 501L).getStatus());

        var cancelled = transactionService.cancelTransaction(pendingId, 501L);
        assertEquals(TransactionStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getProcessedAt());
        assertEquals(cancelled.getProcessedAt(),
                transactionService.cancelTransaction(pendingId, 501L).getProcessedAt());

        Transaction settled = new Transaction(501L, 11L, 12L, 25.00,
                Transaction.TransactionType.TRANSFER, "Seeded completed");
        settled.setStatus(Transaction.TransactionStatus.COMPLETED);
        Long settledId = transactionRepository.saveAndFlush(settled).getId();
        var conflict = assertThrows(ResponseStatusException.class,
                () -> transactionService.cancelTransaction(settledId, 501L));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(TransactionStatus.COMPLETED,
                transactionService.getTransaction(settledId, 501L).getStatus());
    }

    @Test
    void queryFiltersAndSummaryAreScopedToTheOwnerAndAccount() {
        long userId = 501L;
        long accountId = 11L;
        var first = new Transaction(userId, accountId, 12L, 25.00,
                Transaction.TransactionType.TRANSFER, "Synthetic rent");
        var second = new Transaction(userId, 12L, accountId, 25.00,
                Transaction.TransactionType.TRANSFER, "Synthetic rent");
        var unrelated = new Transaction(userId, 13L, 14L, 25.00,
                Transaction.TransactionType.TRANSFER, "Synthetic rent");
        var otherUser = new Transaction(502L, accountId, 12L, 25.00,
                Transaction.TransactionType.TRANSFER, "Synthetic rent");
        transactionRepository.saveAllAndFlush(List.of(first, second, unrelated, otherUser));
        when(accountsServiceClient.validateAccountOwnership(eq(accountId), any(HttpServletRequest.class))).thenReturn(true);
        var request = mock(HttpServletRequest.class);
        var filters = new TransactionQuery(accountId, Transaction.TransactionType.TRANSFER,
                Transaction.TransactionStatus.PENDING, null, null, 25.0, 25.0,
                null, "SYNTHETIC", 0, 1, "amount,asc");

        var page = (Page<?>) transactionService.queryTransactions(userId, filters, true, request);
        assertEquals(2, page.getTotalElements());
        assertEquals(first.getId(), ((TransactionResponse) page.getContent().get(0)).getId());
        assertEquals(2L, transactionService.summarize(userId, accountId, request).getTotalTransactions());
        assertEquals(3L, transactionService.summarize(userId, null, request).getPendingTransactions());
        assertEquals(unrelated.getId(), transactionService.recentTransactions(userId, 1).get(0).getId());
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM transactions_service.event_outbox");
        jdbc.update("DELETE FROM transactions_service.movement_decisions");
        transactionRepository.deleteAll();
        // Fraud service approves all transactions by default in tests
        when(fraudServiceClient.checkTransaction(anyString(), anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(FraudCheckResponse.newBuilder().setApproved(true).setRiskScore(0.0).setReason("OK").build());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void compensatesAfterLedgerFailureAndAllowsRetry() {
        long owner = 901L;
        assertThrows(RuntimeException.class, () -> transactionService.transfer(
                new TransferRequest(11L, 12L, 25.00).description("x".repeat(501)), owner, httpServletRequest));

        var calls = inOrder(accountsServiceClient);
        calls.verify(accountsServiceClient).applyMovement(any(), eq(Action.RESERVE), any());
        calls.verify(accountsServiceClient).applyMovement(any(), eq(Action.COMPENSATE), any());
        assertTrue(transactionRepository.findByUserIdOrderByCreatedAtDesc(owner).isEmpty());

        var result = transactionService.transfer(new TransferRequest(11L, 12L, 25.00).description("Retry"), owner, httpServletRequest);
        assertEquals(TransactionStatus.COMPLETED, result.getStatus());
        calls.verify(accountsServiceClient).applyMovement(any(), eq(Action.RESERVE), any());
        calls.verify(accountsServiceClient).applyMovement(any(), eq(Action.COMMIT), any());
        assertEquals(1, transactionRepository.findByUserIdOrderByCreatedAtDesc(owner).size());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void retryReconcilesCommittedDecisionAfterAmbiguousAccountResponse() {
        long owner = 902L;
        doThrow(new IllegalStateException("response lost")).doNothing()
                .when(accountsServiceClient).applyMovement(any(), eq(Action.COMMIT), any());

        var ambiguous = assertThrows(ResponseStatusException.class, () -> transactionService.transfer(
                new TransferRequest(21L, 22L, 15.00).description("First"), owner, httpServletRequest));
        assertEquals(503, ambiguous.getStatusCode().value());
        assertEquals(1, transactionRepository.findByUserIdOrderByCreatedAtDesc(owner).size());

        // The next distinct movement resolves the first operation with its original ID.
        var first = org.mockito.ArgumentCaptor.forClass(java.util.UUID.class);
        verify(accountsServiceClient).applyMovement(first.capture(), eq(Action.COMMIT), any());
        transactionService.transfer(new TransferRequest(21L, 22L, 20.00).description("Second"), owner, httpServletRequest);
        verify(accountsServiceClient, times(2)).applyMovement(eq(first.getValue()), eq(Action.COMMIT), any());
        assertEquals(2, transactionRepository.findByUserIdOrderByCreatedAtDesc(owner).size());
    }
}
