package com.cachecraft.controller;

import com.cachecraft.service.UnsupportedStrategyException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
