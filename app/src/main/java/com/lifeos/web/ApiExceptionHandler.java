package com.lifeos.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        err -> err.getField(),
                        err -> err.getDefaultMessage() == null ? "invalid" : err.getDefaultMessage(),
                        (a, b) -> a,
                        LinkedHashMap::new)));
        return ResponseEntity.badRequest()
                .body(Map.of("error", "validation", "message", "request invalid", "details", details));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> badRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "bad_request", "message", String.valueOf(ex.getMessage()), "details", Map.of()));
    }

    /** e.g. an id that does not fit a long: 400, not a 500 from the error page. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "bad_request", "message", "invalid value for '" + ex.getName() + "'", "details", Map.of()));
    }

    /**
     * Everything else: the API's JSON shape instead of Boot's error page, and no exception message to
     * the caller (it can carry SQL or internals); the log has it, keyed by X-Request-Id.
     * Security exceptions are rethrown so Spring Security still answers 401/403.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> other(Exception ex, jakarta.servlet.http.HttpServletRequest req) throws Exception {
        if (ex instanceof org.springframework.security.access.AccessDeniedException
                || ex instanceof org.springframework.security.core.AuthenticationException) {
            throw ex;
        }
        if (ex instanceof org.springframework.web.ErrorResponse er) { // 404 no route, 405, 415, ... (Spring MVC's own)
            int status = er.getStatusCode().value();
            String error = status == 404 ? "not_found" : status == 405 ? "method_not_allowed" : "bad_request";
            return ResponseEntity.status(status)
                    .body(Map.of("error", error, "message", String.valueOf(er.getBody().getDetail()), "details", Map.of()));
        }
        Object rid = req.getAttribute(com.lifeos.security.AuditContext.REQUEST_ID_ATTR);
        log.error("unhandled {} {} (request {})", req.getMethod(), req.getRequestURI(), rid, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal_error", "message", "unexpected server error (request "
                        + (rid == null ? "?" : rid) + ")", "details", Map.of()));
    }

    @ExceptionHandler(com.lifeos.security.ApiKeyService.Conflict.class)
    public ResponseEntity<?> conflict(com.lifeos.security.ApiKeyService.Conflict ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "conflict", "message", String.valueOf(ex.getMessage()), "details", Map.of()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message", String.valueOf(ex.getMessage()), "details", Map.of()));
    }
}
