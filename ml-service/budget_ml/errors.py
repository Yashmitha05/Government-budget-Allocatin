"""Domain errors, mapped to HTTP status codes by the FastAPI layer."""


class BudgetMLError(Exception):
    code = "ML_ERROR"
    status = 400

    def __init__(self, message: str, details: list | None = None):
        super().__init__(message)
        self.message = message
        self.details = details or []


class InvalidInput(BudgetMLError):
    code, status = "INVALID_INPUT", 400


class NotFound(BudgetMLError):
    code, status = "NOT_FOUND", 404


class InsufficientHistory(BudgetMLError):
    code, status = "INSUFFICIENT_HISTORY", 409


class Infeasible(BudgetMLError):
    code, status = "INFEASIBLE_CONSTRAINTS", 422
