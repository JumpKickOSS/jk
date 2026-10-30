// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The extractor for each file kind a JVM or front-end project carries. Anything else (binaries,
 * media, languages outside the JVM and the web) has none and stays out of the graph.
 */
public final class Extractors {

    private Extractors() {}

    /**
     * Bumped whenever any extractor's output changes, so cached fragments from an older extractor are
     * not reused.
     */
    public static final String VERSION = "10";

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
            Map.entry("md", DocExtractors::markdown),
            Map.entry("markdown", DocExtractors::markdown),
            Map.entry("mdx", DocExtractors::markdown),
            Map.entry("adoc", DocExtractors::asciidoc),
            Map.entry("asciidoc", DocExtractors::asciidoc),
            Map.entry("asc", DocExtractors::asciidoc),
            Map.entry("ad", DocExtractors::asciidoc),
            Map.entry("json", ConfigExtractors::json),
            Map.entry("jsonc", ConfigExtractors::json),
            Map.entry("yml", ConfigExtractors::yaml),
            Map.entry("yaml", ConfigExtractors::yaml),
            Map.entry("properties", ConfigExtractors::properties),
            Map.entry("toml", ConfigExtractors::toml),
            Map.entry("ini", ConfigExtractors::toml),
            Map.entry("cfg", ConfigExtractors::toml),
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
            Map.entry("sql", MarkupExtractors::sql));

    /** Files under {@code META-INF/} named by their contents' contract, not an extension. */
    private static final Set<String> CLASS_LISTS = Set.of("spring.factories");

    /** The extractor for {@code relPath}, {@code null} for a file the graph ignores. */
    public static @Nullable Extractor forPath(String relPath) {
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        if (relPath.contains("META-INF/services/") || relPath.endsWith(".imports") || CLASS_LISTS.contains(name)) {
            return ConfigExtractors::classList;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        return BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** True for the JVM source kinds whose compiled classes also feed the graph. */
    public static boolean isJvmSource(String relPath) {
        String lower = relPath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".groovy");
    }
}
