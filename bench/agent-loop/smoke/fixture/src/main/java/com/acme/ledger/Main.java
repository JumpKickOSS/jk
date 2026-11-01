package com.acme.ledger;

import java.util.List;

public class Main {

    public static void main(String[] args) {
        Ledger ledger = new Ledger();
        Journal journal = new Journal();
        new Billing(ledger, journal).charge("ada", 500);
        new Refunds(ledger).refund(List.of("ada", "bob"), 100);
        ledger.transfer("ops", "ada", 50);
        new Payroll(ledger, journal).run("ops");
        System.out.println(ledger.balance("ada") + " " + journal.lines().size());
    }
}
