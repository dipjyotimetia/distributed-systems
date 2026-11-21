package com.banking.transactionsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.transactionsservice.generated.TransactionResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.lang.Nullable;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TransactionPage
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class TransactionPage implements ListTransactions200Response {

  @Valid
  private List<@Valid TransactionResponse> content = new ArrayList<>();

  private Object pageable;

  private Object sort;

  private Boolean last;

  private Boolean first;

  private Boolean empty;

  private Integer totalPages;

  private Long totalElements;

  private Integer size;

  private Integer number;

  private Integer numberOfElements;

  public TransactionPage() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TransactionPage(List<@Valid TransactionResponse> content, Object pageable, Object sort, Boolean last, Boolean first, Boolean empty, Integer totalPages, Long totalElements, Integer size, Integer number, Integer numberOfElements) {
    this.content = content;
    this.pageable = pageable;
    this.sort = sort;
    this.last = last;
    this.first = first;
    this.empty = empty;
    this.totalPages = totalPages;
    this.totalElements = totalElements;
    this.size = size;
    this.number = number;
    this.numberOfElements = numberOfElements;
  }

  public TransactionPage content(List<@Valid TransactionResponse> content) {
    this.content = content;
    return this;
  }

  public TransactionPage addContentItem(TransactionResponse contentItem) {
    if (this.content == null) {
      this.content = new ArrayList<>();
    }
    this.content.add(contentItem);
    return this;
  }

  /**
   * Get content
   * @return content
   */
  @NotNull @Valid 
  @JsonProperty("content")
  public List<@Valid TransactionResponse> getContent() {
    return content;
  }

  public void setContent(List<@Valid TransactionResponse> content) {
    this.content = content;
  }

  public TransactionPage pageable(Object pageable) {
    this.pageable = pageable;
    return this;
  }

  /**
   * Get pageable
   * @return pageable
   */
  @NotNull 
  @JsonProperty("pageable")
  public Object getPageable() {
    return pageable;
  }

  public void setPageable(Object pageable) {
    this.pageable = pageable;
  }

  public TransactionPage sort(Object sort) {
    this.sort = sort;
    return this;
  }

  /**
   * Get sort
   * @return sort
   */
  @NotNull 
  @JsonProperty("sort")
  public Object getSort() {
    return sort;
  }

  public void setSort(Object sort) {
    this.sort = sort;
  }

  public TransactionPage last(Boolean last) {
    this.last = last;
    return this;
  }

  /**
   * Get last
   * @return last
   */
  @NotNull 
  @JsonProperty("last")
  public Boolean getLast() {
    return last;
  }

  public void setLast(Boolean last) {
    this.last = last;
  }

  public TransactionPage first(Boolean first) {
    this.first = first;
    return this;
  }

  /**
   * Get first
   * @return first
   */
  @NotNull 
  @JsonProperty("first")
  public Boolean getFirst() {
    return first;
  }

  public void setFirst(Boolean first) {
    this.first = first;
  }

  public TransactionPage empty(Boolean empty) {
    this.empty = empty;
    return this;
  }

  /**
   * Get empty
   * @return empty
   */
  @NotNull 
  @JsonProperty("empty")
  public Boolean getEmpty() {
    return empty;
  }

  public void setEmpty(Boolean empty) {
    this.empty = empty;
  }

  public TransactionPage totalPages(Integer totalPages) {
    this.totalPages = totalPages;
    return this;
  }

  /**
   * Get totalPages
   * @return totalPages
   */
  @NotNull 
  @JsonProperty("totalPages")
  public Integer getTotalPages() {
    return totalPages;
  }

  public void setTotalPages(Integer totalPages) {
    this.totalPages = totalPages;
  }

  public TransactionPage totalElements(Long totalElements) {
    this.totalElements = totalElements;
    return this;
  }

  /**
   * Get totalElements
   * @return totalElements
   */
  @NotNull 
  @JsonProperty("totalElements")
  public Long getTotalElements() {
    return totalElements;
  }

  public void setTotalElements(Long totalElements) {
    this.totalElements = totalElements;
  }

  public TransactionPage size(Integer size) {
    this.size = size;
    return this;
  }

  /**
   * Get size
   * @return size
   */
  @NotNull 
  @JsonProperty("size")
  public Integer getSize() {
    return size;
  }

  public void setSize(Integer size) {
    this.size = size;
  }

  public TransactionPage number(Integer number) {
    this.number = number;
    return this;
  }

  /**
   * Get number
   * @return number
   */
  @NotNull 
  @JsonProperty("number")
  public Integer getNumber() {
    return number;
  }

  public void setNumber(Integer number) {
    this.number = number;
  }

  public TransactionPage numberOfElements(Integer numberOfElements) {
    this.numberOfElements = numberOfElements;
    return this;
  }

  /**
   * Get numberOfElements
   * @return numberOfElements
   */
  @NotNull 
  @JsonProperty("numberOfElements")
  public Integer getNumberOfElements() {
    return numberOfElements;
  }

  public void setNumberOfElements(Integer numberOfElements) {
    this.numberOfElements = numberOfElements;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TransactionPage transactionPage = (TransactionPage) o;
    return Objects.equals(this.content, transactionPage.content) &&
        Objects.equals(this.pageable, transactionPage.pageable) &&
        Objects.equals(this.sort, transactionPage.sort) &&
        Objects.equals(this.last, transactionPage.last) &&
        Objects.equals(this.first, transactionPage.first) &&
        Objects.equals(this.empty, transactionPage.empty) &&
        Objects.equals(this.totalPages, transactionPage.totalPages) &&
        Objects.equals(this.totalElements, transactionPage.totalElements) &&
        Objects.equals(this.size, transactionPage.size) &&
        Objects.equals(this.number, transactionPage.number) &&
        Objects.equals(this.numberOfElements, transactionPage.numberOfElements);
  }

  @Override
  public int hashCode() {
    return Objects.hash(content, pageable, sort, last, first, empty, totalPages, totalElements, size, number, numberOfElements);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TransactionPage {\n");
    sb.append("    content: ").append(toIndentedString(content)).append("\n");
    sb.append("    pageable: ").append(toIndentedString(pageable)).append("\n");
    sb.append("    sort: ").append(toIndentedString(sort)).append("\n");
    sb.append("    last: ").append(toIndentedString(last)).append("\n");
    sb.append("    first: ").append(toIndentedString(first)).append("\n");
    sb.append("    empty: ").append(toIndentedString(empty)).append("\n");
    sb.append("    totalPages: ").append(toIndentedString(totalPages)).append("\n");
    sb.append("    totalElements: ").append(toIndentedString(totalElements)).append("\n");
    sb.append("    size: ").append(toIndentedString(size)).append("\n");
    sb.append("    number: ").append(toIndentedString(number)).append("\n");
    sb.append("    numberOfElements: ").append(toIndentedString(numberOfElements)).append("\n");
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

