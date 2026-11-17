// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Fragment;
import java.util.Set;

/** One file's text in, its fragment out. Pure: no I/O, no state between calls. */
@FunctionalInterface
public interface Extractor {

    Fragment extract(String relPath, String text);

    /**
     * The fragment with the command lines of {@code programs}, the programs the graph defines, that the
     * file passes flags to; an extractor that reads no command line ignores them, and with none
     * ({@link Set#of()}) no line is read for them.
     */
    default Fragment extract(String relPath, String text, Set<String> programs) {
        return extract(relPath, text);
    }
}
