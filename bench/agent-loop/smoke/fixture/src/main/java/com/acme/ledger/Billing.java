package com.acme.ledger;

/** Charges a customer. */
public class Billing {

    private final Ledger ledger;
    private final Journal journal;

    public Billing(Ledger ledger, Journal journal) {
        this.ledger = ledger;
        this.journal = journal;
    }

    public void charge(String customer, long cents) {
        ledger.post(new Entry(customer, -cents));
        journal.post("charged " + customer);
    }
}
