package com.banking.accountsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.accountsservice.generated.AccountStatusChange;
import com.banking.accountsservice.generated.AccountType;
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
 * AccountUpdateRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class AccountUpdateRequest {

  private @Nullable AccountType accountType;

  private @Nullable AccountStatusChange status;

  public AccountUpdateRequest accountType(@Nullable AccountType accountType) {
    this.accountType = accountType;
    return this;
  }

  /**
   * Get accountType
   * @return accountType
   */
  @Valid 
  @JsonProperty("accountType")
  public @Nullable AccountType getAccountType() {
    return accountType;
  }

  public void setAccountType(@Nullable AccountType accountType) {
    this.accountType = accountType;
  }

  public AccountUpdateRequest status(@Nullable AccountStatusChange status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * @return status
   */
  @Valid 
  @JsonProperty("status")
  public @Nullable AccountStatusChange getStatus() {
    return status;
  }

  public void setStatus(@Nullable AccountStatusChange status) {
    this.status = status;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AccountUpdateRequest accountUpdateRequest = (AccountUpdateRequest) o;
    return Objects.equals(this.accountType, accountUpdateRequest.accountType) &&
        Objects.equals(this.status, accountUpdateRequest.status);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountType, status);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AccountUpdateRequest {\n");
    sb.append("    accountType: ").append(toIndentedString(accountType)).append("\n");
    sb.append("    status: ").append(toIndentedString(status)).append("\n");
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

