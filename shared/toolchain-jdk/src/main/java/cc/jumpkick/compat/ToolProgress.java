// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

/**
 * Progress of one tool install: the archive's bytes as they arrive, then the unpack. {@code name}
 * is the tool and version as a user reads it ({@code Node.js 24.21.0}); {@code totalBytes} is
 * {@code 0} when the server sends no length.
 */
public interface ToolProgress {

    /** Reports nothing. */
    ToolProgress NONE = new ToolProgress() {};

    /** Cumulative bytes of {@code name}'s archive. */
    default void downloading(String name, long readBytes, long totalBytes) {}

    /** The archive is complete and being unpacked. */
    default void installing(String name) {}
}
