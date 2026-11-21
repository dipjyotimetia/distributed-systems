package com.banking.accountsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * StatementDocument
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class StatementDocument {

  private Long accountId;

  private String accountNumber;

  private String accountType;

  private BigDecimal balance;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private LocalDateTime exportedAt;

  private Long userId;

  public StatementDocument() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public StatementDocument(Long accountId, String accountNumber, String accountType, BigDecimal balance, LocalDateTime exportedAt, Long userId) {
    this.accountId = accountId;
    this.accountNumber = accountNumber;
    this.accountType = accountType;
    this.balance = balance;
    this.exportedAt = exportedAt;
    this.userId = userId;
  }

  public StatementDocument accountId(Long accountId) {
    this.accountId = accountId;
    return this;
  }

  /**
   * Get accountId
   * @return accountId
   */
  @NotNull 
  @JsonProperty("accountId")
  public Long getAccountId() {
    return accountId;
  }

  public void setAccountId(Long accountId) {
    this.accountId = accountId;
  }

  public StatementDocument accountNumber(String accountNumber) {
    this.accountNumber = accountNumber;
    return this;
  }

  /**
   * Get accountNumber
   * @return accountNumber
   */
  @NotNull 
  @JsonProperty("accountNumber")
  public String getAccountNumber() {
    return accountNumber;
  }

  public void setAccountNumber(String accountNumber) {
    this.accountNumber = accountNumber;
  }

  public StatementDocument accountType(String accountType) {
    this.accountType = accountType;
    return this;
  }

  /**
   * Get accountType
   * @return accountType
   */
  @NotNull 
  @JsonProperty("accountType")
  public String getAccountType() {
    return accountType;
  }

  public void setAccountType(String accountType) {
    this.accountType = accountType;
  }

  public StatementDocument balance(BigDecimal balance) {
    this.balance = balance;
    return this;
  }

  /**
   * Get balance
   * @return balance
   */
  @NotNull @Valid 
  @JsonProperty("balance")
  public BigDecimal getBalance() {
    return balance;
  }

  public void setBalance(BigDecimal balance) {
    this.balance = balance;
  }

  public StatementDocument exportedAt(LocalDateTime exportedAt) {
    this.exportedAt = exportedAt;
    return this;
  }

  /**
   * Get exportedAt
   * @return exportedAt
   */
  @NotNull @Valid 
  @JsonProperty("exportedAt")
  public LocalDateTime getExportedAt() {
    return exportedAt;
  }

  public void setExportedAt(LocalDateTime exportedAt) {
    this.exportedAt = exportedAt;
  }

  public StatementDocument userId(Long userId) {
    this.userId = userId;
    return this;
  }

  /**
   * Get userId
   * @return userId
   */
  @NotNull 
  @JsonProperty("userId")
  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StatementDocument statementDocument = (StatementDocument) o;
    return Objects.equals(this.accountId, statementDocument.accountId) &&
        Objects.equals(this.accountNumber, statementDocument.accountNumber) &&
        Objects.equals(this.accountType, statementDocument.accountType) &&
        Objects.equals(this.balance, statementDocument.balance) &&
        Objects.equals(this.exportedAt, statementDocument.exportedAt) &&
        Objects.equals(this.userId, statementDocument.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountId, accountNumber, accountType, balance, exportedAt, userId);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class StatementDocument {\n");
    sb.append("    accountId: ").append(toIndentedString(accountId)).append("\n");
    sb.append("    accountNumber: ").append(toIndentedString(accountNumber)).append("\n");
    sb.append("    accountType: ").append(toIndentedString(accountType)).append("\n");
    sb.append("    balance: ").append(toIndentedString(balance)).append("\n");
    sb.append("    exportedAt: ").append(toIndentedString(exportedAt)).append("\n");
    sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
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

