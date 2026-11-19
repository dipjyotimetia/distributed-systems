package com.banking.transactionsservice.dto;

import com.banking.transactionsservice.entity.Transaction.TransactionStatus;
import com.banking.transactionsservice.entity.Transaction.TransactionType;

public record TransactionQuery(Long accountId, TransactionType type, TransactionStatus status,
                               String startDate, String endDate, Double minAmount, Double maxAmount,
                               String description, String q, Integer page, Integer size, String sort) {
    public boolean isUnfiltered() {
        return accountId == null && type == null && status == null && startDate == null && endDate == null
                && minAmount == null && maxAmount == null && description == null && q == null
                && page == null && size == null && sort == null;
    }
}
