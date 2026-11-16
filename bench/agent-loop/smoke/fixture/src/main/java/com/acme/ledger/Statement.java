package com.acme.ledger;

/** One line of a customer's statement. */
public class Statement {

    public String line(Account account, Ledger ledger) {
        String owner = account.getOwner();
        return owner + ": " + ledger.balance(owner);
    }

    public void transferTo(Account account, String holder) {
        account.setOwner(holder);
    }
}
