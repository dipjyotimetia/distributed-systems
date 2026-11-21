package com.banking.transactionsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TransactionSummary
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class TransactionSummary {

  private Long totalTransactions;

  private Long totalDeposits;

  private Long totalWithdrawals;

  private Long totalTransfers;

  private Long pendingTransactions;

  private Long completedTransactions;

  private Long failedTransactions;

  @Valid
  private Map<String, Long> transactionsByType = new HashMap<>();

  @Valid
  private Map<String, Long> transactionsByStatus = new HashMap<>();

  public TransactionSummary() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TransactionSummary(Long totalTransactions, Long totalDeposits, Long totalWithdrawals, Long totalTransfers, Long pendingTransactions, Long completedTransactions, Long failedTransactions, Map<String, Long> transactionsByType, Map<String, Long> transactionsByStatus) {
    this.totalTransactions = totalTransactions;
    this.totalDeposits = totalDeposits;
    this.totalWithdrawals = totalWithdrawals;
    this.totalTransfers = totalTransfers;
    this.pendingTransactions = pendingTransactions;
    this.completedTransactions = completedTransactions;
    this.failedTransactions = failedTransactions;
    this.transactionsByType = transactionsByType;
    this.transactionsByStatus = transactionsByStatus;
  }

  public TransactionSummary totalTransactions(Long totalTransactions) {
    this.totalTransactions = totalTransactions;
    return this;
  }

  /**
   * Get totalTransactions
   * @return totalTransactions
   */
  @NotNull 
  @JsonProperty("totalTransactions")
  public Long getTotalTransactions() {
    return totalTransactions;
  }

  public void setTotalTransactions(Long totalTransactions) {
    this.totalTransactions = totalTransactions;
  }

  public TransactionSummary totalDeposits(Long totalDeposits) {
    this.totalDeposits = totalDeposits;
    return this;
  }

  /**
   * Get totalDeposits
   * @return totalDeposits
   */
  @NotNull 
  @JsonProperty("totalDeposits")
  public Long getTotalDeposits() {
    return totalDeposits;
  }

  public void setTotalDeposits(Long totalDeposits) {
    this.totalDeposits = totalDeposits;
  }

  public TransactionSummary totalWithdrawals(Long totalWithdrawals) {
    this.totalWithdrawals = totalWithdrawals;
    return this;
  }

  /**
   * Get totalWithdrawals
   * @return totalWithdrawals
   */
  @NotNull 
  @JsonProperty("totalWithdrawals")
  public Long getTotalWithdrawals() {
    return totalWithdrawals;
  }

  public void setTotalWithdrawals(Long totalWithdrawals) {
    this.totalWithdrawals = totalWithdrawals;
  }

  public TransactionSummary totalTransfers(Long totalTransfers) {
    this.totalTransfers = totalTransfers;
    return this;
  }

  /**
   * Get totalTransfers
   * @return totalTransfers
   */
  @NotNull 
  @JsonProperty("totalTransfers")
  public Long getTotalTransfers() {
    return totalTransfers;
  }

  public void setTotalTransfers(Long totalTransfers) {
    this.totalTransfers = totalTransfers;
  }

  public TransactionSummary pendingTransactions(Long pendingTransactions) {
    this.pendingTransactions = pendingTransactions;
    return this;
  }

  /**
   * Get pendingTransactions
   * @return pendingTransactions
   */
  @NotNull 
  @JsonProperty("pendingTransactions")
  public Long getPendingTransactions() {
    return pendingTransactions;
  }

  public void setPendingTransactions(Long pendingTransactions) {
    this.pendingTransactions = pendingTransactions;
  }

  public TransactionSummary completedTransactions(Long completedTransactions) {
    this.completedTransactions = completedTransactions;
    return this;
  }

  /**
   * Get completedTransactions
   * @return completedTransactions
   */
  @NotNull 
  @JsonProperty("completedTransactions")
  public Long getCompletedTransactions() {
    return completedTransactions;
  }

  public void setCompletedTransactions(Long completedTransactions) {
    this.completedTransactions = completedTransactions;
  }

  public TransactionSummary failedTransactions(Long failedTransactions) {
    this.failedTransactions = failedTransactions;
    return this;
  }

  /**
   * Get failedTransactions
   * @return failedTransactions
   */
  @NotNull 
  @JsonProperty("failedTransactions")
  public Long getFailedTransactions() {
    return failedTransactions;
  }

  public void setFailedTransactions(Long failedTransactions) {
    this.failedTransactions = failedTransactions;
  }

  public TransactionSummary transactionsByType(Map<String, Long> transactionsByType) {
    this.transactionsByType = transactionsByType;
    return this;
  }

  public TransactionSummary putTransactionsByTypeItem(String key, Long transactionsByTypeItem) {
    if (this.transactionsByType == null) {
      this.transactionsByType = new HashMap<>();
    }
    this.transactionsByType.put(key, transactionsByTypeItem);
    return this;
  }

  /**
   * Get transactionsByType
   * @return transactionsByType
   */
  @NotNull 
  @JsonProperty("transactionsByType")
  public Map<String, Long> getTransactionsByType() {
    return transactionsByType;
  }

  public void setTransactionsByType(Map<String, Long> transactionsByType) {
    this.transactionsByType = transactionsByType;
  }

  public TransactionSummary transactionsByStatus(Map<String, Long> transactionsByStatus) {
    this.transactionsByStatus = transactionsByStatus;
    return this;
  }

  public TransactionSummary putTransactionsByStatusItem(String key, Long transactionsByStatusItem) {
    if (this.transactionsByStatus == null) {
      this.transactionsByStatus = new HashMap<>();
    }
    this.transactionsByStatus.put(key, transactionsByStatusItem);
    return this;
  }

  /**
   * Get transactionsByStatus
   * @return transactionsByStatus
   */
  @NotNull 
  @JsonProperty("transactionsByStatus")
  public Map<String, Long> getTransactionsByStatus() {
    return transactionsByStatus;
  }

  public void setTransactionsByStatus(Map<String, Long> transactionsByStatus) {
    this.transactionsByStatus = transactionsByStatus;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TransactionSummary transactionSummary = (TransactionSummary) o;
    return Objects.equals(this.totalTransactions, transactionSummary.totalTransactions) &&
        Objects.equals(this.totalDeposits, transactionSummary.totalDeposits) &&
        Objects.equals(this.totalWithdrawals, transactionSummary.totalWithdrawals) &&
        Objects.equals(this.totalTransfers, transactionSummary.totalTransfers) &&
        Objects.equals(this.pendingTransactions, transactionSummary.pendingTransactions) &&
        Objects.equals(this.completedTransactions, transactionSummary.completedTransactions) &&
        Objects.equals(this.failedTransactions, transactionSummary.failedTransactions) &&
        Objects.equals(this.transactionsByType, transactionSummary.transactionsByType) &&
        Objects.equals(this.transactionsByStatus, transactionSummary.transactionsByStatus);
  }

  @Override
  public int hashCode() {
    return Objects.hash(totalTransactions, totalDeposits, totalWithdrawals, totalTransfers, pendingTransactions, completedTransactions, failedTransactions, transactionsByType, transactionsByStatus);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TransactionSummary {\n");
    sb.append("    totalTransactions: ").append(toIndentedString(totalTransactions)).append("\n");
    sb.append("    totalDeposits: ").append(toIndentedString(totalDeposits)).append("\n");
    sb.append("    totalWithdrawals: ").append(toIndentedString(totalWithdrawals)).append("\n");
    sb.append("    totalTransfers: ").append(toIndentedString(totalTransfers)).append("\n");
    sb.append("    pendingTransactions: ").append(toIndentedString(pendingTransactions)).append("\n");
    sb.append("    completedTransactions: ").append(toIndentedString(completedTransactions)).append("\n");
    sb.append("    failedTransactions: ").append(toIndentedString(failedTransactions)).append("\n");
    sb.append("    transactionsByType: ").append(toIndentedString(transactionsByType)).append("\n");
    sb.append("    transactionsByStatus: ").append(toIndentedString(transactionsByStatus)).append("\n");
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

