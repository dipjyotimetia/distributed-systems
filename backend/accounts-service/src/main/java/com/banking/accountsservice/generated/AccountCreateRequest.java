package com.banking.accountsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.accountsservice.generated.AccountType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * AccountCreateRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class AccountCreateRequest {

  private AccountType accountType;

  private BigDecimal initialBalance = new BigDecimal("0");

  private String currency = "USD";

  public AccountCreateRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public AccountCreateRequest(AccountType accountType) {
    this.accountType = accountType;
  }

  public AccountCreateRequest accountType(AccountType accountType) {
    this.accountType = accountType;
    return this;
  }

  /**
   * Get accountType
   * @return accountType
   */
  @NotNull @Valid 
  @JsonProperty("accountType")
  public AccountType getAccountType() {
    return accountType;
  }

  public void setAccountType(AccountType accountType) {
    this.accountType = accountType;
  }

  public AccountCreateRequest initialBalance(BigDecimal initialBalance) {
    this.initialBalance = initialBalance;
    return this;
  }

  /**
   * Get initialBalance
   * minimum: 0
   * @return initialBalance
   */
  @Valid @DecimalMin("0") 
  @JsonProperty("initialBalance")
  public BigDecimal getInitialBalance() {
    return initialBalance;
  }

  public void setInitialBalance(BigDecimal initialBalance) {
    this.initialBalance = initialBalance;
  }

  public AccountCreateRequest currency(String currency) {
    this.currency = currency;
    return this;
  }

  /**
   * Get currency
   * @return currency
   */
  @Pattern(regexp = "^[A-Z]{3}$") 
  @JsonProperty("currency")
  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AccountCreateRequest accountCreateRequest = (AccountCreateRequest) o;
    return Objects.equals(this.accountType, accountCreateRequest.accountType) &&
        Objects.equals(this.initialBalance, accountCreateRequest.initialBalance) &&
        Objects.equals(this.currency, accountCreateRequest.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountType, initialBalance, currency);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AccountCreateRequest {\n");
    sb.append("    accountType: ").append(toIndentedString(accountType)).append("\n");
    sb.append("    initialBalance: ").append(toIndentedString(initialBalance)).append("\n");
    sb.append("    currency: ").append(toIndentedString(currency)).append("\n");
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

