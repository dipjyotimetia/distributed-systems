package com.banking.transactionsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TransferRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class TransferRequest {

  private Long fromAccountId;

  private Long toAccountId;

  private Double amount;

  private @Nullable String description;

  public TransferRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TransferRequest(Long fromAccountId, Long toAccountId, Double amount) {
    this.fromAccountId = fromAccountId;
    this.toAccountId = toAccountId;
    this.amount = amount;
  }

  public TransferRequest fromAccountId(Long fromAccountId) {
    this.fromAccountId = fromAccountId;
    return this;
  }

  /**
   * Get fromAccountId
   * @return fromAccountId
   */
  @NotNull 
  @JsonProperty("fromAccountId")
  public Long getFromAccountId() {
    return fromAccountId;
  }

  public void setFromAccountId(Long fromAccountId) {
    this.fromAccountId = fromAccountId;
  }

  public TransferRequest toAccountId(Long toAccountId) {
    this.toAccountId = toAccountId;
    return this;
  }

  /**
   * Get toAccountId
   * @return toAccountId
   */
  @NotNull 
  @JsonProperty("toAccountId")
  public Long getToAccountId() {
    return toAccountId;
  }

  public void setToAccountId(Long toAccountId) {
    this.toAccountId = toAccountId;
  }

  public TransferRequest amount(Double amount) {
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

  public TransferRequest description(@Nullable String description) {
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
    TransferRequest transferRequest = (TransferRequest) o;
    return Objects.equals(this.fromAccountId, transferRequest.fromAccountId) &&
        Objects.equals(this.toAccountId, transferRequest.toAccountId) &&
        Objects.equals(this.amount, transferRequest.amount) &&
        Objects.equals(this.description, transferRequest.description);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fromAccountId, toAccountId, amount, description);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TransferRequest {\n");
    sb.append("    fromAccountId: ").append(toIndentedString(fromAccountId)).append("\n");
    sb.append("    toAccountId: ").append(toIndentedString(toAccountId)).append("\n");
    sb.append("    amount: ").append(toIndentedString(amount)).append("\n");
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

