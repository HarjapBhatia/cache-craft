package com.cachecraft.controller;

/** Stable error shape returned by the HTTP exception mapper. */
public record ApiError(String code, String message) {
}
