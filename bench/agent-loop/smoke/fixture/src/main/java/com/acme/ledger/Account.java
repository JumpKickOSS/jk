package com.acme.ledger;

/** A customer account: who holds it and its credit limit in cents. */
public class Account {

    private String owner;
    private long limit;

    public Account(String owner, long limit) {
        this.owner = owner;
        this.limit = limit;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public long getLimit() {
        return limit;
    }
}
