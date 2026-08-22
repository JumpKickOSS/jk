// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Child-JVM Java compile worker: Zinc incremental compile, streaming diagnostics, AP provenance,
 * and status as JSONL. {@code --pull} keeps the JVM for many COMPILE/PLAN specs on one job.
 */
public final class JavaIncrementalCompiler implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-java-compiler", "##JKJC:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (args.size() == 1 && "--pull".equals(args.get(0))) {
            return pull(out);
        }
        if (args.size() != 1) {
            System.err.println("usage: jk-java-compiler <spec-file>|@<spec-file>|--pull");
            return 2;
        }
        String specArg = args.get(0);
        String spec = specArg.startsWith("@") ? specArg.substring(1) : specArg;
        return compileSpec(Path.of(spec), out);
    }

    /**
     * Job-scoped loop: emit {@code ready}, read {@code COMPILE}/{@code PLAN} {@code <spec>} or
     * {@code DONE} from stdin. Stays up across modules so Zinc and AOT are paid once per job.
     */
    static int pull(ProtocolWriter out) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int worst = 0;
        while (true) {
            out.emit(PluginReply.ready());
            String line = in.readLine();
            if (line == null) return worst;
            String cmd = line.trim();
            if (cmd.isEmpty()) continue;
            if ("DONE".equals(cmd)) return worst;
            int space = cmd.indexOf(' ');
            String op = space < 0 ? cmd : cmd.substring(0, space);
            String spec = space < 0 ? "" : cmd.substring(space + 1).trim();
            if (spec.isEmpty()) {
                out.emit(PluginReply.error("usage", "COMPILE|PLAN <spec-file>"));
                worst = Math.max(worst, 2);
                continue;
            }
            int code =
                    switch (op) {
                        case "COMPILE" -> compileSpec(Path.of(spec), out);
                        case "PLAN" -> planSpec(Path.of(spec), out);
                        default -> {
                            out.emit(PluginReply.error("unknown", op));
                            yield 2;
                        }
                    };
            if (code != 0) worst = code;
        }
    }

    /** Run a compile from {@code specFile}, emitting JSONL to {@code out}; returns the exit code. */
    static int compileSpec(Path specFile, ProtocolWriter out) throws Exception {
        PluginSpec spec = PluginSpec.read(specFile);
        Path workdir = spec.workdir();
        boolean tempWork = workdir == null;
        if (tempWork) workdir = Files.createTempDirectory("jk-zinc-");
        try {
            int release = (int) spec.config().intValue("release", 0);
            String scalaVersion = spec.config().stringOpt("scalaVersion").orElse(null);
            ZincJavaCompiler.Result r =
                    (scalaVersion != null && !spec.compilerClasspath().isEmpty())
                            ? ZincJavaCompiler.compileMixed(
                                    spec.sources(),
                                    spec.compileClasspath(),
                                    spec.classesDir(),
                                    workdir,
                                    spec.sourceOutput(),
                                    release,
                                    spec.args(),
                                    spec.processorClasspath(),
                                    scalaVersion,
                                    spec.compilerClasspath(),
                                    spec.extra("scala-bridge").orElse(null),
                                    spec.extra("scala-library").orElse(null),
                                    spec.extra("scala-compiler").orElse(null))
                            : ZincJavaCompiler.compileJava(
                                    spec.sources(),
                                    spec.compileClasspath(),
                                    spec.classesDir(),
                                    workdir,
                                    spec.sourceOutput(),
                                    release,
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
                deleteTree(workdir);
            }
        }
    }

    static int planSpec(Path specFile, ProtocolWriter out) throws Exception {
        PluginSpec spec = PluginSpec.read(specFile);
        Path workdir = spec.workdir();
        int release = (int) spec.config().intValue("release", 0);
        ZincJavaCompiler.Plan plan = ZincJavaCompiler.planJava(
                spec.sources(),
                spec.compileClasspath(),
                spec.classesDir(),
                workdir,
                spec.sourceOutput(),
                release,
                spec.args(),
                spec.processorClasspath());
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
        out.emit(PluginReply.result(fields));
        return 0;
    }

    private static void deleteTree(Path workdir) {
        if (workdir == null) return;
        try (var walk = Files.walk(workdir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
            // temp workdir is best-effort
        }
    }
}
