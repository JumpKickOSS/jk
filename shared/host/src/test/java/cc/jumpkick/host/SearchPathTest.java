// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * The two decisions that make {@code PATH} a different vocabulary from a classpath, pinned: blanks
 * are entries (the current directory on POSIX), and prepending onto nothing must not manufacture
 * one.
 */
class SearchPathTest {

    private static final String SEP = File.pathSeparator;

    /** The rule a classpath splitter gets wrong: an empty {@code PATH} entry is the current directory. */
    @Test
    void split_preserves_blank_entries() {
        assertThat(SearchPath.entries("a" + SEP + SEP + "b")).containsExactly("a", "", "b");
        assertThat(SearchPath.entries("a" + SEP)).containsExactly("a", "");
    }

    @Test
    void an_unset_path_searches_nothing() {
        assertThat(SearchPath.entries(null)).isEmpty();
        assertThat(SearchPath.entries("")).isEmpty();
    }

    @Test
    void separator_is_the_platform_search_path_separator() {
        assertThat(SearchPath.SEPARATOR).isEqualTo(File.pathSeparator);
    }

    @Test
    void prepend_puts_the_bin_dir_first() {
        assertThat(SearchPath.prepend("/jdk/bin", "/usr/bin" + SEP + "/bin"))
                .isEqualTo("/jdk/bin" + SEP + "/usr/bin" + SEP + "/bin");
    }

    /** Appending a separator to nothing would put the current directory on the search path. */
    @Test
    void prepend_onto_nothing_adds_no_blank_entry() {
        assertThat(SearchPath.prepend("/jdk/bin", null)).isEqualTo("/jdk/bin");
        assertThat(SearchPath.prepend("/jdk/bin", "")).isEqualTo("/jdk/bin");
    }
}
