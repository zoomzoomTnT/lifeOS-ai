package com.lifeos.web.dto;

import java.util.Map;

@JsonApi
public record ErrorResponse(String error, String message, Map<String, Object> details) {}
