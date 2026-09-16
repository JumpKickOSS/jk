// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.MinimalToml;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The writer for {@code jk.toml}: surgical line edits that preserve formatting and comments, with
 * every result run back through {@link Toml#parse} by {@link #validated} before it is returned.
 *
 * <p>Scalars are quoted by {@link MinimalToml}, the one TOML encoder, so control characters are
 * escaped rather than rejected by {@code validated} as invalid TOML.
 */
public final class JkBuildEditor {

    /** Header line for the {@code [dependencies]} table (MAIN scope). */
    private static final Pattern DEPS_FLAT_HEADER = Pattern.compile("^\\s*\\[dependencies]\\s*$");

    /** Any TOML table header. */
    private static final Pattern ANY_HEADER = Pattern.compile("^\\s*\\[[^]]+]\\s*$");

    /** Header line for the {@code [workspace]} table. */
    private static final Pattern WORKSPACE_HEADER = Pattern.compile("^(\\s*)\\[workspace]\\s*$");

    /** The {@code modules = ...} assignment within {@code [workspace]}. */
    private static final Pattern MODULES_KEY = Pattern.compile("^\\s*modules\\s*=.*$");

    /**
     * A double-quoted string literal element, escapes included. The escape alternative is
     * load-bearing: the writer emits {@link MinimalToml#quote}d values, so a module path carrying a
     * quote is written as {@code "a\\"b"} — and a pattern that stops at the first {@code "} cannot
     * find its own output again, which made {@code removeWorkspaceModule} a silent no-op for it.
     */
    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * A dep entry line: {@code key = { ... }} or {@code key.workspace = true}. Captures key in group
     * 2.
     */
    private static final Pattern DEP_ENTRY =
            Pattern.compile("^(\\s*)([A-Za-z][A-Za-z0-9_-]*)(?:\\.[a-zA-Z][a-zA-Z0-9_-]*)?\\s*=.*$");

    private JkBuildEditor() {}

    /**
     * Append a dependency to the scope section (creates the section if missing). The entry is
     * spelled by {@link #renderDependencyEntry} against the bundled catalog.
     *
     * @param versionLiteral the selector as it should appear in the file: {@code "1.2.3"} pins,
     *     {@code "^1.2.3"} floats
     */
    public static String addDependency(
            String content, Scope scope, String name, String group, String artifact, String versionLiteral) {
        return addDependency(content, scope, name, group, artifact, versionLiteral, LibraryCatalog.bundled());
    }

    /** {@link #addDependency(String, Scope, String, String, String, String)} spelled against {@code catalog}. */
    public static String addDependency(
            String content,
            Scope scope,
            String name,
            String group,
            String artifact,
            String versionLiteral,
            LibraryCatalog catalog) {
        requireWritable(scope);
        validateName(name);
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        requireVersionLiteral(versionLiteral);
        if (artifact == null || artifact.isBlank()) artifact = name;

        List<String> lines = splitPreservingTerminator(content);
        if (findDepKey(lines, scope, name) >= 0) {
            throw new IllegalStateException(scope.tomlSection() + " already contains \"" + name + "\"");
        }
        return validated(
                join(insertEntry(lines, scope, renderDependencyEntry(catalog, name, group, artifact, versionLiteral))));
    }

    /** The plugin scope has no manifest table: its rows are the lock's own ({@link ManifestDeps#PLUGIN_TABLE_REFUSED}). */
    private static void requireWritable(Scope scope) {
        if (scope == Scope.PLUGIN) throw new IllegalArgumentException(ManifestDeps.PLUGIN_TABLE_REFUSED);
    }

    /**
     * One dependency entry line, in the one spelling jk writes for a plain Maven coordinate:
     *
     * <ol>
     *   <li>{@code name = "1.2.3"} when {@code catalog} maps {@code name} to exactly this
     *       group:artifact;
     *   <li>{@code name = "group:artifact:1.2.3"} otherwise.
     * </ol>
     *
     * An exact selector is written bare ({@code =1.2.3} becomes {@code 1.2.3}); {@code ^}, {@code ~},
     * ranges and {@code latest} are written as given. Entries that need more fields (sha256,
     * classifier, optional, features, git, path) are inline tables and are rendered by their own
     * writers.
     */
    public static String renderDependencyEntry(
            LibraryCatalog catalog, String name, String group, String artifact, String versionLiteral) {
        validateName(name);
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        requireVersionLiteral(versionLiteral);
        if (artifact == null || artifact.isBlank()) artifact = name;
        String literal = bareLiteral(versionLiteral);
        var hit = catalog.lookup(name);
        if (hit.isPresent()
                && hit.get().group().equals(group)
                && hit.get().artifact().equals(artifact)) {
            return name + " = " + MinimalToml.quote(literal);
        }
        return name + " = " + MinimalToml.quote(group + ":" + artifact + ":" + literal);
    }

    /** The selector as written to the file: an exact {@code =1.2.3} is spelled {@code 1.2.3}. */
    private static String bareLiteral(String versionLiteral) {
        String t = versionLiteral.strip();
        return t.startsWith("=") ? t.substring(1).strip() : t;
    }

    /** Insert {@code entryLine} flush at the bottom of the scope's table, creating the table at EOF when absent. */
    private static List<String> insertEntry(List<String> lines, Scope scope, String entryLine) {
        int header = findScopeHeader(lines, scope);
        if (header < 0) {
            ensureTrailingBlankLine(lines);
            lines.add("[" + scope.tomlSection() + "]");
            lines.add(entryLine);
            return lines;
        }
        int insertAt = endOfTable(lines, header);
        while (insertAt > header + 1 && lines.get(insertAt - 1).isBlank()) {
            insertAt--;
        }
        lines.add(insertAt, entryLine);
        return lines;
    }

    /**
     * Add a file-backed (CAS sha256) dependency entry to the scope's dependency section. The extra
     * field keeps this an inline table:
     *
     * <pre>{@code
     * library = { sha256 = "...", group = "...", version = "1.2.3" }
     * }</pre>
     *
     * {@code name} is omitted when {@code artifact} equals {@code library}; the version is written bare.
     */
    public static String addFileDependency(
            String content, Scope scope, String library, String group, String artifact, String version, String sha256) {
        requireWritable(scope);
        validateName(library);
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        requireVersionLiteral(version);
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("sha256 must not be blank");
        }
        if (artifact == null || artifact.isBlank()) artifact = library;

        List<String> lines = splitPreservingTerminator(content);
        if (findDepKey(lines, scope, library) >= 0) {
            throw new IllegalStateException(scope.tomlSection() + " already contains \"" + library + "\"");
        }

        StringBuilder sb = new StringBuilder(library)
                .append(" = { sha256 = ")
                .append(MinimalToml.quote(sha256))
                .append(", group = ")
                .append(MinimalToml.quote(group));
        if (!artifact.equals(library)) {
            sb.append(", name = ").append(MinimalToml.quote(artifact));
        }
        sb.append(", version = ")
                .append(MinimalToml.quote(bareLiteral(version)))
                .append(" }");
        return validated(join(insertEntry(lines, scope, sb.toString())));
    }

    /**
     * Remove a dependency by short {@code name} from its scope section (e.g. {@code [dependencies]}
     * for MAIN, {@code [test-dependencies]} for TEST). Leaves the
     * (possibly now-empty) sub-table in place — minimal blast radius on surrounding formatting.
     *
     * @throws IllegalStateException if the scope or name isn't present.
     */
    public static String removeDependency(String content, Scope scope, String name) {
        requireWritable(scope);
        validateName(name);
        List<String> lines = splitPreservingTerminator(content);
        int hit = findDepKey(lines, scope, name);
        if (hit < 0) {
            // Determine whether the scope section existed for a better error.
            if (findScopeHeader(lines, scope) < 0) {
                throw new IllegalStateException(scope.tomlSection() + " not found in jk.toml");
            }
            throw new IllegalStateException("\"" + name + "\" not found in " + scope.tomlSection());
        }
        lines.remove(hit);
        return validated(join(lines));
    }

    /**
     * Append {@code modulePath} to the root manifest's {@code [workspace].modules} array, preserving
     * the array's existing shape (single-line vs multi-line) and any surrounding comments.
     *
     * <p>Idempotent: if the path is already a module the content is returned unchanged. Used by
     * {@code jk new}/{@code jk init}/ {@code jk add <path>} to register a new module, the way {@code
     * cargo new} / {@code uv init} edit the workspace manifest.
     *
     * @throws IllegalStateException if there is no {@code [workspace]} table.
     */
    public static String addWorkspaceModule(String content, String modulePath) {
        return addWorkspaceModule(content, modulePath, false);
    }

    /**
     * Register a workspace module, <em>creating</em> the {@code [workspace]} table if the manifest
     * doesn't have one yet. This is how adding the first module promotes a plain single-project
     * {@code jk.toml} into a workspace root (Cargo/uv semantics). When a {@code [workspace]} table
     * already exists this is identical to {@link #addWorkspaceModule(String, String)}.
     */
    public static String registerWorkspaceModule(String content, String modulePath) {
        return addWorkspaceModule(content, modulePath, true);
    }

    private static String addWorkspaceModule(String content, String modulePath, boolean createTable) {
        if (modulePath == null || modulePath.isBlank()) {
            throw new IllegalArgumentException("module path must not be blank");
        }
        String path = modulePath.replace('\\', '/');

        List<String> lines = splitPreservingTerminator(content);
        int wsHeader = -1;
        String wsIndent = "";
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = WORKSPACE_HEADER.matcher(lines.get(i));
            if (m.matches()) {
                wsHeader = i;
                wsIndent = m.group(1);
                break;
            }
        }
        if (wsHeader < 0) {
            if (!createTable) {
                throw new IllegalStateException("no [workspace] table in jk.toml");
            }
            // Promote a plain project into a workspace: append a [workspace]
            // table with this module as its first entry.
            StringBuilder sb = new StringBuilder(content);
            if (!content.isEmpty() && !content.endsWith("\n")) sb.append('\n');
            sb.append("\n[workspace]\nmodules = [")
                    .append(MinimalToml.quote(path))
                    .append("]\n");
            return validated(sb.toString());
        }

        int end = endOfTable(lines, wsHeader);
        int modulesLine = -1;
        for (int i = wsHeader + 1; i < end; i++) {
            if (MODULES_KEY.matcher(lines.get(i)).matches()) {
                modulesLine = i;
                break;
            }
        }
        // No modules key yet — add one right under the header.
        if (modulesLine < 0) {
            lines.add(wsHeader + 1, wsIndent + "modules = [" + MinimalToml.quote(path) + "]");
            return validated(join(lines));
        }

        // Find the line carrying the array's closing ']'. String elements
        // never contain ']', so the first ']' at/after modulesLine closes it.
        int closeLine = -1;
        for (int i = modulesLine; i < end; i++) {
            if (lines.get(i).indexOf(']') >= 0) {
                closeLine = i;
                break;
            }
        }
        if (closeLine < 0) {
            throw new IllegalStateException("malformed modules array in [workspace]");
        }

        // Idempotency: collect existing elements across the array's lines. A glob that already
        // covers the new module counts — appending the literal beside `libs/*` would list it twice.
        StringBuilder arrayText = new StringBuilder();
        for (int i = modulesLine; i <= closeLine; i++)
            arrayText.append(lines.get(i)).append('\n');
        Matcher q = QUOTED.matcher(arrayText);
        List<String> existing = new ArrayList<>();
        while (q.find()) existing.add(decoded(q));
        if (WorkspaceModules.lists(existing, path)) return content; // already a module

        if (modulesLine == closeLine) {
            insertInlineModule(lines, closeLine, path);
        } else {
            insertMultilineModule(lines, modulesLine, closeLine, path);
        }
        return validated(join(lines));
    }

    /**
     * Remove {@code modulePath} from the root manifest's {@code [workspace].modules} array,
     * preserving the array's shape. Idempotent: a missing {@code [workspace]} table, missing
     * {@code modules} key, or absent element returns the content unchanged.
     */
    public static String removeWorkspaceModule(String content, String modulePath) {
        if (modulePath == null || modulePath.isBlank()) {
            throw new IllegalArgumentException("module path must not be blank");
        }
        String path = modulePath.replace('\\', '/');

        List<String> lines = splitPreservingTerminator(content);
        int wsHeader = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (WORKSPACE_HEADER.matcher(lines.get(i)).matches()) {
                wsHeader = i;
                break;
            }
        }
        if (wsHeader < 0) return content;

        int end = endOfTable(lines, wsHeader);
        int modulesLine = -1;
        for (int i = wsHeader + 1; i < end; i++) {
            if (MODULES_KEY.matcher(lines.get(i)).matches()) {
                modulesLine = i;
                break;
            }
        }
        if (modulesLine < 0) return content;

        int closeLine = -1;
        for (int i = modulesLine; i < end; i++) {
            if (lines.get(i).indexOf(']') >= 0) {
                closeLine = i;
                break;
            }
        }
        if (closeLine < 0) {
            throw new IllegalStateException("malformed modules array in [workspace]");
        }

        for (int i = modulesLine; i <= closeLine; i++) {
            String line = lines.get(i);
            Matcher q = QUOTED.matcher(line);
            while (q.find()) {
                if (!decoded(q).equals(path)) continue;
                // Cut the quoted element plus one adjacent comma (the following one when present,
                // else the preceding one) so the remaining array stays valid.
                String before = line.substring(0, q.start());
                String after = line.substring(q.end());
                int a = 0;
                while (a < after.length() && Character.isWhitespace(after.charAt(a))) a++;
                if (a < after.length() && after.charAt(a) == ',') {
                    after = after.substring(a + 1);
                    if (after.startsWith(" ")) after = after.substring(1);
                } else {
                    int b = before.length() - 1;
                    while (b >= 0 && Character.isWhitespace(before.charAt(b))) b--;
                    if (b >= 0 && before.charAt(b) == ',') before = before.substring(0, b);
                }
                String rewritten = before + after;
                if (rewritten.isBlank() && i != modulesLine) {
                    lines.remove(i); // element-only line of a multi-line array
                } else {
                    lines.set(i, rewritten);
                }
                return validated(join(lines));
            }
        }
        return content; // not a registered module — idempotent
    }

    /** Insert {@code "path"} before the {@code ]} on a single-line modules array. */
    private static void insertInlineModule(List<String> lines, int lineIdx, String path) {
        String line = lines.get(lineIdx);
        int close = line.lastIndexOf(']');
        int j = close - 1;
        while (j >= 0 && Character.isWhitespace(line.charAt(j))) j--;
        char prev = j >= 0 ? line.charAt(j) : '\0';
        String insertion =
                switch (prev) {
                    case '[' -> MinimalToml.quote(path); // empty array
                    case ',' -> " " + MinimalToml.quote(path); // trailing comma already present
                    default -> ", " + MinimalToml.quote(path);
                };
        lines.set(lineIdx, line.substring(0, close) + insertion + line.substring(close));
    }

    /** Insert a new element line just before the {@code ]} of a multi-line array. */
    private static void insertMultilineModule(List<String> lines, int modulesLine, int closeLine, String path) {
        // Ensure the last element line carries a trailing comma.
        for (int i = closeLine - 1; i > modulesLine - 1; i--) {
            String t = lines.get(i);
            String trimmed = t.stripTrailing();
            if (trimmed.isEmpty() || trimmed.stripLeading().startsWith("#")) continue;
            if (!trimmed.endsWith(",") && !trimmed.endsWith("[")) {
                lines.set(i, trimmed + ",");
            }
            break;
        }
        // Indent like the first element line if there is one, else 4 spaces.
        String indent = "    ";
        if (modulesLine + 1 < closeLine) {
            String el = lines.get(modulesLine + 1);
            indent = el.substring(0, el.length() - el.stripLeading().length());
        }
        lines.add(closeLine, indent + MinimalToml.quote(path) + ",");
    }

    // --- internals ---------------------------------------------------------

    /**
     * Locate the line that opens the section for {@code scope}. MAIN scope uses the {@code
     * [dependencies]} header; non-MAIN scopes use {@code [<canonical>-dependencies]}.
     */
    private static int findScopeHeader(List<String> lines, Scope scope) {
        String sectionName = scope.tomlSection();
        Pattern target;
        if (scope == Scope.MAIN) {
            target = DEPS_FLAT_HEADER;
        } else {
            // Build a pattern that matches exactly this scope's section header.
            target = Pattern.compile("^\\s*\\[" + Pattern.quote(sectionName) + "]\\s*$");
        }
        for (int i = 0; i < lines.size(); i++) {
            if (target.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    /** Find the line index of the dep entry named {@code name} in {@code scope}, or {@code -1}. */
    private static int findDepKey(List<String> lines, Scope scope, String name) {
        int header = findScopeHeader(lines, scope);
        if (header < 0) return -1;
        int end = endOfTable(lines, header);
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i);
            Matcher m = DEP_ENTRY.matcher(line);
            if (m.matches() && m.group(2).equals(name)) return i;
        }
        return -1;
    }

    /**
     * Return the index of the next top-level header after {@code headerLine}, or {@code
     * lines.size()}.
     */
    private static int endOfTable(List<String> lines, int headerLine) {
        for (int i = headerLine + 1; i < lines.size(); i++) {
            if (ANY_HEADER.matcher(lines.get(i)).matches()) return i;
        }
        return lines.size();
    }

    /**
     * The first TOML string literal (basic {@code "…"} or literal {@code '…'}) with its quotes,
     * capturing the opening quote in group 1 and the body in group 2.
     */
    private static final Pattern FIRST_STRING = Pattern.compile("(\")((?:[^\"\\\\]|\\\\.)*)\"|(')([^']*)'");

    /** The {@code version = "…"} pair inside an inline table; the literal is group 2. */
    private static final Pattern TABLE_VERSION =
            Pattern.compile("(\\bversion\\s*=\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*')");

    /**
     * Rewrite the version of the dependency {@code name} in {@code scope} in place, keeping the
     * entry's spelling: a catalog one-liner stays a one-liner, a {@code group:artifact:version}
     * string keeps its coordinate, an inline table keeps every other key and any trailing comment.
     *
     * @param versionLiteral the selector as it should appear in the file, e.g. {@code 1.2.3}
     * @throws IllegalStateException when the entry is absent, or carries no version to rewrite
     *     (path, git, workspace, or versionless coordinate)
     */
    public static String setDependencyVersion(String content, Scope scope, String name, String versionLiteral) {
        validateName(name);
        requireVersionLiteral(versionLiteral);
        List<String> lines = splitPreservingTerminator(content);
        int hit = findDepKey(lines, scope, name);
        if (hit < 0) {
            throw new IllegalStateException("\"" + name + "\" not found in " + scope.tomlSection());
        }
        lines.set(hit, rewriteVersion(lines.get(hit), scope.tomlSection() + "." + name, versionLiteral));
        return validated(join(lines));
    }

    /** {@link #setDependencyVersion} for a {@code [workspace.dependencies]} entry. */
    public static String setWorkspaceDependencyVersion(String content, String name, String versionLiteral) {
        validateName(name);
        requireVersionLiteral(versionLiteral);
        List<String> lines = splitPreservingTerminator(content);
        Pattern header = Pattern.compile("^\\s*\\[workspace\\.dependencies]\\s*$");
        int headerLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (header.matcher(lines.get(i)).matches()) {
                headerLine = i;
                break;
            }
        }
        if (headerLine < 0) {
            throw new IllegalStateException("[workspace.dependencies] not found in jk.toml");
        }
        int end = endOfTable(lines, headerLine);
        for (int i = headerLine + 1; i < end; i++) {
            Matcher m = DEP_ENTRY.matcher(lines.get(i));
            if (m.matches() && m.group(2).equals(name)) {
                lines.set(i, rewriteVersion(lines.get(i), "workspace.dependencies." + name, versionLiteral));
                return validated(join(lines));
            }
        }
        throw new IllegalStateException("\"" + name + "\" not found in [workspace.dependencies]");
    }

    private static void requireVersionLiteral(String versionLiteral) {
        if (versionLiteral == null || versionLiteral.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /**
     * One entry line with its version replaced. A string value is either a catalog selector
     * (replaced whole) or a coordinate (third field replaced); a table value has its {@code version}
     * pair replaced.
     */
    private static String rewriteVersion(String line, String displayPath, String versionLiteral) {
        int eq = line.indexOf('=');
        if (eq < 0) throw new IllegalStateException(displayPath + " is not a dependency entry");
        String before = line.substring(0, eq + 1);
        String after = line.substring(eq + 1);
        String rhs = after.stripLeading();
        if (rhs.startsWith("{")) {
            Matcher m = TABLE_VERSION.matcher(after);
            if (!m.find()) {
                throw new IllegalStateException(
                        displayPath + " has no `version` to rewrite (git, path, sha256 or platform-managed)");
            }
            return before
                    + after.substring(0, m.start(2))
                    + MinimalToml.quote(versionLiteral)
                    + after.substring(m.end(2));
        }
        Matcher m = FIRST_STRING.matcher(after);
        if (!m.find() || m.start() != after.length() - rhs.length()) {
            throw new IllegalStateException(displayPath + " has no version to rewrite");
        }
        String value = m.group(1) != null ? MinimalToml.unquote(m.group()) : m.group(4);
        String replacement;
        if (ManifestDeps.isPathShorthand(value) || ManifestDeps.isGitUrlShorthand(value)) {
            throw new IllegalStateException(displayPath + " is a path or git dependency; it has no version to rewrite");
        } else if (value.indexOf(':') >= 0) {
            String[] parts = value.split(":", 3);
            if (parts.length < 3) {
                throw new IllegalStateException(displayPath
                        + " is platform-managed (`"
                        + value
                        + "` has no version); the BOM under [platform-dependencies] owns it");
            }
            replacement = parts[0] + ":" + parts[1] + ":" + versionLiteral;
        } else {
            replacement = versionLiteral;
        }
        return before + after.substring(0, m.start()) + MinimalToml.quote(replacement) + after.substring(m.end());
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dependency name must not be blank");
        }
        // Bare-key character class per TOML: [A-Za-z0-9_-]+. We additionally
        // require a leading letter to keep the rendered TOML free of quoting.
        if (!name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("dependency name must match [A-Za-z][A-Za-z0-9_-]* (got: " + name + ")");
        }
    }

    /**
     * Set {@code [application].assembly} and {@code minified} surgically. Artifacts are additive,
     * so both keys are written independently; a minified build also carries {@code assembly = true}
     * because the R8 jar is built beside the fat one. Requires an existing {@code [application]}
     * table with {@code main}; other keys are left intact.
     */
    public static String setArtifacts(String content, boolean assembly, boolean minified) {
        String next = setApplicationFlag(content, "assembly", assembly || minified);
        return setApplicationFlag(next, "minified", minified);
    }

    /** Write {@code key = true} under {@code [application]}, or remove the key when false. */
    private static String setApplicationFlag(String content, String key, boolean value) {
        List<String> lines = splitPreservingTerminator(content);
        Pattern appHeader = Pattern.compile("^\\s*\\[application]\\s*$");
        Pattern flagKey = Pattern.compile("^(\\s*)" + Pattern.quote(key) + "\\s*=");
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (appHeader.matcher(lines.get(i)).matches()) {
                header = i;
                break;
            }
        }
        String assignment = value ? key + " = true" : null;
        if (header < 0) {
            if (assignment == null) return content; // nothing to remove
            throw new IllegalArgumentException("[application].main is required before setting " + key);
        }
        int end = endOfTable(lines, header);
        int existing = -1;
        for (int i = header + 1; i < end; i++) {
            if (flagKey.matcher(lines.get(i)).find()) {
                existing = i;
                break;
            }
        }
        if (assignment == null) {
            if (existing >= 0) lines.remove(existing);
            return validated(join(lines));
        }
        String line = assignment;
        if (existing >= 0) {
            Matcher m = flagKey.matcher(lines.get(existing));
            if (m.find()) line = m.group(1) + assignment;
            lines.set(existing, line);
        } else {
            int insertAt = end;
            while (insertAt > header + 1 && lines.get(insertAt - 1).isBlank()) insertAt--;
            lines.add(insertAt, line);
        }
        return validated(join(lines));
    }

    /**
     * Set a root-level scalar key, creating it when absent and replacing its value when present.
     *
     * <p>Insertion position is the whole point: a bare key written after a {@code [table]} header
     * lands <em>inside</em> that table, so a new key goes before the first header (or at the end of
     * a manifest that has none). {@code value} is already TOML-encoded — {@link MinimalToml#quote}
     * for a string, {@link String#valueOf} for a number or boolean — and the result is
     * {@link #validated}, so a caller cannot write a manifest jk will not read back.
     */
    public static String setRootScalar(String content, String key, String value) {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("root key must match [A-Za-z][A-Za-z0-9_-]* (got: " + key + ")");
        }
        String assignment = key + " = " + value;
        List<String> lines = splitPreservingTerminator(content);
        // Groups 1 and 2 keep the author's indent and their `=` column: a hand-aligned manifest
        // stays aligned across an edit.
        Pattern rootKey = Pattern.compile("^([ \\t]*)" + Pattern.quote(key) + "([ \\t]*)=");
        for (int i = 0; i < lines.size(); i++) {
            if (ANY_HEADER.matcher(lines.get(i)).matches()) break; // past the root section
            String line = lines.get(i);
            Matcher m = rootKey.matcher(line);
            if (m.find()) {
                String comment = trailingComment(line.substring(m.end()));
                lines.set(i, m.group(1) + key + m.group(2) + "= " + value + comment);
                return validated(join(lines));
            }
        }
        int firstHeader = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (ANY_HEADER.matcher(lines.get(i)).matches()) {
                firstHeader = i;
                break;
            }
        }
        while (firstHeader > 0 && lines.get(firstHeader - 1).isBlank()) firstHeader--;
        lines.add(firstHeader, assignment);
        return validated(join(lines));
    }

    /**
     * The {@code # …} comment trailing a value, with its leading whitespace, or {@code ""}. A
     * {@code #} inside a quoted value is part of the value, so quoting is tracked rather than
     * assumed away — this is a formatting-preserving editor and silently eating a user's note
     * would be the same class of loss as reflowing their file.
     */
    private static String trailingComment(String afterEquals) {
        boolean basic = false;
        boolean literal = false;
        for (int i = 0; i < afterEquals.length(); i++) {
            char c = afterEquals.charAt(i);
            if (c == '\\' && basic) {
                i++; // an escaped character cannot close the string
            } else if (c == '"' && !literal) {
                basic = !basic;
            } else if (c == '\'' && !basic) {
                literal = !literal;
            } else if (c == '#' && !basic && !literal) {
                return "  " + afterEquals.substring(i).strip();
            }
        }
        return "";
    }

    /** The element a {@link #QUOTED} match names, decoded by the one TOML codec. */
    private static String decoded(Matcher quotedElement) {
        return MinimalToml.unquote(quotedElement.group());
    }

    private static String validated(String text) {
        TomlParseResult result = Toml.parse(text);
        if (result.hasErrors()) {
            throw new IllegalStateException(
                    "edit produced invalid TOML: " + result.errors().getFirst().getMessage());
        }
        return text;
    }

    private static List<String> splitPreservingTerminator(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private static void ensureTrailingBlankLine(List<String> lines) {
        if (lines.isEmpty()) return;
        if (!lines.getLast().isBlank()) {
            lines.add("");
        }
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
