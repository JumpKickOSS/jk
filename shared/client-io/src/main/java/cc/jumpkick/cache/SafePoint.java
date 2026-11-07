// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

/**
 * A point in a store collector's scan where it may stop: everything it deleted so far was safe to
 * delete on its own, and nothing it has half-done needs finishing. The collector calls {@link
 * #check} there and the caller decides; a caller that wants the pass to stop throws from it (the
 * engine's idle prune unwinds to its own handler and runs the pass again later). The collectors
 * never catch what it throws, so a stop costs only the report.
 */
@FunctionalInterface
public interface SafePoint {

    /** Never stops: {@code jk cache clean}, the MCP disk tool and every other foreground caller. */
    SafePoint NONE = () -> {};

    /** May throw an unchecked exception to stop the pass here. */
    void check();
}
