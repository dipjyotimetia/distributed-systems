package com.banking.accountsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.accountsservice.generated.StatementDocument;
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
 * StatementExportResponse
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class StatementExportResponse {

  /**
   * Gets or Sets status
   */
  public enum StatusEnum {
    GENERATED("generated"),
    
    EXPORTED("exported");

    private final String value;

    StatusEnum(String value) {
      this.value = value;
    }

    @JsonValue
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @JsonCreator
    public static StatusEnum fromValue(String value) {
      for (StatusEnum b : StatusEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private StatusEnum status;

  private @Nullable String key;

  private @Nullable StatementDocument statement;

  public StatementExportResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public StatementExportResponse(StatusEnum status) {
    this.status = status;
  }

  public StatementExportResponse status(StatusEnum status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * @return status
   */
  @NotNull 
  @JsonProperty("status")
  public StatusEnum getStatus() {
    return status;
  }

  public void setStatus(StatusEnum status) {
    this.status = status;
  }

  public StatementExportResponse key(@Nullable String key) {
    this.key = key;
    return this;
  }

  /**
   * Get key
   * @return key
   */
  
  @JsonProperty("key")
  public @Nullable String getKey() {
    return key;
  }

  public void setKey(@Nullable String key) {
    this.key = key;
  }

  public StatementExportResponse statement(@Nullable StatementDocument statement) {
    this.statement = statement;
    return this;
  }

  /**
   * Get statement
   * @return statement
   */
  @Valid 
  @JsonProperty("statement")
  public @Nullable StatementDocument getStatement() {
    return statement;
  }

  public void setStatement(@Nullable StatementDocument statement) {
    this.statement = statement;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StatementExportResponse statementExportResponse = (StatementExportResponse) o;
    return Objects.equals(this.status, statementExportResponse.status) &&
        Objects.equals(this.key, statementExportResponse.key) &&
        Objects.equals(this.statement, statementExportResponse.statement);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, key, statement);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class StatementExportResponse {\n");
    sb.append("    status: ").append(toIndentedString(status)).append("\n");
    sb.append("    key: ").append(toIndentedString(key)).append("\n");
    sb.append("    statement: ").append(toIndentedString(statement)).append("\n");
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

