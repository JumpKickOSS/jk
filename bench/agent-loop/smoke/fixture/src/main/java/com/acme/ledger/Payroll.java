package com.acme.ledger;

/** Pays staff; writes to the journal and reads balances, but posts nothing to the ledger. */
public class Payroll {

    private final Ledger ledger;
    private final Journal journal;

    public Payroll(Ledger ledger, Journal journal) {
        this.ledger = ledger;
        this.journal = journal;
    }

    public void run(String account) {
        // A payroll run does not call ledger.post: the bank posts it.
        journal.post("payroll for " + account + " at balance " + ledger.balance(account));
    }
}
