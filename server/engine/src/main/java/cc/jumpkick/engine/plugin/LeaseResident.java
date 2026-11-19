// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A helper that holds a {@link WorkerLeases} lease between requests. {@code yield} is called off the
 * ledger's lock when the head of the queue needs this lease back; it lets the request in flight
 * finish, then makes the process exit, which closes the grant. It may block. {@code idle} says the
 * helper is between requests, so its heap does not count toward the bound on what held heaps may
 * grow to; the ledger reads it under its lock, so it must neither block nor take a lock.
 */
public record LeaseResident(String name, Runnable yield, BooleanSupplier idle) {

    public LeaseResident {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(yield, "yield");
        Objects.requireNonNull(idle, "idle");
    }

    /** A helper that never says it is idle: its heap always counts toward the bound. */
    public LeaseResident(String name, Runnable yield) {
        this(name, yield, () -> false);
    }
}
