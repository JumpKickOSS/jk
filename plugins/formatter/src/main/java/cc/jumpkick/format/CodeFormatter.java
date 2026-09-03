// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import com.diffplug.spotless.DirtyState;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.groovy.RemoveSemicolonsStep;
import com.diffplug.spotless.java.GoogleJavaFormatStep;
import com.diffplug.spotless.java.ImportOrderStep;
import com.diffplug.spotless.java.PalantirJavaFormatStep;
import com.diffplug.spotless.java.RemoveUnusedImportsStep;
import com.diffplug.spotless.kotlin.KtfmtStep;
import com.diffplug.spotless.scala.ScalaFmtStep;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * {@code jk-formatter} plugin: optional FQCN-shorten pass, then Spotless. Host forks with a
 * tab-delimited spec file; emits {@code ##JKFMT:} JSONL per file + summary.
 *
 * <p>Java Spotless pipeline: {@code importOrder} → {@code removeUnusedImports} → Palantir / Google
 * / AOSP. Kotlin is ktfmt. Groovy is semicolon removal. Scala is scalafmt. FQCN shortening is a
 * first-party index pass (no compiler) that runs before Spotless.
 */
public final class CodeFormatter implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-formatter", "##JKFMT:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (args.isEmpty()) {
            System.err.println("jk-formatter: expected spec file path");
            return Exit.USAGE;
        }
        Spec spec = Spec.from(PluginSpec.read(Path.of(args.get(0))));

        FormatStampCache stampCache = spec.cacheDir != null && spec.configKey != null
                ? new FormatStampCache(CacheTree.FORMAT_STAMPS.under(spec.cacheDir), spec.configKey)
                : null;

        // Built once for the whole run, then only read, so every thread shares one copy. That is the
        // property the OpenRewrite pass could not have: it needed a fresh parser per file for source
        // isolation, so its type cache had to be per-thread and parallelism partly cancelled it.
        TypeIndex index = spec.optimizeImports ? TypeIndex.scan(spec.indexFiles) : null;

        int changed = 0, clean = 0, errors = 0;
        Workers workers = new Workers(spec);
        try {
            // Work runs in parallel; results are emitted in spec order, so the per-file stream — and
            // every tally the host derives from it — does not depend on thread timing.
            List<Future<FileResult>> pending = new ArrayList<>(spec.files.size());
            ExecutorService pool = Executors.newFixedThreadPool(concurrency(spec));
            try {
                for (FileRef ref : spec.files) {
                    pending.add(pool.submit(() -> formatOne(ref, spec, stampCache, index, workers)));
                }
            } finally {
                pool.shutdown();
            }
            for (int i = 0; i < pending.size(); i++) {
                FileResult result;
                try {
                    result = pending.get(i).get();
                } catch (ExecutionException e) {
                    // pending is index-aligned with spec.files, so the crashed task's file is
                    // knowable — name it, or reconcile blames the wrong "never visited" file.
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    errors++;
                    emitFile(out, spec.files.get(i).file(), "error", String.valueOf(cause.getMessage()));
                    continue;
                }
                switch (result.status()) {
                    case "changed" -> changed++;
                    case "error" -> errors++;
                    default -> clean++;
                }
                emitFile(out, result.file(), result.status(), result.msg());
            }
        } finally {
            workers.close();
            // One write for the whole run: every contains/record above was a map operation.
            if (stampCache != null) stampCache.save();
        }

        int exit = errors > 0 || (!spec.apply && changed > 0) ? 1 : 0;
        return exit;
    }

    /** One file's verdict, decided off-thread and emitted in spec order by {@link #run}. */
    private record FileResult(File file, String status, String msg) {}

    /**
     * How many files to format at once. The work is per-file independent and CPU-bound, and the host
     * launches this worker as its only fork, so {@code ActiveProcessorCount} is the whole machine.
     * Capped at 8: past that the curve flattens and every thread adds a live Spotless step chain to a
     * heap sized for one worker. {@code jk.format.threads} overrides for measurement.
     */
    static int concurrency(Spec spec) {
        Integer override = Integer.getInteger("jk.format.threads");
        int files = Math.max(1, spec.files.size());
        if (override != null && override > 0) return Math.min(override, files);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int byHeap = (int) Math.max(1, heapMb / 256);
        return Math.max(1, Math.min(Math.min(cores, 8), Math.min(byHeap, files)));
    }

    /**
     * Per-thread Spotless formatters. A {@link Formatter} owns its step chain — google-java-format,
     * palantir, ktfmt and scalafmt behind their own classloaders — and is not documented thread-safe,
     * so no two threads share one. Built lazily per language: a tree with no Scala never pays for a
     * scalafmt chain, and a thread that only ever sees Java builds only that one.
     *
     * <p>The {@link TypeIndex} is deliberately <em>not</em> in here — it is immutable once built, so
     * one copy serves every thread.
     */
    private static final class Workers implements AutoCloseable {

        private final List<Holder> created = Collections.synchronizedList(new ArrayList<>());
        private final ThreadLocal<Holder> local;

        Workers(Spec spec) {
            this.local = ThreadLocal.withInitial(() -> {
                Holder h = new Holder(spec);
                created.add(h);
                return h;
            });
        }

        Holder get() {
            return local.get();
        }

        @Override
        public void close() {
            synchronized (created) {
                for (Holder h : created) h.close();
            }
        }

        static final class Holder implements AutoCloseable {
            private final Spec spec;
            private final EnumMap<Kind, Formatter> byKind = new EnumMap<>(Kind.class);

            Holder(Spec spec) {
                this.spec = spec;
            }

            /** The formatter for {@code kind} on this thread, or null when the run has no jars for it. */
            Formatter formatter(Kind kind) {
                if (byKind.containsKey(kind)) return byKind.get(kind);
                Formatter f = build(kind);
                byKind.put(kind, f);
                return f;
            }

            private Formatter build(Kind kind) {
                List<FormatterStep> steps =
                        switch (kind) {
                            case JAVA -> spec.javaJars.isEmpty() ? null : javaSteps(spec);
                            case KOTLIN ->
                                spec.kotlinJars.isEmpty()
                                        ? null
                                        : List.of(KtfmtStep.create(
                                                spec.kotlinVersion,
                                                provisioner(spec.kotlinJars),
                                                ktfmtStyle(spec.kotlinStyle),
                                                ktfmtOptions(spec.kotlinMaxWidth)));
                            case GROOVY -> groovySteps();
                            case SCALA -> spec.scalaJars.isEmpty() ? null : scalaSteps(spec);
                        };
                if (steps == null) return null;
                return Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(steps)
                        .build();
            }

            @Override
            public void close() {
                for (Formatter f : byKind.values()) {
                    if (f != null) f.close();
                }
            }
        }
    }

    /**
     * One file, start to finish: stamp lookup, the FQCN pass, then Spotless. Runs on a pool thread
     * and returns its verdict rather than emitting it, so {@link #run} keeps the stream in spec order.
     */
    private static FileResult formatOne(
            FileRef ref, Spec spec, FormatStampCache stampCache, TypeIndex index, Workers workers) {
        Formatter fmt = workers.get().formatter(ref.kind());
        // The host listed a file but sent no jars for its language. Reported, not skipped in silence:
        // a file the run was told to visit and said nothing about is indistinguishable from a dead
        // worker, which is the shortfall FormatWorker.reconcile exists to catch.
        if (fmt == null) {
            return new FileResult(ref.file(), "error", "no " + ref.kind() + " formatter jars were provided");
        }
        try {
            byte[] originalBytes = Files.readAllBytes(ref.file().toPath());
            String stampKey = stampCache != null ? stampCache.keyFor(originalBytes) : null;
            if (stampKey != null && stampCache.contains(stampKey)) {
                return new FileResult(ref.file(), "clean", null);
            }

            if (ref.kind() == Kind.JAVA && isUnnamedClass(originalBytes)) {
                if (stampKey != null) stampCache.record(stampKey);
                return new FileResult(ref.file(), "skipped", null);
            }

            // Java only. The blanking pass implements Java's lexeme set, and the other three differ in
            // ways that make it rewrite string contents: Groovy has `'''` blocks and slashy `/…/`
            // literals, Kotlin and Scala have `import … as Alias` and brace/underscore imports whose
            // bindings this pass cannot read. Java is also the only one of the four whose Spotless
            // chain includes an import-repair step, so it is the only one where a mistake here would
            // be noticed downstream rather than written and stamped. Widening this needs a real lexer
            // per language, not a wider regex.
            boolean shortened = false;
            if (index != null && ref.kind() == Kind.JAVA) {
                String src = new String(originalBytes, StandardCharsets.UTF_8);
                FqcnShortener.Result r = FqcnShortener.shorten(src, index, syntax(ref.kind()));
                if (r.changed()) {
                    shortened = true;
                    if (spec.apply) Files.writeString(ref.file().toPath(), r.source(), StandardCharsets.UTF_8);
                }
            }

            DirtyState state = DirtyState.of(fmt, ref.file());
            boolean spotlessChanged = !state.isClean() && !state.didNotConverge();

            if (state.didNotConverge()) {
                return new FileResult(ref.file(), "error", "formatter did not converge");
            }
            if (shortened || spotlessChanged) {
                if (spec.apply && spotlessChanged) state.writeCanonicalTo(ref.file());
                if (spec.apply && stampCache != null) {
                    byte[] finalBytes = Files.readAllBytes(ref.file().toPath());
                    String finalKey = stampCache.keyFor(finalBytes);
                    if (finalKey != null) stampCache.record(finalKey);
                }
                return new FileResult(ref.file(), "changed", null);
            }
            if (stampKey != null) stampCache.record(stampKey);
            return new FileResult(ref.file(), "clean", null);
        } catch (Exception e) {
            return new FileResult(ref.file(), "error", e.getMessage());
        }
    }

    private static FqcnShortener.Syntax syntax(Kind kind) {
        return switch (kind) {
            case JAVA -> FqcnShortener.Syntax.JAVA;
            case KOTLIN -> FqcnShortener.Syntax.KOTLIN;
            case GROOVY -> FqcnShortener.Syntax.GROOVY;
            case SCALA -> FqcnShortener.Syntax.SCALA;
        };
    }

    private static void emitFile(ProtocolWriter out, File file, String status, String msg) {
        out.emit(PluginReply.file(file.getAbsolutePath(), status, msg));
    }

    /**
     * Java steps in Spotless order: optional import order, optional remove-unused, then the chosen
     * style formatter. {@code removeUnusedImports} uses google-java-format under the hood, so when
     * the style is Palantir the host also passes GJF jars in {@link Spec#removeUnusedJars}.
     */
    static List<FormatterStep> javaSteps(Spec spec) {
        Provisioner styleProv = provisioner(spec.javaJars);
        var steps = new ArrayList<FormatterStep>();
        if (spec.importOrder) {
            steps.add(ImportOrderStep.forJava().createFrom());
        }
        if (spec.removeUnusedImports) {
            Set<File> removeUnusedJars = spec.removeUnusedJars.isEmpty() ? spec.javaJars : spec.removeUnusedJars;
            steps.add(RemoveUnusedImportsStep.create(provisioner(removeUnusedJars)));
        }
        steps.add(styleStep(spec, styleProv));
        return List.copyOf(steps);
    }

    static List<FormatterStep> groovySteps() {
        return List.of(RemoveSemicolonsStep.create());
    }

    static List<FormatterStep> scalaSteps(Spec spec) {
        File config = scalaConfig(spec);
        return List.of(ScalaFmtStep.create(spec.scalaVersion, provisioner(spec.scalaJars), config));
    }

    /** Palantir-aligned defaults: 4-space indent, 120 columns, Scala 3 dialect. */
    private static File scalaConfig(Spec spec) {
        Path dir = spec.cacheDir;
        if (dir == null) return null;
        try {
            Files.createDirectories(dir);
            Path conf = dir.resolve("jk-scalafmt.conf");
            Files.writeString(conf, """
                    version = "%s"
                    runner.dialect = scala3
                    maxColumn = 120
                    indent.main = 4
                    """.formatted(spec.scalaVersion), StandardCharsets.UTF_8);
            return conf.toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private static FormatterStep styleStep(Spec spec, Provisioner prov) {
        if ("palantir".equalsIgnoreCase(spec.javaStyle)) {
            return PalantirJavaFormatStep.create(spec.javaVersion, "PALANTIR", /* formatJavadoc */ false, prov);
        }
        return GoogleJavaFormatStep.create(spec.javaVersion, spec.javaStyle.toUpperCase(Locale.ROOT), prov);
    }

    private static Provisioner provisioner(Set<File> jars) {
        return (withTransitives, coords) -> jars;
    }

    private static KtfmtStep.Style ktfmtStyle(String style) {
        return switch (style.toLowerCase(Locale.ROOT)) {
            case "kotlinlang" -> KtfmtStep.Style.KOTLINLANG;
            case "google" -> KtfmtStep.Style.GOOGLE;
            case "meta" -> KtfmtStep.Style.META;
            default -> throw new IllegalArgumentException("unknown kotlin style: " + style);
        };
    }

    private static KtfmtStep.KtfmtFormattingOptions ktfmtOptions(int maxWidth) {
        var opts = new KtfmtStep.KtfmtFormattingOptions();
        if (maxWidth > 0) opts.setMaxWidth(maxWidth);
        return opts;
    }

    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record)\\s+\\w|@interface\\s+\\w");

    /** True if the source has no top-level type declaration (Java 21+ unnamed class). */
    static boolean isUnnamedClass(byte[] bytes) {
        String src = new String(bytes, StandardCharsets.UTF_8);
        String stripped = src.replaceAll("//[^\n]*", "").replaceAll("(?s)/\\*.*?\\*/", " ");
        return !TYPE_DECL.matcher(stripped).find();
    }

    enum Kind {
        JAVA,
        KOTLIN,
        GROOVY,
        SCALA
    }

    record FileRef(Kind kind, File file) {}

    static final class Spec {
        boolean apply = true;
        String javaStyle = "palantir";
        String javaVersion = PalantirJavaFormatStep.defaultVersion();
        Set<File> javaJars = new LinkedHashSet<>();
        Set<File> removeUnusedJars = new LinkedHashSet<>();

        String kotlinStyle = "kotlinlang";
        String kotlinVersion = KtfmtStep.defaultVersion();
        int kotlinMaxWidth = 0;
        Set<File> kotlinJars = new LinkedHashSet<>();
        String scalaVersion = ScalaFmtStep.defaultVersion();
        Set<File> scalaJars = new LinkedHashSet<>();
        boolean optimizeImports = false;
        boolean importOrder = true;
        boolean removeUnusedImports = true;
        Path cacheDir = null;
        String configKey = null;
        /** All project sources the type index should read (may be a superset of {@link #files}). */
        List<Path> indexFiles = List.of();

        final List<FileRef> files = new ArrayList<>();

        static Spec from(PluginSpec ws) {
            Spec s = new Spec();
            PluginConfig c = ws.config();
            s.apply = c.bool("apply", true);
            s.javaStyle = c.stringOpt("javaStyle").orElse(s.javaStyle);
            s.javaVersion = c.stringOpt("javaVersion").orElse(s.javaVersion);
            s.javaJars = jars(c.stringList("javaJars"));
            s.removeUnusedJars = jars(c.stringList("removeUnusedJars"));
            s.kotlinStyle = c.stringOpt("kotlinStyle").orElse(s.kotlinStyle);
            s.kotlinVersion = c.stringOpt("kotlinVersion").orElse(s.kotlinVersion);
            s.kotlinMaxWidth = (int) c.intValue("kotlinMaxWidth", 0);
            s.kotlinJars = jars(c.stringList("kotlinJars"));
            s.scalaVersion = c.stringOpt("scalaVersion").orElse(s.scalaVersion);
            s.scalaJars = jars(c.stringList("scalaJars"));
            s.optimizeImports = c.bool("optimizeImports", false);
            s.importOrder = c.bool("importOrder", true);
            s.removeUnusedImports = c.bool("removeUnusedImports", true);
            c.stringOpt("cacheDir").ifPresent(p -> s.cacheDir = Path.of(p));
            c.stringOpt("configKey").ifPresent(k -> s.configKey = k);
            List<Path> index = new ArrayList<>();
            for (String f : c.stringList("indexFiles")) {
                if (!f.isBlank()) index.add(Path.of(f));
            }
            for (String f : c.stringList("javaFiles")) s.files.add(new FileRef(Kind.JAVA, new File(f)));
            for (String f : c.stringList("kotlinFiles")) s.files.add(new FileRef(Kind.KOTLIN, new File(f)));
            for (String f : c.stringList("groovyFiles")) s.files.add(new FileRef(Kind.GROOVY, new File(f)));
            for (String f : c.stringList("scalaFiles")) s.files.add(new FileRef(Kind.SCALA, new File(f)));
            if (index.isEmpty()) {
                for (FileRef r : s.files) index.add(r.file.toPath());
            }
            s.indexFiles = List.copyOf(index);
            return s;
        }

        private static Set<File> jars(List<String> paths) {
            Set<File> out = new LinkedHashSet<>();
            for (String path : paths) {
                if (!path.isBlank()) out.add(new File(path));
            }
            return out;
        }
    }
}
