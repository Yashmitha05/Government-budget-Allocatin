package com.budgetintel.client;

import java.util.List;

/** The ML service answered with an error response ({error, message, details}). */
public class MlServiceException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<String> details;

    public MlServiceException(int status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public List<String> getDetails() { return details; }
}
