// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

/** One domain API, many encodings (CLI JSONL, SSE, tests). */
@FunctionalInterface
public interface EventSink {
    void emit(EngineEvent event);

    default void close() {}
}
