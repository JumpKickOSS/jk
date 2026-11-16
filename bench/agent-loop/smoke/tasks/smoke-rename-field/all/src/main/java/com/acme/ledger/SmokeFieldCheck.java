package com.acme.ledger;

/** Compiles only when Account.owner is renamed to holder with its getter and setter. */
final class SmokeFieldCheck {

    static String check(Account a) {
        a.setHolder("x");
        return a.getHolder();
    }
}
