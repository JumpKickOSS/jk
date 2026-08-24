// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

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
import com.diffplug.spotless.java.GoogleJavaFormatStep;
import com.diffplug.spotless.java.ImportOrderStep;
import com.diffplug.spotless.java.PalantirJavaFormatStep;
import com.diffplug.spotless.java.RemoveUnusedImportsStep;
import com.diffplug.spotless.kotlin.KtfmtStep;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.style.ImportLayoutStyle;
import org.openrewrite.style.NamedStyles;

/**
 * {@code jk-formatter} plugin: optional OpenRewrite import pass, then Spotless. Host forks with a
 * tab-delimited spec file; emits {@code ##JKFMT:} JSONL per file + summary.
 *
 * <p>Java Spotless pipeline (always on): {@code importOrder} → {@code removeUnusedImports} →
 * Palantir / Google / AOSP style. Matches the usual Spotless recipe; FQCN shortening is the
 * separate OpenRewrite {@code optimizeImports} pass, which resolves names against the compile
 * classpath the host sends ({@code cp} lines, compile role) and can shorten nothing without one.
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
            return 2;
        }
        Spec spec = Spec.from(PluginSpec.read(Path.of(args.get(0))));

        // Per-file stamp cache — skips unchanged files without running the formatter. The host
        // computed and sent the config digest (its FormatKey); no key without it.
        FormatStampCache stampCache = spec.cacheDir != null && spec.configKey != null
                ? new FormatStampCache(spec.cacheDir.resolve("format-stamps"), spec.configKey)
                : null;

        // Build the OpenRewrite recipe once (null when no rewrite is requested).
        Recipe rewriteRecipe = spec.hasRewrite() ? buildRecipe(spec) : null;

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

        int changed = 0, clean = 0, errors = 0;
        try {
            for (FileRef ref : spec.files) {
                Formatter fmt = ref.kotlin ? kotlinFmt : javaFmt;
                if (fmt == null) continue; // no formatter for this language (shouldn't happen)
                try {
                    // Read once: stamp key, unnamed-class probe, and the formatter on a miss.
                    byte[] originalBytes = Files.readAllBytes(ref.file.toPath());
                    String stampKey = stampCache != null ? stampCache.keyFor(originalBytes) : null;

                    if (stampKey != null && stampCache.contains(stampKey)) {
                        clean++;
                        emitFile(out, ref.file, "clean", null);
                        continue;
                    }

                    // Java 21+ unnamed classes (no type declaration) can't be parsed by
                    // palantir/google-java-format — skip them silently (after the stamp miss).
                    if (!ref.kotlin && isUnnamedClass(originalBytes)) {
                        clean++;
                        emitFile(out, ref.file, "skipped", null);
                        if (stampKey != null) stampCache.record(stampKey);
                        continue;
                    }

                    // --- OpenRewrite pass (Java only) --------------------------------
                    boolean rewriteChanged = false;
                    if (!ref.kotlin && rewriteRecipe != null) {
                        rewriteChanged = applyRewrite(rewriteRecipe, ref.file, spec.apply, spec.compileClasspath);
                    }

                    // --- Spotless pass -----------------------------------------------
                    DirtyState state = DirtyState.of(fmt, ref.file);
                    boolean spotlessChanged = !state.isClean() && !state.didNotConverge();

                    if (state.didNotConverge()) {
                        errors++;
                        emitFile(out, ref.file, "error", "formatter did not converge");
                    } else if (rewriteChanged || spotlessChanged) {
                        changed++;
                        if (spec.apply && spotlessChanged) state.writeCanonicalTo(ref.file);
                        emitFile(out, ref.file, "changed", null);
                        // In apply mode: stamp the newly formatted content so next run
                        // skips it. (In check mode the file wasn't written, so no stamp.)
                        if (spec.apply && stampCache != null) {
                            byte[] finalBytes = Files.readAllBytes(ref.file.toPath());
                            String finalKey = stampCache.keyFor(finalBytes);
                            if (finalKey != null) stampCache.record(finalKey);
                        }
                    } else {
                        // File is already clean — stamp current content to skip next time.
                        clean++;
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
        }

        // In --check mode, an unformatted (changed) file is a failure; errors always are. The engine
        // recomputes the changed/clean/error tallies from the per-file events, so `done` carries only exit.
        int exit = errors > 0 || (!spec.apply && changed > 0) ? 1 : 0;
        return exit;
    }

    // -------------------------------------------------------------------------
    // OpenRewrite helpers
    // -------------------------------------------------------------------------

    /**
     * Assemble the composite OpenRewrite recipe from flags + optional YAML config. Flag-driven
     * built-ins come first; the config file layers additional recipes on top. Returns a single Recipe
     * that represents all active transformations, or {@code null} if nothing is active.
     */
    private static Recipe buildRecipe(Spec spec) throws IOException {
        List<Recipe> recipes = new ArrayList<>();
        if (spec.optimizeImports) recipes.add(new ShortenFullyQualifiedTypeReferences());

        if (spec.rewriteConfigFile != null) {
            try (InputStream is = new FileInputStream(spec.rewriteConfigFile)) {
                Environment env = Environment.builder()
                        .load(new YamlResourceLoader(is, spec.rewriteConfigFile.toURI(), new Properties()))
                        .build();
                env.listRecipes().forEach(d -> recipes.add(env.activateRecipes(d.getName())));
            }
        }
        if (recipes.isEmpty()) return null;
        return recipes.size() == 1 ? recipes.get(0) : new CompositeRecipe(recipes);
    }

    /**
     * Run the recipe against a single Java file. In apply mode the file is written back if the recipe
     * produced changes. Returns whether the file was (or would be) changed.
     */
    /**
     * Import layout with the star-collapse thresholds effectively disabled. OpenRewrite's default
     * layout folds a package to {@code .*} at five imports, so the FQCN-shorten pass silently
     * rewrote explicit imports into wildcards once it pushed a package over the threshold
     *. jk's style is single-type imports, always.
     */
    private static final List<NamedStyles> NO_STAR_IMPORTS = List.of(new NamedStyles(
            org.openrewrite.Tree.randomId(),
            "cc.jumpkick.format.NoStarImports",
            "jk import layout",
            "Never collapse imports to wildcards",
            Set.of(),
            List.of(ImportLayoutStyle.builder()
                    .classCountToUseStarImport(Integer.MAX_VALUE)
                    .nameCountToUseStarImport(Integer.MAX_VALUE)
                    .importAllOthers()
                    .blankLine()
                    .importStaticAllOthers()
                    .build())));

    // Package-private: CodeFormatterRewriteTest drives it directly, so the classpath's effect on
    // shortening is asserted without resolving the Spotless formatter jars.
    static boolean applyRewrite(Recipe recipe, File file, boolean apply, List<Path> classpath) throws IOException {
        ExecutionContext ctx = new InMemoryExecutionContext(e -> {});
        List<SourceFile> parsed;
        try {
            JavaParser.Builder<?, ?> parser = JavaParser.fromJavaVersion()
                    .logCompilationWarningsAndErrors(false)
                    .styles(NO_STAR_IMPORTS);
            // Type attribution is the whole feature: ShortenFullyQualifiedTypeReferences rewrites a
            // field access only when it resolves to a top-level class, so with no classpath every
            // name outside java.* stays fully qualified and the pass reports success having done
            // nothing. Left unset (not set empty) when the host sent none, so javac keeps its own
            // default rather than being told the classpath is empty.
            if (!classpath.isEmpty()) parser = parser.classpath(classpath);
            parsed = parser.build()
                    .parse(List.of(file.toPath()), file.toPath().getParent(), ctx)
                    .toList();
        } catch (Exception e) {
            // Unparseable file (unnamed class, syntax error, …) — skip silently.
            return false;
        }
        if (parsed.isEmpty()) return false;

        RecipeRun run = recipe.run(new InMemoryLargeSourceSet(parsed), ctx);
        List<Result> results = run.getChangeset().getAllResults();
        if (results.isEmpty()) return false;

        if (apply) {
            for (Result result : results) {
                if (result.getAfter() != null) {
                    Files.writeString(file.toPath(), result.getAfter().printAll(), StandardCharsets.UTF_8);
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Spotless helpers
    // -------------------------------------------------------------------------

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
            // Empty groups = Spotless default: static imports, then everything else
            // (alphabetical within each block, blank line between).
            steps.add(ImportOrderStep.forJava().createFrom());
        }
        if (spec.removeUnusedImports) {
            Set<File> removeUnusedJars = spec.removeUnusedJars.isEmpty() ? spec.javaJars : spec.removeUnusedJars;
            steps.add(RemoveUnusedImportsStep.create(provisioner(removeUnusedJars)));
        }
        steps.add(styleStep(spec, styleProv));
        return List.copyOf(steps);
    }

    /**
     * The style step: {@code palantir} → palantir-java-format (PALANTIR); {@code google}/{@code
     * aosp} → google-java-format (GOOGLE/AOSP). The version is whatever jk resolved and put in the
     * spec.
     */
    private static FormatterStep styleStep(Spec spec, Provisioner prov) {
        if ("palantir".equalsIgnoreCase(spec.javaStyle)) {
            return PalantirJavaFormatStep.create(spec.javaVersion, "PALANTIR", /* formatJavadoc */ false, prov);
        }
        return GoogleJavaFormatStep.create(spec.javaVersion, spec.javaStyle.toUpperCase(Locale.ROOT), prov);
    }

    /** A classpath Provisioner: hands Spotless the pre-resolved jars jk passed in. */
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

    // Matches any top-level type declaration (class/interface/enum/record/@interface).
    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record)\\s+\\w|@interface\\s+\\w");

    /** True if the source has no top-level type declaration (Java 21+ unnamed class). */
    static boolean isUnnamedClass(byte[] bytes) {
        String src = new String(bytes, StandardCharsets.UTF_8);
        // Strip line and block comments before checking for type declarations.
        String stripped = src.replaceAll("//[^\n]*", "").replaceAll("(?s)/\\*.*?\\*/", " ");
        return !TYPE_DECL.matcher(stripped).find();
    }

    private record FileRef(boolean kotlin, File file) {}

    /** Parsed spec: modes, per-language style/version/jars, rewrite flags, and the file list. */
    static final class Spec {
        boolean apply = true;
        String javaStyle = "palantir";
        String javaVersion = PalantirJavaFormatStep.defaultVersion();
        Set<File> javaJars = new LinkedHashSet<>();
        /**
         * Classpath for {@link RemoveUnusedImportsStep} (google-java-format). Empty means reuse
         * {@link #javaJars} (already GJF when style is google/aosp).
         */
        Set<File> removeUnusedJars = new LinkedHashSet<>();

        String kotlinStyle = "kotlinlang";
        String kotlinVersion = KtfmtStep.defaultVersion();
        int kotlinMaxWidth = 0;
        Set<File> kotlinJars = new LinkedHashSet<>();
        // OpenRewrite fields
        boolean optimizeImports = false;
        // Spotless import hygiene (defaults on — host always sends explicit values)
        boolean importOrder = true;
        boolean removeUnusedImports = true;
        File rewriteConfigFile = null;
        /**
         * The project's compile classpath, as {@code cp} lines with the compile role. OpenRewrite
         * resolves type names against it; empty means only the JDK's own types can be shortened.
         */
        List<Path> compileClasspath = List.of();
        // Stamp cache: null when the host didn't pass a cache-dir (no caching).
        Path cacheDir = null;
        /** The host's FormatKey digest — the single owner of what this run is keyed by. */
        String configKey = null;

        final List<FileRef> files = new ArrayList<>();

        boolean hasRewrite() {
            return optimizeImports || rewriteConfigFile != null;
        }

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
            s.optimizeImports = c.bool("optimizeImports", false);
            s.compileClasspath = List.copyOf(ws.compileClasspath());
            s.importOrder = c.bool("importOrder", true);
            s.removeUnusedImports = c.bool("removeUnusedImports", true);
            c.stringOpt("rewriteConfigFile").ifPresent(p -> s.rewriteConfigFile = new File(p));
            c.stringOpt("cacheDir").ifPresent(p -> s.cacheDir = Path.of(p));
            c.stringOpt("configKey").ifPresent(k -> s.configKey = k);
            for (String f : c.stringList("javaFiles")) s.files.add(new FileRef(false, new File(f)));
            for (String f : c.stringList("kotlinFiles")) s.files.add(new FileRef(true, new File(f)));
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
