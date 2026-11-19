package com.banking.accountsservice.dto;

import java.math.BigDecimal;
import java.util.Map;

public record AccountSummary(long totalAccounts, BigDecimal totalBalance, long activeAccounts,
                             Map<String, Long> accountsByType) {}
