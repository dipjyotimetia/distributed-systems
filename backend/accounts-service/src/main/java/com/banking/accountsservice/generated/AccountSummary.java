package com.banking.accountsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * AccountSummary
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class AccountSummary {

  private Long totalAccounts;

  private Long activeAccounts;

  private BigDecimal totalBalance;

  @Valid
  private Map<String, Long> accountsByType = new HashMap<>();

  public AccountSummary() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public AccountSummary(Long totalAccounts, Long activeAccounts, BigDecimal totalBalance, Map<String, Long> accountsByType) {
    this.totalAccounts = totalAccounts;
    this.activeAccounts = activeAccounts;
    this.totalBalance = totalBalance;
    this.accountsByType = accountsByType;
  }

  public AccountSummary totalAccounts(Long totalAccounts) {
    this.totalAccounts = totalAccounts;
    return this;
  }

  /**
   * Get totalAccounts
   * @return totalAccounts
   */
  @NotNull 
  @JsonProperty("totalAccounts")
  public Long getTotalAccounts() {
    return totalAccounts;
  }

  public void setTotalAccounts(Long totalAccounts) {
    this.totalAccounts = totalAccounts;
  }

  public AccountSummary activeAccounts(Long activeAccounts) {
    this.activeAccounts = activeAccounts;
    return this;
  }

  /**
   * Get activeAccounts
   * @return activeAccounts
   */
  @NotNull 
  @JsonProperty("activeAccounts")
  public Long getActiveAccounts() {
    return activeAccounts;
  }

  public void setActiveAccounts(Long activeAccounts) {
    this.activeAccounts = activeAccounts;
  }

  public AccountSummary totalBalance(BigDecimal totalBalance) {
    this.totalBalance = totalBalance;
    return this;
  }

  /**
   * Get totalBalance
   * @return totalBalance
   */
  @NotNull @Valid 
  @JsonProperty("totalBalance")
  public BigDecimal getTotalBalance() {
    return totalBalance;
  }

  public void setTotalBalance(BigDecimal totalBalance) {
    this.totalBalance = totalBalance;
  }

  public AccountSummary accountsByType(Map<String, Long> accountsByType) {
    this.accountsByType = accountsByType;
    return this;
  }

  public AccountSummary putAccountsByTypeItem(String key, Long accountsByTypeItem) {
    if (this.accountsByType == null) {
      this.accountsByType = new HashMap<>();
    }
    this.accountsByType.put(key, accountsByTypeItem);
    return this;
  }

  /**
   * Get accountsByType
   * @return accountsByType
   */
  @NotNull 
  @JsonProperty("accountsByType")
  public Map<String, Long> getAccountsByType() {
    return accountsByType;
  }

  public void setAccountsByType(Map<String, Long> accountsByType) {
    this.accountsByType = accountsByType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AccountSummary accountSummary = (AccountSummary) o;
    return Objects.equals(this.totalAccounts, accountSummary.totalAccounts) &&
        Objects.equals(this.activeAccounts, accountSummary.activeAccounts) &&
        Objects.equals(this.totalBalance, accountSummary.totalBalance) &&
        Objects.equals(this.accountsByType, accountSummary.accountsByType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(totalAccounts, activeAccounts, totalBalance, accountsByType);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AccountSummary {\n");
    sb.append("    totalAccounts: ").append(toIndentedString(totalAccounts)).append("\n");
    sb.append("    activeAccounts: ").append(toIndentedString(activeAccounts)).append("\n");
    sb.append("    totalBalance: ").append(toIndentedString(totalBalance)).append("\n");
    sb.append("    accountsByType: ").append(toIndentedString(accountsByType)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

