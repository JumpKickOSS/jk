// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Child-JVM Java compile worker: Zinc incremental compile, streaming diagnostics, AP provenance,
 * and status as JSONL.
 */
public final class JavaIncrementalCompiler implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-java-compiler", "##JKJC:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (args.size() != 1) {
            System.err.println("usage: jk-java-compiler <spec-file>|@<spec-file>");
            return 2;
        }
        String specArg = args.get(0);
        String spec = specArg.startsWith("@") ? specArg.substring(1) : specArg;
        return compileSpec(Path.of(spec), out);
    }

    /** Run a compile from {@code specFile}, emitting JSONL to {@code out}; returns the exit code. */
    static int compileSpec(Path specFile, ProtocolWriter out) throws Exception {
        PluginSpec spec = PluginSpec.read(specFile);
        Path workdir = spec.workdir();
        boolean tempWork = workdir == null;
        if (tempWork) workdir = Files.createTempDirectory("jk-zinc-");
        try {
            ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(
                    spec.sources(),
                    spec.compileClasspath(),
                    spec.classesDir(),
                    workdir,
                    spec.sourceOutput(),
                    (int) spec.config().intValue("release", 0),
                    spec.args(),
                    spec.processorClasspath());
            for (ZincJavaCompiler.Diag d : r.diagnostics()) {
                out.emit(PluginReply.diagnostic(d.kind(), d.file(), (int) d.line(), (int) d.col(), d.message()));
            }
            for (Map.Entry<Path, Set<Path>> e : r.generated().entrySet()) {
                out.emit(PluginReply.provenance(
                        e.getKey().toString(),
                        e.getValue().stream().map(Path::toString).toList()));
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("status", r.success() ? "OK" : "ERROR");
            fields.put(
                    "compiled",
                    r.compiledSources().stream()
                            .map(p -> p.toAbsolutePath().normalize().toString())
                            .toList());
            out.emit(PluginReply.result(fields));
            return r.success() ? 0 : 1;
        } finally {
            if (tempWork) {
                try (var walk = Files.walk(workdir)) {
                    walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        }
    }
}
