package com.banking.transactionsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.transactionsservice.generated.TransactionType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * CreateTransactionRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class CreateTransactionRequest {

  private Long accountId;

  private @Nullable Long toAccountId = null;

  private TransactionType type;

  private Double amount;

  private String currency;

  private @Nullable String description;

  public CreateTransactionRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public CreateTransactionRequest(Long accountId, TransactionType type, Double amount, String currency) {
    this.accountId = accountId;
    this.type = type;
    this.amount = amount;
    this.currency = currency;
  }

  public CreateTransactionRequest accountId(Long accountId) {
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

  public CreateTransactionRequest toAccountId(@Nullable Long toAccountId) {
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

  public CreateTransactionRequest type(TransactionType type) {
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

  public CreateTransactionRequest amount(Double amount) {
    this.amount = amount;
    return this;
  }

  /**
   * Get amount
   * minimum: 0
   * @return amount
   */
  @NotNull @DecimalMin(value = "0", inclusive = false) 
  @JsonProperty("amount")
  public Double getAmount() {
    return amount;
  }

  public void setAmount(Double amount) {
    this.amount = amount;
  }

  public CreateTransactionRequest currency(String currency) {
    this.currency = currency;
    return this;
  }

  /**
   * Get currency
   * @return currency
   */
  @NotNull @Pattern(regexp = "^[A-Z]{3}$") @Size(min = 3, max = 3) 
  @JsonProperty("currency")
  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public CreateTransactionRequest description(@Nullable String description) {
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CreateTransactionRequest createTransactionRequest = (CreateTransactionRequest) o;
    return Objects.equals(this.accountId, createTransactionRequest.accountId) &&
        Objects.equals(this.toAccountId, createTransactionRequest.toAccountId) &&
        Objects.equals(this.type, createTransactionRequest.type) &&
        Objects.equals(this.amount, createTransactionRequest.amount) &&
        Objects.equals(this.currency, createTransactionRequest.currency) &&
        Objects.equals(this.description, createTransactionRequest.description);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountId, toAccountId, type, amount, currency, description);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CreateTransactionRequest {\n");
    sb.append("    accountId: ").append(toIndentedString(accountId)).append("\n");
    sb.append("    toAccountId: ").append(toIndentedString(toAccountId)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    amount: ").append(toIndentedString(amount)).append("\n");
    sb.append("    currency: ").append(toIndentedString(currency)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
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

