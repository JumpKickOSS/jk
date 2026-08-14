// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.processing.Processor;

/**
 * Child-JVM {@link InProcessJavac} worker: runs under the project JDK and streams diagnostics,
 * AP provenance, and status as JSONL.
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
        List<Processor> processors = loadProcessors(spec.processorClasspath());

        InProcessJavac.Result r = InProcessJavac.compile(
                spec.sources(),
                spec.compileClasspath(),
                spec.classesDir(),
                spec.sourceOutput(),
                (int) spec.config().intValue("release", 0),
                spec.args(),
                processors);

        for (InProcessJavac.Diag d : r.diagnostics()) {
            out.emit(PluginReply.diagnostic(d.kind(), d.file(), (int) d.line(), (int) d.col(), d.message()));
        }
        for (Map.Entry<Path, Set<Path>> e : r.generated().entrySet()) {
            out.emit(PluginReply.provenance(
                    e.getKey().toString(),
                    e.getValue().stream().map(Path::toString).toList()));
        }
        out.emit(PluginReply.result(Map.of("status", r.success() ? "OK" : "ERROR")));
        out.emit(PluginReply.done(r.success() ? 0 : 1));
        return r.success() ? 0 : 1;
    }

    /** ServiceLoader-discover annotation processors from the processor path (jars/dirs). */
    private static List<Processor> loadProcessors(List<Path> processorPath) {
        if (processorPath.isEmpty()) return List.of();
        URL[] urls = new URL[processorPath.size()];
        for (int i = 0; i < processorPath.size(); i++) {
            try {
                urls[i] = processorPath.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad processor path entry: " + processorPath.get(i), e);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls, JavaIncrementalCompiler.class.getClassLoader());
        List<Processor> processors = new ArrayList<>();
        for (Processor p : ServiceLoader.load(Processor.class, loader)) processors.add(p);
        return processors;
    }
}
