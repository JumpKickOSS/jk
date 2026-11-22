// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.prices;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.repo.StoreFileMemo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The price table in effect: the copy in the store when it was published after the one built into
 * JumpKick ({@link PriceTable#builtIn()}), else the built-in one. The store copy is read again
 * whenever it is replaced on disk, so a running engine prices with a table its maintenance cycle has
 * just fetched.
 */
public final class PriceTables {

    /** The store copy's file name, under {@link JkDirs#store()}. */
    public static final String FILE = "prices.toml";

    /** The parsed store copy, one entry per file asked about. */
    private static final StoreFileMemo<PriceTable> STORED = new StoreFileMemo<>(4, table -> 1);

    private PriceTables() {}

    /** The table in effect on this host. */
    public static PriceTable current() {
        return current(storeFile());
    }

    /** As {@link #current()} with the store copy at {@code file}. */
    public static PriceTable current(Path file) {
        PriceTable builtIn = PriceTable.builtIn();
        try {
            return STORED.get(file, f -> PriceTable.parse(Files.readAllBytes(f)))
                    .filter(stored -> stored.published().isAfter(builtIn.published()))
                    .orElse(builtIn);
        } catch (IOException e) {
            Log.debug("price table in the store is unreadable; the built-in one applies", e);
            return builtIn;
        }
    }

    /** Where the engine keeps the newest table it fetched. */
    public static Path storeFile() {
        return JkDirs.store().resolve(FILE);
    }
}
