package com.acme.ledger;

/** Compiles only when Journal is renamed to Diary. */
final class SmokeClassCheck {

    static Sink check() {
        return new Diary();
    }
}
