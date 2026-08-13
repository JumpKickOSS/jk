// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

/** HTTP/MCP has no CLI writer — hooks still run. */
public enum NoopEventSink implements EventSink {
    INSTANCE;

    @Override
    public void emit(EngineEvent event) {}
}
