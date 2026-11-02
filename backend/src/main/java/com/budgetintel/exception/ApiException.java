package com.budgetintel.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An error with a known HTTP status and machine-readable code, shown to the client as-is. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public ApiException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of());
    }

    public static ApiException badRequest(String message, String... details) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message, List.of(details));
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, List.of());
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public List<String> getDetails() { return details; }
}
