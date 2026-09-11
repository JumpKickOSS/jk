// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal parse of {@code jk ide --print-model} JSON (IdeWireModel encode shape). Intentionally
 * dependency-free so the plugin stays wire-only and Java-17-safe.
 */
public final class JkWireModel {

    public final @Nullable String error;
    public final @NotNull String wsRoot;
    public final @NotNull String rootName;
    public final boolean workspace;
    public final @NotNull List<String> moduleDirs;
    public final @NotNull List<String> names;
    public final @NotNull List<String> javaReleases;
    public final @NotNull List<String> mainClasses;
    public final @NotNull List<String> libJars;
    public final @NotNull List<String> libSources;

    private JkWireModel(
            @Nullable String error,
            @Nullable String wsRoot,
            @Nullable String rootName,
            boolean workspace,
            List<String> moduleDirs,
            List<String> names,
            List<String> javaReleases,
            List<String> mainClasses,
            List<String> libJars,
            List<String> libSources) {
        this.error = error;
        this.wsRoot = wsRoot == null ? "" : wsRoot;
        this.rootName = rootName == null ? "" : rootName;
        this.workspace = workspace;
        this.moduleDirs = moduleDirs;
        this.names = names;
        this.javaReleases = javaReleases;
        this.mainClasses = mainClasses;
        this.libJars = libJars;
        this.libSources = libSources;
    }

    public int moduleCount() {
        return moduleDirs.size();
    }

    private static final Pattern WORKSPACE_TRUE = Pattern.compile("\"workspace\"\\s*:\\s*true");

    public static @NotNull JkWireModel parse(@NotNull String json) {
        // Take the last complete-looking JSON object if chrome leaked onto stdout.
        String body = extractJsonObject(json);
        String error = strField(body, "error");
        if ("null".equals(error)) error = null;
        // The same whitespace tolerance the string fields get; two literal spellings missed `"workspace" : true`.
        boolean workspace = WORKSPACE_TRUE.matcher(body).find();
        return new JkWireModel(
                error,
                strField(body, "wsRoot"),
                strField(body, "rootName"),
                workspace,
                strArray(body, "moduleDirs"),
                strArray(body, "names"),
                strArray(body, "javaReleases"),
                strArray(body, "mainClasses"),
                strArray(body, "libJars"),
                strArray(body, "libSources"));
    }

    private static String extractJsonObject(String raw) {
        int start = raw.lastIndexOf("{\"type\"");
        if (start < 0) start = raw.indexOf('{');
        if (start < 0) return raw;
        return raw.substring(start).trim();
    }

    private static @Nullable String strField(String json, String field) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|null)");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        String v = m.group(1);
        if ("null".equals(v)) return null;
        return unquote(v);
    }

    private static List<String> strArray(String json, String field) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) return List.of();
        String inner = m.group(1).trim();
        if (inner.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher sm = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(inner);
        while (sm.find()) {
            out.add(unquote("\"" + sm.group(1) + "\""));
        }
        return Collections.unmodifiableList(out);
    }

    private static String unquote(String quoted) {
        if (quoted == null || quoted.length() < 2) return "";
        String s = quoted.substring(1, quoted.length() - 1);
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }
}
