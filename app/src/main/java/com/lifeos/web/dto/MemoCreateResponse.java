package com.lifeos.web.dto;

import com.lifeos.domain.MemoStatus;

@JsonApi
public record MemoCreateResponse(long id, MemoStatus status) {}
