// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import org.jspecify.annotations.Nullable;

/**
 * What the requesting process's environment says about a build request: its trigger and its
 * progress-bar mode. Read once, where the CLI builds the request — never inside {@code encode()},
 * because the engine encodes requests too (web and MCP submissions) and its own environment is not
 * the requester's: a daemon started under {@code JK_BUILD_TRIGGER=ci} would have journaled every
 * dashboard build as {@code ci}.
 */
public final class RequestEnvironment {

    private RequestEnvironment() {}

    /** {@code jk.build.trigger} system property, else {@code JK_BUILD_TRIGGER}; null when neither is set. */
    public static @Nullable String trigger() {
        String trigger = System.getProperty("jk.build.trigger");
        if (trigger == null || trigger.isBlank()) trigger = System.getenv("JK_BUILD_TRIGGER");
        return trigger == null || trigger.isBlank() ? null : trigger.trim();
    }

    /** The progress-bar mode the environment asks for, or null for the default (auto). */
    public static @Nullable String progressMode() {
        ProgressBarMode mode = ProgressBarMode.fromEnvironment();
        return mode == ProgressBarMode.AUTO ? null : mode.wireName();
    }
}
