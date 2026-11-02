package com.budgetintel.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Error body returned for every failed request. */
public record ApiError(OffsetDateTime timestamp, int status, String error, String message, String path,
                       List<String> details) {
}
