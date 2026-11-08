// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.process.CommandChannel;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Child-JVM Java compile worker: Zinc incremental compile, diagnostics, AP provenance and status as
 * JSONL. {@code --pull} keeps the JVM resident and runs many compiles and plans at once on threads
 * ({@link CompileServer}), answering on the command channel; a spec argument runs one compile and
 * answers on stdout, which is how the startup-cache trainer runs it.
 */
public final class JavaIncrementalCompiler implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-java-compiler", "##JKJC:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (!args.isEmpty() && "--pull".equals(args.get(0))) {
            try (CommandChannel.Duplex channel = CommandChannel.connectDuplex(args)) {
                return new CompileServer(channel.in(), channel::send, JavaIncrementalCompiler::runSpec).serve();
            }
        }
        if (args.size() != 1) {
            System.err.println(
                    "usage: jk-java-compiler <spec-file>|@<spec-file>|--pull --commands=<host>:<port> --token=<hex>");
            return Exit.USAGE;
        }
        String specArg = args.get(0);
        String spec = specArg.startsWith("@") ? specArg.substring(1) : specArg;
        return compileSpec(Path.of(spec), out);
    }

    /** One compile or plan of the resident worker, its frames sent through {@code emit}. */
    static int runSpec(boolean plan, Path spec, Consumer<String> emit) throws Exception {
        return plan ? planSpec(spec, emit) : compileSpec(spec, emit);
    }

    /** As {@link #compileSpec(Path, Consumer)}, emitting through {@code out}. */
    static int compileSpec(Path specFile, ProtocolWriter out) throws Exception {
        return compileSpec(specFile, out::emit);
    }

    /** Run a compile from {@code specFile}, emitting JSONL to {@code out}; returns the exit code. */
    static int compileSpec(Path specFile, Consumer<String> out) throws Exception {
        CompileCancel.checkpoint();
        PluginSpec spec = PluginSpec.read(specFile);
        Path specWorkdir = spec.workdir();
        boolean tempWork = specWorkdir == null;
        Path workdir = specWorkdir != null ? specWorkdir : Files.createTempDirectory("jk-zinc-");
        try {
            int release = (int) spec.config().intValue("release", 0);
            String scalaVersion = spec.config().stringOpt("scalaVersion").orElse(null);
            JavaCompileJob job = job(spec, workdir, release);
            ZincJavaCompiler.Result r =
                    (scalaVersion != null && !spec.compilerClasspath().isEmpty())
                            ? ZincJavaCompiler.compileMixed(
                                    job,
                                    scalaVersion,
                                    spec.compilerClasspath(),
                                    spec.extra("scala-bridge").orElse(null),
                                    spec.extra("scala-library").orElse(null),
                                    spec.extra("scala-compiler").orElse(null))
                            : ZincJavaCompiler.compileJava(job);
            for (ZincJavaCompiler.Diag d : r.diagnostics()) {
                out.accept(PluginReply.diagnostic(
                        d.kind(), d.file(), (int) d.line(), (int) d.col(), d.message(), d.key()));
            }
            for (Map.Entry<Path, Set<Path>> e : r.generated().entrySet()) {
                out.accept(PluginReply.provenance(
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
            out.accept(PluginReply.result(fields));
            return r.success() ? 0 : 1;
        } finally {
            if (tempWork) {
                deleteTree(workdir);
            }
        }
    }

    /** The spec's compile facts, read once for whichever entry point the spec selects. */
    private static JavaCompileJob job(PluginSpec spec, @Nullable Path workdir, int release) {
        return new JavaCompileJob(
                spec.sources(),
                spec.compileClasspath(),
                Objects.requireNonNull(spec.classesDir(), "spec missing layout.classesDir (OUTPUT)"),
                workdir,
                spec.sourceOutput(),
                release,
                spec.args(),
                spec.processorClasspath(),
                spec.config()
                        .stringOpt(PluginProtocol.CONFIG_PHASES_LOG)
                        .map(Path::of)
                        .orElse(null),
                spec.classpathAnalyses());
    }

    static int planSpec(Path specFile, Consumer<String> out) throws Exception {
        CompileCancel.checkpoint();
        PluginSpec spec = PluginSpec.read(specFile);
        Path workdir = spec.workdir();
        int release = (int) spec.config().intValue("release", 0);
        ZincJavaCompiler.Plan plan = ZincJavaCompiler.planJava(job(spec, workdir, release));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "OK");
        fields.put("outcome", plan.full() ? "full" : "incremental");
        fields.put("reason", plan.reason());
        List<String> compiled = new ArrayList<>();
        List<String> whys = new ArrayList<>();
        for (ZincJavaCompiler.Invalidation i : plan.invalidations()) {
            compiled.add(i.source().toAbsolutePath().normalize().toString());
            whys.add(i.why());
        }
        fields.put("compiled", compiled);
        fields.put("whys", whys);
        out.accept(PluginReply.result(fields));
        return 0;
    }

    private static void deleteTree(Path workdir) {
        // temp workdir is best-effort; the owner swallows failures
        if (workdir != null) PathUtil.deleteRecursively(workdir);
    }
}
