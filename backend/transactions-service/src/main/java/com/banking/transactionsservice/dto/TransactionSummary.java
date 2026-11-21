package com.banking.transactionsservice.dto;

import com.banking.transactionsservice.entity.Transaction.TransactionStatus;
import com.banking.transactionsservice.entity.Transaction.TransactionType;

import java.util.Map;

public record TransactionSummary(long totalTransactions, long totalDeposits, long totalWithdrawals,
                                 long totalTransfers, long pendingTransactions, long completedTransactions,
                                 long failedTransactions, Map<TransactionType, Long> transactionsByType,
                                 Map<TransactionStatus, Long> transactionsByStatus) {}
