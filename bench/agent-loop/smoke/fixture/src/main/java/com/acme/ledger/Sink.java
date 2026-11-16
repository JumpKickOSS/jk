package com.acme.ledger;

/** Where notes go; implementations are found with ServiceLoader. */
public interface Sink {
    void post(String line);
}
