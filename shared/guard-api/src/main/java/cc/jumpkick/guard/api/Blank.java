// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/** Which parts of a source file {@link Text#blanked} replaces with spaces (offsets and lines kept). */
public enum Blank {
    /** Comments blanked; string literals stay. */
    COMMENTS,
    /** Comments and string literals blanked: what the compiler sees. */
    COMMENTS_AND_STRINGS,
    /** The raw text. */
    NONE,
    /** The comments-only view: code and strings blanked. */
    CODE
}
