package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.MemoStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoWriteResponse(long id, MemoStatus status, Boolean updated, Boolean fired) {}
