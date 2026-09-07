// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.util.List;

/**
 * The source tree as text, for what bytecode cannot say. Available to a {@link Scope#WORKSPACE}
 * suite, and to a {@link Scope#MODULE} suite for its own module's files only. Paths are
 * workspace-relative.
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
}
