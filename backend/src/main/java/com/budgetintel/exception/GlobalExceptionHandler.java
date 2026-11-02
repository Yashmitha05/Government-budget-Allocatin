package com.budgetintel.exception;

import com.budgetintel.client.MlServiceException;
import com.budgetintel.client.MlServiceUnavailableException;
import com.budgetintel.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to {@link ApiError}. Stack traces are logged, never returned. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<ApiError> body(HttpStatus status, String code, String message, List<String> details,
                                          HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(new ApiError(OffsetDateTime.now(), status.value(), code, message, req.getRequestURI(), details));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), e.getDetails(), req);
    }

    /** Client-side problems reported by the ML service (bad input, unknown state, infeasible constraints) pass through. */
    @ExceptionHandler(MlServiceException.class)
    ResponseEntity<ApiError> ml(MlServiceException e, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(e.getStatus());
        if (status != null && status.is4xxClientError()) {
            HttpStatus out = status == HttpStatus.UNPROCESSABLE_ENTITY && "VALIDATION_ERROR".equals(e.getCode())
                    ? HttpStatus.BAD_REQUEST : status;
            return body(out, e.getCode(), e.getMessage(), e.getDetails(), req);
        }
        log.error("ML service error {} {}: {}", e.getStatus(), e.getCode(), e.getMessage());
        return body(HttpStatus.BAD_GATEWAY, "ML_SERVICE_ERROR", "The ML service failed to process the request",
                List.of(), req);
    }

    @ExceptionHandler(MlServiceUnavailableException.class)
    ResponseEntity<ApiError> mlDown(MlServiceUnavailableException e, HttpServletRequest req) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "ML_SERVICE_UNAVAILABLE",
                "The analytics service is currently unavailable. Please try again later.", List.of(), req);
    }

    /** Field paths in error details use the JSON (snake_case) names clients send, e.g. "state_code". */
    static String jsonName(String javaPath) {
        return javaPath.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> jsonName(f.getField()) + ": " + f.getDefaultMessage()).sorted().toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", details, req);
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ApiError> constraint(Exception e, HttpServletRequest req) {
        List<String> details = e instanceof ConstraintViolationException cve
                ? cve.getConstraintViolations().stream()
                        .map(v -> {   // path is "method.param"; report only the parameter
                            String p = v.getPropertyPath().toString();
                            return jsonName(p.substring(p.lastIndexOf('.') + 1)) + ": " + v.getMessage();
                        }).sorted().toList()
                : ((HandlerMethodValidationException) e).getAllErrors().stream()
                        .map(err -> String.valueOf(err.getDefaultMessage())).toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", details, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Malformed or invalid JSON request body", List.of(), req);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> param(Exception e, HttpServletRequest req) {
        String detail = e instanceof MissingServletRequestParameterException m
                ? m.getParameterName() + ": required parameter is missing"
                : ((MethodArgumentTypeMismatchException) e).getName() + ": invalid value";
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request parameters", List.of(detail), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e, HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint", List.of(), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported", List.of(), req);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException e, HttpServletRequest req) {
        log.error("Database error on {}", req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR", "A database error occurred", List.of(), req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {}", req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", List.of(), req);
    }
}
