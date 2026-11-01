package com.acme.ledger;

import java.util.ArrayList;
import java.util.List;

/** Free-text notes beside the books; not the ledger. */
public class Journal {

    private final List<String> lines = new ArrayList<>();

    /** Add a note. Unrelated to {@code Ledger.post}, despite the name. */
    public void post(String line) {
        lines.add(line);
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }
}
