package com.acme.ledger;

import java.util.List;

/** Refunds a batch of customers. */
public class Refunds {

    private final Ledger ledger;

    public Refunds(Ledger ledger) {
        this.ledger = ledger;
    }

    public void refund(List<String> customers, long cents) {
        customers.forEach(c -> ledger.post(new Entry(c, cents)));
    }
}
