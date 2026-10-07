// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * The store's POM files parsed once per change. A lock reaches one POM from every repository
 * probe, parent walk and BOM import that names it; the parsed model is immutable, so all of them
 * share one parse while the file is unchanged.
 */
public final class StorePoms {

    /** Parsed POMs held at once, weighed by their declared entries (a BOM weighs its table). */
    static final long MAX_WEIGHT = 200_000;

    private static final StoreFileMemo<Pom> PARSED = new StoreFileMemo<>(MAX_WEIGHT, StorePoms::weight);

    private StorePoms() {}

    /** The POM at {@code pom}, parsed. */
    public static Pom parse(Path pom) throws IOException {
        return PARSED.get(pom, file -> PomParser.parse(Files.readAllBytes(file)))
                .orElseThrow(() -> new NoSuchFileException(pom.toString()));
    }

    /** Drop the parsed POMs and return how many went; force, tests and the idle engine. */
    public static int dropMemo() {
        return PARSED.clear();
    }

    /** How many POM files this process has read and parsed. */
    static long reads() {
        return PARSED.reads();
    }

    static long weight(Pom pom) {
        return 1L
                + pom.properties().size()
                + pom.dependencies().size()
                + pom.managedDependencies().size()
                + pom.inheritableManaged().size();
    }
}
