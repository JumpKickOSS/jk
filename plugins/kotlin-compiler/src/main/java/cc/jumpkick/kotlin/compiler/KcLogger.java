// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import cc.jumpkick.plugin.protocol.CompilerProtocol;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.kotlin.buildtools.api.KotlinLogger;
import org.jspecify.annotations.Nullable;

/**
 * Bridges the Build Tools API's logger onto jk's JSONL protocol. Compiler diagnostics arrive here
 * as {@code error}/{@code warn} calls; we forward them to the parent as structured {@code diag}
 * lines. Of {@code debug}, which the incremental compiler writes whether or not it is enabled, only
 * the files each {@code Compile iteration:} names are kept: one indented line per source, relative
 * to a project root or absolute, followed by {@code " <- "} and the reason.
 */
final class KcLogger implements KotlinLogger {

    private static final String ITERATION = "Compile iteration:";
    private static final String REASON = " <- ";

    private final CompilerProtocol proto;
    private final List<Path> sources;
    private final Set<Path> compiled = new LinkedHashSet<>();
    private boolean iterated;
    private boolean inIteration;

    KcLogger(CompilerProtocol proto) {
        this(proto, List.of());
    }

    /** A logger for a compile of {@code sources}, whose iterations it records. */
    KcLogger(CompilerProtocol proto, List<Path> sources) {
        this.proto = proto;
        List<Path> normalized = new ArrayList<>(sources.size());
        for (Path p : sources) normalized.add(p.toAbsolutePath().normalize());
        this.sources = normalized;
    }

    /**
     * The sources the compile's iterations recompiled, or null when it reported no iteration: a
     * compile that names none cannot be told apart from one whose report this logger no longer reads.
     */
    @Nullable
    List<String> compiled() {
        if (!iterated) return null;
        List<String> out = new ArrayList<>(compiled.size());
        for (Path p : compiled) out.add(p.toString());
        return out;
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
        if (msg.startsWith(ITERATION)) {
            iterated = true;
            inIteration = true;
            return;
        }
        if (!inIteration) return;
        if (msg.isEmpty() || !Character.isWhitespace(msg.charAt(0))) {
            inIteration = false;
            return;
        }
        String entry = msg.strip();
        int reason = entry.indexOf(REASON);
        if (reason >= 0) entry = entry.substring(0, reason);
        Path source = source(entry);
        if (source != null) compiled.add(source);
    }

    @Override
    public void lifecycle(String msg) {
        proto.diagnostic("INFO", msg);
    }

    /** The compile's source {@code entry} names: itself when absolute, else the one it is a suffix of. */
    private @Nullable Path source(String entry) {
        Path named;
        try {
            named = Path.of(entry);
        } catch (InvalidPathException notAPath) {
            return null;
        }
        if (named.isAbsolute()) {
            Path abs = named.normalize();
            return sources.contains(abs) ? abs : null;
        }
        if (named.getNameCount() == 0) return null;
        for (Path p : sources) {
            if (p.endsWith(named)) return p;
        }
        return null;
    }

    private static String withThrowable(String msg, @Nullable Throwable t) {
        return t == null ? msg : msg + "\n" + t;
    }
}
