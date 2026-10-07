package com.cachecraft.controller;

import com.cachecraft.service.UnsupportedStrategyException;
import com.cachecraft.service.InvalidDistributedLockDelayException;
import com.cachecraft.service.LockUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts expected request and strategy failures into a stable HTTP error. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnsupportedStrategyException.class)
    public ResponseEntity<ApiError> handleUnsupportedStrategy(UnsupportedStrategyException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("UNSUPPORTED_STRATEGY", exception.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(ConstraintViolationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(InvalidDistributedLockDelayException.class)
    public ResponseEntity<ApiError> handleUnsupportedLockDelay(InvalidDistributedLockDelayException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_DISTRIBUTED_LOCK_DELAY", exception.getMessage()));
    }

    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    public ResponseEntity<ApiError> handleDatabaseConnectionTimeout(CannotGetJdbcConnectionException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("DATABASE_CONNECTION_TIMEOUT", "Database connection pool is unavailable"));
    }

    @ExceptionHandler(LockUnavailableException.class)
    public ResponseEntity<ApiError> handleLockUnavailable(LockUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("CACHE_LOCK_UNAVAILABLE", exception.getMessage()));
    }
}
