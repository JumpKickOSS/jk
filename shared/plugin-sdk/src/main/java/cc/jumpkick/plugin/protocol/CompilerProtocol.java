// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import cc.jumpkick.model.command.Exit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The compiler-worker half of the plugin wire: a typed view over {@link ProtocolWriter} plus the
 * one-spec-argument entry point every language worker shares. The prefix and line framing live in
 * the {@code ProtocolWriter} (built by {@code PluginMain} from the plugin manifest); this only
 * builds the reply for each event.
 *
 * <p>One owner for every JVM language worker. {@code jk-kotlin-compiler} and
 * {@code jk-groovy-compiler} carried a 33-line copy each ({@code KcProtocol}/{@code GcProtocol})
 * that differed only in which arity of {@link #diagnostic} they exposed — Kotlin's Build Tools API
 * logger surfaces text with no locus, Groovy's {@code ErrorCollector} surfaces file/line/column.
 * That is a caller difference, not a protocol difference: the wire shape is one
 * {@link PluginReply#diagnostic} either way.
 *
 * <p>It lives in {@code :plugin-sdk} rather than {@code :host} because {@link ProtocolWriter} and
 * {@link PluginReply} do, and in neither case in {@code :core} — a worker rebuilds its launch
 * classpath from a POM and {@code :core} declares {@code api(libs.tomlj)}, which would drag a TOML
 * parser and ANTLR onto every worker.
 */
public final class CompilerProtocol {

    /**
     * The worker exit code for a compiler fault — an OOM or an internal compiler error, where the
     * tool failed rather than the code under compilation. {@code sysexits.h} has no name for it and
     * {@link Exit} does not invent one, but both compiler workers document and emit the same 3, so
     * the value belongs here rather than twice in a javadoc sentence.
     */
    public static final int COMPILER_FAULT = 3;

    private final ProtocolWriter out;

    public CompilerProtocol(ProtocolWriter out) {
        this.out = out;
    }

    /** A compiler diagnostic with no locus (Kotlin's BTA logger surfaces text only). */
    public void diagnostic(String severity, String message) {
        diagnostic(severity, null, 0, 0, message);
    }

    /** A located compiler diagnostic; {@code file} may be null, {@code line}/{@code col} 0 when unknown. */
    public void diagnostic(String severity, String file, int line, int col, String message) {
        out.emit(PluginReply.diagnostic(severity, file, line, col, message));
    }

    /** The terminal outcome, e.g. {@code COMPILATION_SUCCESS}. */
    public void result(String status) {
        out.emit(PluginReply.result(Map.of("status", status)));
    }

    /**
     * The worker entry point: validate the single {@code @spec} argument, decode it, and run
     * {@code body}. An escaping throwable is printed to stderr (the host keeps a bounded tail of
     * non-protocol output and surfaces it when a worker dies before speaking protocol) and mapped
     * to {@link Exit#SOFTWARE}; a wrong argument count is {@link Exit#USAGE}.
     *
     * @param pluginId the worker's manifest id, used in the usage line and the failure header
     */
    public static int compileFromSpec(String pluginId, List<String> args, ProtocolWriter out, SpecCompile body) {
        CompilerProtocol proto = new CompilerProtocol(out);
        try {
            if (args.size() != 1) {
                System.err.println("usage: " + pluginId + " <spec-file>|@<spec-file>");
                return Exit.USAGE;
            }
            String arg = args.get(0);
            PluginSpec spec = PluginSpec.read(Path.of(arg.startsWith("@") ? arg.substring(1) : arg));
            return body.compile(spec, proto);
        } catch (Throwable t) {
            System.err.println(pluginId + ": " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            return Exit.SOFTWARE;
        }
    }

    /** The per-language half of {@link #compileFromSpec}: decode the spec and compile it. */
    @FunctionalInterface
    public interface SpecCompile {

        /** @return the worker's process exit code */
        int compile(PluginSpec spec, CompilerProtocol proto) throws Exception;
    }
}
