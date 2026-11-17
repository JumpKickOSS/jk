// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The extractor for each file kind a JVM or front-end project carries, and the shell scripts,
 * Dockerfiles, Makefiles and {@code .env} files around it. Anything else (binaries, media, languages outside the JVM and the web) has none and
 * stays out of the graph.
 */
public final class Extractors {

    private Extractors() {}

    private static final Map<String, Extractor> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", JvmSourceScanner::extract),
            Map.entry("kt", JvmSourceScanner::extract),
            Map.entry("kts", JvmSourceScanner::extract),
            Map.entry("groovy", JvmSourceScanner::extract),
            Map.entry("gradle", JvmSourceScanner::extract),
            Map.entry("js", ScriptScanner::extract),
            Map.entry("jsx", ScriptScanner::extract),
            Map.entry("mjs", ScriptScanner::extract),
            Map.entry("cjs", ScriptScanner::extract),
            Map.entry("ts", ScriptScanner::extract),
            Map.entry("tsx", ScriptScanner::extract),
            Map.entry("mts", ScriptScanner::extract),
            Map.entry("cts", ScriptScanner::extract),
            Map.entry("vue", ScriptScanner::extract),
            Map.entry("md", reads(DocExtractors::markdown)),
            Map.entry("markdown", reads(DocExtractors::markdown)),
            Map.entry("mdx", reads(DocExtractors::markdown)),
            Map.entry("adoc", reads(DocExtractors::asciidoc)),
            Map.entry("asciidoc", reads(DocExtractors::asciidoc)),
            Map.entry("asc", reads(DocExtractors::asciidoc)),
            Map.entry("ad", reads(DocExtractors::asciidoc)),
            Map.entry("json", reads(ConfigExtractors::json)),
            Map.entry("jsonc", reads(ConfigExtractors::json)),
            Map.entry("yml", reads(ConfigExtractors::yaml)),
            Map.entry("yaml", reads(ConfigExtractors::yaml)),
            Map.entry("properties", reads(ConfigExtractors::properties)),
            Map.entry("toml", reads(ConfigExtractors::toml)),
            Map.entry("ini", reads(ConfigExtractors::toml)),
            Map.entry("cfg", reads(ConfigExtractors::toml)),
            Map.entry("csv", ConfigExtractors::csv),
            Map.entry("xml", MarkupExtractors::xml),
            Map.entry("xsd", MarkupExtractors::xml),
            Map.entry("html", MarkupExtractors::html),
            Map.entry("htm", MarkupExtractors::html),
            Map.entry("css", MarkupExtractors::css),
            Map.entry("scss", MarkupExtractors::css),
            Map.entry("sass", MarkupExtractors::css),
            Map.entry("less", MarkupExtractors::css),
            Map.entry("dot", MarkupExtractors::diagram),
            Map.entry("gv", MarkupExtractors::diagram),
            Map.entry("mmd", MarkupExtractors::diagram),
            Map.entry("mermaid", MarkupExtractors::diagram),
            Map.entry("puml", MarkupExtractors::diagram),
            Map.entry("sql", MarkupExtractors::sql),
            Map.entry("sh", reads(ShellScripts::extract)),
            Map.entry("bash", reads(ShellScripts::extract)));

    private static final Extractor DOCKERFILE = reads(EnvVars::dockerfile);
    private static final Extractor MAKEFILE = reads(Makefiles::extract);

    /** Files under {@code META-INF/} named by their contents' contract, not an extension. */
    private static final Set<String> CLASS_LISTS = Set.of("spring.factories");

    /** The extractor for {@code relPath}, {@code null} for a file the graph ignores. */
    public static @Nullable Extractor forPath(String relPath) {
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        if (relPath.contains("META-INF/services/") || relPath.endsWith(".imports") || CLASS_LISTS.contains(name)) {
            return ConfigExtractors::classList;
        }
        if (EnvVars.isDotEnv(name)) return EnvVars::dotEnv;
        if (EnvVars.isDockerfile(name)) return DOCKERFILE;
        if (Makefiles.isMakefile(name)) return MAKEFILE;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        return BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * True for a file whose extractor reads command lines ({@link CommandLines}): a script, a
     * Dockerfile, a Makefile, a YAML, TOML, properties or JSON file ({@code package.json}'s scripts) and
     * a Markdown or AsciiDoc page. Its fragment depends on the programs the graph defines; no other
     * file's does.
     */
    public static boolean readsCommandLines(String relPath) {
        return forPath(relPath) instanceof CommandLineReader;
    }

    /**
     * {@code read}, a script's fragment from a reader other than {@link ScriptScanner} (oxc), with the
     * HTTP requests its text makes ({@link Requests}): the route uses do not depend on which reader
     * parsed the declarations.
     */
    public static Fragment withRequests(Fragment read, String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", null);
        Requests.calls(b, b.fileId(), text, new Lines(text));
        List<Mention> found = b.build().mentions();
        if (found.isEmpty()) return read;
        List<Mention> all = new ArrayList<>(read.mentions());
        all.addAll(found);
        return new Fragment(read.nodes(), read.edges(), all);
    }

    /** An extractor that reads command lines: given the programs the graph defines, their uses. */
    @FunctionalInterface
    interface Reader {
        Fragment extract(String relPath, String text, Set<String> programs);
    }

    private static Extractor reads(Reader reader) {
        return new CommandLineReader(reader);
    }

    /** {@code reader} as an {@link Extractor}: read alone, no command line, as for a project with no program. */
    private record CommandLineReader(Reader reader) implements Extractor {
        @Override
        public Fragment extract(String relPath, String text) {
            return reader.extract(relPath, text, Set.of());
        }

        @Override
        public Fragment extract(String relPath, String text, Set<String> programs) {
            return reader.extract(relPath, text, programs);
        }
    }

    /** True for the JVM source kinds whose compiled classes also feed the graph. */
    public static boolean isJvmSource(String relPath) {
        String lower = relPath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".groovy");
    }
}
