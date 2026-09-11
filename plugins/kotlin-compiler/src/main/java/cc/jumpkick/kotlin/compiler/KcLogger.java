// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import cc.jumpkick.plugin.protocol.CompilerProtocol;
import org.jetbrains.kotlin.buildtools.api.KotlinLogger;
import org.jspecify.annotations.Nullable;

/**
 * Bridges the Build Tools API's logger onto jk's JSONL protocol. Compiler diagnostics arrive here
 * as {@code error}/{@code warn} calls; we forward them to the parent as structured {@code diag}
 * lines. {@code debug} is dropped (we report {@link #isDebugEnabled()} {@code false}).
 */
final class KcLogger implements KotlinLogger {

    private final CompilerProtocol proto;

    KcLogger(CompilerProtocol proto) {
        this.proto = proto;
    }

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void error(String msg, @Nullable Throwable throwable) {
        proto.diagnostic("ERROR", withThrowable(msg, throwable));
    }

    @Override
    public void warn(String msg, @Nullable Throwable throwable) {
        proto.diagnostic("WARNING", withThrowable(msg, throwable));
    }

    @Override
    public void warn(String msg) {
        proto.diagnostic("WARNING", msg);
    }

    @Override
    public void info(String msg) {
        proto.diagnostic("INFO", msg);
    }

    @Override
    public void debug(String msg) {
        // dropped — see isDebugEnabled()
    }

    @Override
    public void lifecycle(String msg) {
        proto.diagnostic("INFO", msg);
    }

    private static String withThrowable(String msg, @Nullable Throwable t) {
        return t == null ? msg : msg + "\n" + t;
    }
}
