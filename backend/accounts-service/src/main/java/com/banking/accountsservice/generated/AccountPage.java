package com.banking.accountsservice.generated;

import java.net.URI;
import java.util.Objects;
import com.banking.accountsservice.generated.AccountResponse;
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
 * AccountPage
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.14.0")
public class AccountPage implements ListAccounts200Response {

  @Valid
  private List<@Valid AccountResponse> content = new ArrayList<>();

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

  public AccountPage() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public AccountPage(List<@Valid AccountResponse> content, Object pageable, Object sort, Boolean last, Boolean first, Boolean empty, Integer totalPages, Long totalElements, Integer size, Integer number, Integer numberOfElements) {
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

  public AccountPage content(List<@Valid AccountResponse> content) {
    this.content = content;
    return this;
  }

  public AccountPage addContentItem(AccountResponse contentItem) {
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
  public List<@Valid AccountResponse> getContent() {
    return content;
  }

  public void setContent(List<@Valid AccountResponse> content) {
    this.content = content;
  }

  public AccountPage pageable(Object pageable) {
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

  public AccountPage sort(Object sort) {
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

  public AccountPage last(Boolean last) {
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

  public AccountPage first(Boolean first) {
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

  public AccountPage empty(Boolean empty) {
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

  public AccountPage totalPages(Integer totalPages) {
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

  public AccountPage totalElements(Long totalElements) {
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

  public AccountPage size(Integer size) {
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

  public AccountPage number(Integer number) {
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

  public AccountPage numberOfElements(Integer numberOfElements) {
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
    AccountPage accountPage = (AccountPage) o;
    return Objects.equals(this.content, accountPage.content) &&
        Objects.equals(this.pageable, accountPage.pageable) &&
        Objects.equals(this.sort, accountPage.sort) &&
        Objects.equals(this.last, accountPage.last) &&
        Objects.equals(this.first, accountPage.first) &&
        Objects.equals(this.empty, accountPage.empty) &&
        Objects.equals(this.totalPages, accountPage.totalPages) &&
        Objects.equals(this.totalElements, accountPage.totalElements) &&
        Objects.equals(this.size, accountPage.size) &&
        Objects.equals(this.number, accountPage.number) &&
        Objects.equals(this.numberOfElements, accountPage.numberOfElements);
  }

  @Override
  public int hashCode() {
    return Objects.hash(content, pageable, sort, last, first, empty, totalPages, totalElements, size, number, numberOfElements);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AccountPage {\n");
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

