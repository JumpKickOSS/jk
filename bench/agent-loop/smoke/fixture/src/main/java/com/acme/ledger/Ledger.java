package com.acme.ledger;

import java.util.ArrayList;
import java.util.List;

/** The books: every posted entry, in order. */
public class Ledger {

    private final List<Entry> entries = new ArrayList<>();

    /** Record one entry. */
    public void post(Entry entry) {
        entries.add(entry);
    }

    /** Move money between two accounts as a pair of entries. */
    public void transfer(String from, String to, long cents) {
        post(new Entry(from, -cents));
        post(new Entry(to, cents));
    }

    public long balance(String account) {
        return entries.stream()
                .filter(e -> e.account().equals(account))
                .mapToLong(Entry::cents)
                .sum();
    }
}
