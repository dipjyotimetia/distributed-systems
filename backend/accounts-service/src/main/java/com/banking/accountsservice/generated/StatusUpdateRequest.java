package com.banking.accountsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.accountsservice.generated.AccountStatusChange;
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
 * StatusUpdateRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class StatusUpdateRequest {

  private AccountStatusChange status;

  public StatusUpdateRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public StatusUpdateRequest(AccountStatusChange status) {
    this.status = status;
  }

  public StatusUpdateRequest status(AccountStatusChange status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * @return status
   */
  @NotNull @Valid 
  @JsonProperty("status")
  public AccountStatusChange getStatus() {
    return status;
  }

  public void setStatus(AccountStatusChange status) {
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
    StatusUpdateRequest statusUpdateRequest = (StatusUpdateRequest) o;
    return Objects.equals(this.status, statusUpdateRequest.status);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class StatusUpdateRequest {\n");
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

