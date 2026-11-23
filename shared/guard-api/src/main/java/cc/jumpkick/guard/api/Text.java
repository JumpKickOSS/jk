// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.util.List;
import java.util.function.Function;

/**
 * The source tree as text, for what bytecode cannot say. Available to a {@link Scope#WORKSPACE}
 * suite, and to a {@link Scope#MODULE} suite for its own module's files only. Paths are
 * workspace-relative. jk notes which globs a guard lists and which files it reads here: when none of
 * them changed, nor the suite or its class path, the next run reports the guard's last verdict
 * without running it. A file a guard reads by any other way is not noted.
 */
public interface Text {

    /** Files matching a glob ({@code **}/{@code *}), sorted. */
    List<String> files(String glob);

    /** The file's text under a {@link Blank} projection; lines and offsets are preserved. */
    String blanked(String path, Blank mode);

    /** String literals in the file, in order. */
    List<String> literals(String path);

    /** The file's lines. */
    List<String> lines(String path);

    /**
     * What {@code scan} finds in the file at {@code path}, as lines. jk keeps them with the file's
     * text and {@code salt}: on a later run, while the file reads the same and neither the suite nor
     * its class path changed, they come back without calling {@code scan}. A guard that judges each
     * file on its own so pays only for the files an edit touched. {@code scan} must therefore depend
     * on the file's text alone, and {@code salt} must name everything else it uses (a digest of
     * what the guard gathered from other files, for instance). The file counts as read.
     */
    default List<String> perFile(String path, String salt, Function<String, List<String>> scan) {
        return scan.apply(path);
    }
}
