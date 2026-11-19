package com.banking.accountsservice.dto;

import com.banking.accountsservice.entity.Account.Status;

public record AccountQuery(String type, Status status, String q, Integer page, Integer size, String sort) {
    public boolean isUnfiltered() {
        return type == null && status == null && q == null && page == null && size == null && sort == null;
    }
}
