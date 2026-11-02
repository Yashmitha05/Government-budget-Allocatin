package com.budgetintel.client;

/** The ML service could not be reached (connection refused, timeout, unreadable response). */
public class MlServiceUnavailableException extends RuntimeException {

    public MlServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
