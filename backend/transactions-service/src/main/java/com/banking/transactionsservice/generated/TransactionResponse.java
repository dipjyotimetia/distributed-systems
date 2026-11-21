package com.banking.transactionsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.transactionsservice.generated.TransactionStatus;
import com.banking.transactionsservice.generated.TransactionType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TransactionResponse
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class TransactionResponse {

  private Long id;

  private Long userId;

  private @Nullable Long fromAccountId = null;

  private @Nullable Long toAccountId = null;

  private Double amount;

  private TransactionType type;

  private @Nullable String description = null;

  private TransactionStatus status;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private LocalDateTime createdAt;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable LocalDateTime processedAt = null;

  public TransactionResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TransactionResponse(Long id, Long userId, Double amount, TransactionType type, TransactionStatus status, LocalDateTime createdAt) {
    this.id = id;
    this.userId = userId;
    this.amount = amount;
    this.type = type;
    this.status = status;
    this.createdAt = createdAt;
  }

  public TransactionResponse id(Long id) {
    this.id = id;
    return this;
  }

  /**
   * Get id
   * @return id
   */
  @NotNull 
  @JsonProperty("id")
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public TransactionResponse userId(Long userId) {
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

  public TransactionResponse fromAccountId(@Nullable Long fromAccountId) {
    this.fromAccountId = fromAccountId;
    return this;
  }

  /**
   * Get fromAccountId
   * @return fromAccountId
   */
  
  @JsonProperty("fromAccountId")
  public @Nullable Long getFromAccountId() {
    return fromAccountId;
  }

  public void setFromAccountId(@Nullable Long fromAccountId) {
    this.fromAccountId = fromAccountId;
  }

  public TransactionResponse toAccountId(@Nullable Long toAccountId) {
    this.toAccountId = toAccountId;
    return this;
  }

  /**
   * Get toAccountId
   * @return toAccountId
   */
  
  @JsonProperty("toAccountId")
  public @Nullable Long getToAccountId() {
    return toAccountId;
  }

  public void setToAccountId(@Nullable Long toAccountId) {
    this.toAccountId = toAccountId;
  }

  public TransactionResponse amount(Double amount) {
    this.amount = amount;
    return this;
  }

  /**
   * Get amount
   * @return amount
   */
  @NotNull 
  @JsonProperty("amount")
  public Double getAmount() {
    return amount;
  }

  public void setAmount(Double amount) {
    this.amount = amount;
  }

  public TransactionResponse type(TransactionType type) {
    this.type = type;
    return this;
  }

  /**
   * Get type
   * @return type
   */
  @NotNull @Valid 
  @JsonProperty("type")
  public TransactionType getType() {
    return type;
  }

  public void setType(TransactionType type) {
    this.type = type;
  }

  public TransactionResponse description(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * Get description
   * @return description
   */
  
  @JsonProperty("description")
  public @Nullable String getDescription() {
    return description;
  }

  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public TransactionResponse status(TransactionStatus status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * @return status
   */
  @NotNull @Valid 
  @JsonProperty("status")
  public TransactionStatus getStatus() {
    return status;
  }

  public void setStatus(TransactionStatus status) {
    this.status = status;
  }

  public TransactionResponse createdAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
    return this;
  }

  /**
   * Get createdAt
   * @return createdAt
   */
  @NotNull @Valid 
  @JsonProperty("createdAt")
  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public TransactionResponse processedAt(@Nullable LocalDateTime processedAt) {
    this.processedAt = processedAt;
    return this;
  }

  /**
   * Get processedAt
   * @return processedAt
   */
  @Valid 
  @JsonProperty("processedAt")
  public @Nullable LocalDateTime getProcessedAt() {
    return processedAt;
  }

  public void setProcessedAt(@Nullable LocalDateTime processedAt) {
    this.processedAt = processedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TransactionResponse transactionResponse = (TransactionResponse) o;
    return Objects.equals(this.id, transactionResponse.id) &&
        Objects.equals(this.userId, transactionResponse.userId) &&
        Objects.equals(this.fromAccountId, transactionResponse.fromAccountId) &&
        Objects.equals(this.toAccountId, transactionResponse.toAccountId) &&
        Objects.equals(this.amount, transactionResponse.amount) &&
        Objects.equals(this.type, transactionResponse.type) &&
        Objects.equals(this.description, transactionResponse.description) &&
        Objects.equals(this.status, transactionResponse.status) &&
        Objects.equals(this.createdAt, transactionResponse.createdAt) &&
        Objects.equals(this.processedAt, transactionResponse.processedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, userId, fromAccountId, toAccountId, amount, type, description, status, createdAt, processedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TransactionResponse {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
    sb.append("    fromAccountId: ").append(toIndentedString(fromAccountId)).append("\n");
    sb.append("    toAccountId: ").append(toIndentedString(toAccountId)).append("\n");
    sb.append("    amount: ").append(toIndentedString(amount)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
    sb.append("    status: ").append(toIndentedString(status)).append("\n");
    sb.append("    createdAt: ").append(toIndentedString(createdAt)).append("\n");
    sb.append("    processedAt: ").append(toIndentedString(processedAt)).append("\n");
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

