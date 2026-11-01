package com.acme.ledger;

/** Compiles only when Ledger.post is renamed to record and Journal.post is left alone. */
final class SmokeCheck {

    static void check() {
        new Ledger().record(new Entry("x", 1));
        new Journal().post("x");
    }
}
