package com.cachecraft.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Signals that a requested item identifier is absent from the seed table. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ItemNotFoundException extends RuntimeException {

    public ItemNotFoundException(long id) {
        super("Item %d was not found".formatted(id));
    }
}
