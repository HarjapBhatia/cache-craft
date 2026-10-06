package com.cachecraft.model;

/** Immutable representation of one seeded row in the {@code items} table. */
public record Item(long id, String name, int price, String description) {
}
