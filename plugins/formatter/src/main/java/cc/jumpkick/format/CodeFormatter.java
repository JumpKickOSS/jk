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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

        TypeIndex index = spec.optimizeImports ? TypeIndex.scan(spec.indexFiles) : null;

        Formatter javaFmt = spec.javaJars.isEmpty()
                ? null
                : Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(javaSteps(spec))
                        .build();

        Formatter kotlinFmt = spec.kotlinJars.isEmpty()
                ? null
                : Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(List.of(KtfmtStep.create(
                                spec.kotlinVersion,
                                provisioner(spec.kotlinJars),
                                ktfmtStyle(spec.kotlinStyle),
                                ktfmtOptions(spec.kotlinMaxWidth))))
                        .build();

        boolean anyGroovy = spec.files.stream().anyMatch(r -> r.kind == Kind.GROOVY);
        Formatter groovyFmt = anyGroovy
                ? Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(groovySteps())
                        .build()
                : null;

        Formatter scalaFmt = spec.scalaJars.isEmpty()
                ? null
                : Formatter.builder()
                        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                        .encoding(StandardCharsets.UTF_8)
                        .steps(scalaSteps(spec))
                        .build();

        int changed = 0, errors = 0;
        try {
            for (FileRef ref : spec.files) {
                Formatter fmt = formatterFor(ref.kind, javaFmt, kotlinFmt, groovyFmt, scalaFmt);
                if (fmt == null) continue;
                try {
                    byte[] originalBytes = Files.readAllBytes(ref.file.toPath());
                    String stampKey = stampCache != null ? stampCache.keyFor(originalBytes) : null;
                    if (stampKey != null && stampCache.contains(stampKey)) {
                        emitFile(out, ref.file, "clean", null);
                        continue;
                    }

                    if (ref.kind == Kind.JAVA && isUnnamedClass(originalBytes)) {
                        emitFile(out, ref.file, "skipped", null);
                        if (stampKey != null) stampCache.record(stampKey);
                        continue;
                    }

                    boolean shortened = false;
                    if (index != null) {
                        String src = new String(originalBytes, StandardCharsets.UTF_8);
                        FqcnShortener.Result r = FqcnShortener.shorten(src, index, syntax(ref.kind));
                        if (r.changed() && spec.apply) {
                            Files.writeString(ref.file.toPath(), r.source(), StandardCharsets.UTF_8);
                            shortened = true;
                        } else if (r.changed()) {
                            shortened = true;
                        }
                    }

                    DirtyState state = DirtyState.of(fmt, ref.file);
                    boolean spotlessChanged = !state.isClean() && !state.didNotConverge();

                    if (state.didNotConverge()) {
                        errors++;
                        emitFile(out, ref.file, "error", "formatter did not converge");
                    } else if (shortened || spotlessChanged) {
                        changed++;
                        if (spec.apply && spotlessChanged) state.writeCanonicalTo(ref.file);
                        emitFile(out, ref.file, "changed", null);
                        if (spec.apply && stampCache != null) {
                            byte[] finalBytes = Files.readAllBytes(ref.file.toPath());
                            String finalKey = stampCache.keyFor(finalBytes);
                            if (finalKey != null) stampCache.record(finalKey);
                        }
                    } else {
                        emitFile(out, ref.file, "clean", null);
                        if (stampKey != null) stampCache.record(stampKey);
                    }
                } catch (Exception e) {
                    errors++;
                    emitFile(out, ref.file, "error", e.getMessage());
                }
            }
        } finally {
            if (javaFmt != null) javaFmt.close();
            if (kotlinFmt != null) kotlinFmt.close();
            if (groovyFmt != null) groovyFmt.close();
            if (scalaFmt != null) scalaFmt.close();
        }

        int exit = errors > 0 || (!spec.apply && changed > 0) ? 1 : 0;
        return exit;
    }

    private static Formatter formatterFor(
            Kind kind, Formatter javaFmt, Formatter kotlinFmt, Formatter groovyFmt, Formatter scalaFmt) {
        return switch (kind) {
            case JAVA -> javaFmt;
            case KOTLIN -> kotlinFmt;
            case GROOVY -> groovyFmt;
            case SCALA -> scalaFmt;
        };
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

    private record FileRef(Kind kind, File file) {}

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
