package com.acme.ledger;

/** One posting: an account and a signed amount in cents. */
public record Entry(String account, long cents) {}
