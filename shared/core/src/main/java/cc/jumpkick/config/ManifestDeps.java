// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.Workspace.WorkspaceDependency;
import cc.jumpkick.util.GitUrl;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * Dependency-scope tables, git/path sources, and workspace dep materialization.
 */
@NullMarked
public final class ManifestDeps {

    private ManifestDeps() {}

    static JkBuild.Dependencies parseDependencies(
            TomlTable root, @Nullable Workspace workspace, LibraryCatalog catalog) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);

        // [dependencies] → MAIN scope (all entries are flat deps, no sub-tables)
        TomlTable mainDeps = root.getTable("dependencies");
        if (mainDeps != null) {
            List<Dependency> parsed =
                    parseScopeTable(mainDeps, new ArrayList<>(mainDeps.keySet()), Scope.MAIN, workspace, catalog);
            if (!parsed.isEmpty()) byScope.put(Scope.MAIN, parsed);
        }

        // Top-level scope tables: [test-dependencies], [provided-dependencies], etc.
        addScopeDeps(byScope, root, Scope.TEST, workspace, catalog);
        addScopeDeps(byScope, root, Scope.PROVIDED, workspace, catalog);
        addScopeDeps(byScope, root, Scope.PROCESSOR, workspace, catalog);
        addScopeDeps(byScope, root, Scope.EXPORT, workspace, catalog);
        addScopeDeps(byScope, root, Scope.RUNTIME, workspace, catalog);
        // [platform-dependencies] — BOM imports (version constraints, not classpath entries).
        addScopeDeps(byScope, root, Scope.PLATFORM, workspace, catalog);
        // DEV / TEST_DEV: run-time only / run+test, never packaged.
        addScopeDeps(byScope, root, Scope.DEV, workspace, catalog);
        addScopeDeps(byScope, root, Scope.TEST_DEV, workspace, catalog);

        return new JkBuild.Dependencies(byScope);
    }

    static void addScopeDeps(
            EnumMap<Scope, List<Dependency>> byScope,
            TomlTable root,
            Scope scope,
            @Nullable Workspace workspace,
            LibraryCatalog catalog) {
        TomlTable table = root.getTable(scope.tomlSection());
        if (table == null) return;
        List<Dependency> parsed = parseScopeTable(table, new ArrayList<>(table.keySet()), scope, workspace, catalog);
        if (!parsed.isEmpty()) byScope.put(scope, parsed);
    }

    static List<Dependency> parseScopeTable(
            TomlTable scopeTable,
            List<String> keys,
            Scope scope,
            @Nullable Workspace workspace,
            LibraryCatalog catalog) {
        List<Dependency> result = new ArrayList<>(keys.size());
        for (String name : keys) {
            Object value = scopeTable.get(List.of(name));
            if (value instanceof String versionShorthand) {
                // Cargo-style one-liner: `name = "1.0.0"`. Resolve the
                // coord via the bundled catalog; the user provides only
                // the version selector.
                result.add(parseShorthandEntry(name, versionShorthand, scope, catalog));
                continue;
            }
            if (!(value instanceof TomlTable entry)) {
                throw new JkBuildParseException(scope.tomlSection()
                        + "."
                        + name
                        + " must be an inline table (e.g. { group = \"...\", version = \"...\" })"
                        + " or a version-string shorthand for a catalog-known name");
            }
            result.add(parseDepEntry(name, entry, scope, workspace, catalog));
        }
        return result;
    }

    /**
     * Resolve a {@code name = "value"} string shorthand. Two forms are recognised:
     *
     * <ul>
     * <li>Git URL — value starts with {@code git://} or {@code https://}: a git dependency with
     * URL-embedded ref/subdir parsing. When no ref is embedded, {@code branch = "main"} is
     * implied.
     * <li>Version spec — anything else: looked up in the bundled catalog by {@code name} and
     * treated as a floating version selector (the Cargo-style {@code name = "1.2.3"} form).
     * </ul>
     *
     * <p>A leading {@code .} or {@code /} is a local-path shorthand — a consume-only path dependency
     * ({@link Dependency#pathByName}), built compile/package-only. A local sibling that should be
     * built fully (with tests) belongs in {@code [workspace] modules} instead.
     */
    static Dependency parseShorthandEntry(String name, String value, Scope scope, LibraryCatalog catalog) {
        String displayPath = scope.tomlSection() + "." + name;
        if (value.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty value string");
        }

        // Local-path shorthand: a relative (`./x`, `../x`) or absolute (`/x`) path is a consume-only
        // path dependency. `isVersionSpecOrKeyword` already excludes `.`/`/`-leading strings, so this
        // never shadows a version spec.
        if (value.startsWith(".") || value.startsWith("/")) {
            return Dependency.pathByName(name, new PathSource(value));
        }

        // Git URL shorthand: starts with "git://" or "https://".
        if (value.startsWith("git://") || value.startsWith("https://")) {
            JkBuildParser.EmbeddedUrlParts parts = splitEmbeddedUrl(value);
            GitRefSpec ref;
            boolean shallow;
            if (parts.refSpec() != null) {
                ref = parseUrlEmbeddedRefSpec(parts.refSpec());
                shallow = false;
            } else {
                // No ref embedded → default to branch "main", full clone.
                ref = new GitRefSpec.Branch("main");
                shallow = false;
            }
            String canonical = GitUrl.canonicalize(parts.baseUrl());
            GitSource source = new GitSource(canonical, parts.baseUrl(), ref, parts.subdir(), true, false, shallow);
            return Dependency.gitByName(name, source);
        }

        // Version spec or reserved keyword → catalog lookup.
        // A reserved keyword (latest/stable/lts/…) or a string that starts with a
        // version-spec character (digit, ^, ~, =, >, <) is always a catalog dep.
        if (isVersionSpecOrKeyword(value)) {
            // `version = "2.0"` typed below a table header is a project identity key that fell into
            // the table, not a library called `version`; say so instead of hunting the catalog.
            if (ManifestProject.IDENTITY_KEYS.contains(name)
                    && catalog.lookup(name).isEmpty()) {
                int dot = displayPath.lastIndexOf('.');
                throw new JkBuildParseException(
                        ManifestProject.strandedIdentity(name, dot < 0 ? displayPath : displayPath.substring(0, dot)));
            }
            LibraryCatalog.Module mod = catalog.lookup(name)
                    .orElseThrow(() -> new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog)));
            VersionSelector selector = VersionSelector.parseFloating(value);
            return Dependency.of(name, mod.moduleKey(), selector);
        }

        // Ambiguous string: not a recognised version spec, not a git URL. There is no
        // deferred-path fallback anymore — this is always an unknown-library error.
        throw new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog));
    }

    /**
     * Returns {@code true} when {@code value} should always be treated as a version spec or
     * reserved keyword — never as a filesystem path — regardless of what the filesystem contains.
     *
     * <ul>
     * <li>Reserved keywords: {@code latest}, {@code stable}, {@code lts}, {@code preview},
     * {@code nightly}.
     * <li>Version spec operators: leading {@code ^} (caret), {@code ~} (tilde), {@code =}
     * (exact), {@code >}, {@code <}.
     * <li>Bare version numbers: leading digit (e.g. {@code 1.2.3}, {@code 2.0}).
     * </ul>
     */
    static boolean isVersionSpecOrKeyword(String value) {
        if (value.isEmpty()) return false;
        return switch (value) {
            case "latest", "stable", "lts", "preview", "nightly" -> true;
            default -> {
                char first = value.charAt(0);
                yield Character.isDigit(first)
                        || first == '^'
                        || first == '~'
                        || first == '='
                        || first == '>'
                        || first == '<';
            }
        };
    }

    /** Unknown short-name error, with catalog "did you mean" suggestions when available. */
    static String unknownLibraryMessage(String displayPath, String name, LibraryCatalog catalog) {
        StringBuilder msg = new StringBuilder(displayPath)
                .append(" — unknown short name `")
                .append(name)
                .append("`. ");
        List<String> suggestions = catalog.suggestionsFor(name, 5);
        if (!suggestions.isEmpty()) {
            msg.append("Did you mean: ").append(String.join(", ", suggestions)).append("? ");
        }
        msg.append("Either spell out the coord as `{ group = \"...\", version = \"...\" }`, ")
                .append("or pick a curated name from the catalog.");
        return msg.toString();
    }

    static Dependency parseDepEntry(
            String name, TomlTable entry, Scope scope, @Nullable Workspace workspace, LibraryCatalog catalog) {
        // `optional = true` withholds the dep from the default resolution; a
        // [features] entry pulls it in by name. Works with every dep form
        // (coord / git / path / workspace / sha256) since it's applied to the
        // parsed result regardless of source.
        boolean optional = Boolean.TRUE.equals(entry.getBoolean("optional"));
        Dependency dep =
                parseDepEntryForm(name, entry, scope, workspace, catalog).withOptional(optional);
        dep = applyDependencyKind(dep, entry, scope, name);
        dep = applyFixtures(dep, entry, scope, name);
        // Cross-package features: only when the consumer set `features` and/or
        // `default-features` — absent keys leave prior resolve behavior unchanged.
        boolean hasFeaturesKey = entry.contains("features");
        boolean hasDefaultFeaturesKey = entry.contains("default-features");
        if (!hasFeaturesKey && !hasDefaultFeaturesKey) return dep;
        List<String> features = hasFeaturesKey
                ? optionalStringList(entry, "features", scope.tomlSection() + "." + name + ".features")
                : List.of();
        boolean defaultFeatures = !hasDefaultFeaturesKey || !Boolean.FALSE.equals(entry.getBoolean("default-features"));
        return dep.withFeatures(features, defaultFeatures);
    }

    /**
     * {@code kind = "main"|"tests"} — workspace sibling tests kind (Mill {@code testModuleDeps})
     * or external Maven test-jar. Tests kind is only legal in test scopes so helpers never leak
     * into main jars. External (non-workspace) kind=tests is only legal on Maven GAs.
     */
    static Dependency applyDependencyKind(Dependency dep, TomlTable entry, Scope scope, String name) {
        if (!entry.contains("kind")) return dep;
        String displayPath = scope.tomlSection() + "." + name;
        String raw = entry.getString("kind");
        DependencyKind kind;
        try {
            kind = DependencyKind.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException(displayPath + ".kind: " + e.getMessage());
        }
        if (kind == DependencyKind.MAIN) return dep.withKind(kind);
        // kind = "tests"
        if (scope != Scope.TEST && scope != Scope.TEST_DEV) {
            throw new JkBuildParseException(displayPath
                    + ".kind = \"tests\" is only legal under [test-dependencies] or"
                    + " [test-dev-dependencies] (got ["
                    + scope.tomlSection()
                    + "])");
        }
        if (!dep.isWorkspace()) {
            if (dep.isGit() || dep.isPath() || dep.isFile()) {
                throw new JkBuildParseException(displayPath
                        + ".kind = \"tests\" requires `workspace = true` or a Maven"
                        + " coordinate (got git/path/file source)");
            }
            // Maven GA only (group:artifact). packageKey maps this to g:a:test-jar:tests.
            String mod = dep.module();
            if (mod == null || mod.indexOf(':') <= 0 || mod.indexOf(':') != mod.lastIndexOf(':')) {
                throw new JkBuildParseException(displayPath
                        + ".kind = \"tests\" on an external dep requires a Maven"
                        + " group:artifact module");
            }
        }
        return dep.withKind(kind);
    }

    /**
     * {@code fixtures = true} — consume a workspace sibling's fixtures output directory. Legal only
     * on workspace edges in test scopes, and independent of {@code kind}.
     */
    static Dependency applyFixtures(Dependency dep, TomlTable entry, Scope scope, String name) {
        if (!entry.contains("fixtures")) return dep;
        String displayPath = scope.tomlSection() + "." + name;
        Boolean flag = entry.getBoolean("fixtures");
        if (!Boolean.TRUE.equals(flag)) {
            throw new JkBuildParseException(displayPath + ".fixtures must be `true` (the only legal value)");
        }
        if (scope != Scope.TEST && scope != Scope.TEST_DEV) {
            throw new JkBuildParseException(displayPath
                    + ".fixtures = true is only legal under [test-dependencies] or"
                    + " [test-dev-dependencies] (got ["
                    + scope.tomlSection()
                    + "])");
        }
        if (!dep.isWorkspace()) {
            throw new JkBuildParseException(displayPath + ".fixtures = true requires `workspace = true`");
        }
        return dep.withFixtures(true);
    }

    static Dependency parseDepEntryForm(
            String name, TomlTable entry, Scope scope, @Nullable Workspace workspace, LibraryCatalog catalog) {
        String displayPath = scope.tomlSection() + "." + name;
        boolean hasWorkspace = entry.contains("workspace");
        boolean hasVersion = entry.contains("version");
        boolean hasGit = entry.contains("git");
        boolean hasSha256 = entry.contains("sha256");
        // A standalone `path` (not the git sub-directory modifier, which only applies alongside
        // `git`) is a consume-only path dependency.
        boolean hasPath = entry.contains("path") && !hasGit;

        int sourceCount = (hasVersion ? 1 : 0)
                + (hasGit ? 1 : 0)
                + (hasWorkspace ? 1 : 0)
                + (hasSha256 ? 1 : 0)
                + (hasPath ? 1 : 0);
        // The only legal multi-source pairing: sha256 + version (version records the coordinate).
        boolean sha256WithVersion = hasSha256 && hasVersion && !hasGit && !hasWorkspace;
        // No source + group/name = platform-managed (BOM supplies the version at resolve).
        boolean platformManaged = sourceCount == 0 && (entry.contains("group") || entry.contains("name"));
        if (sourceCount == 0 && !platformManaged) {
            throw new JkBuildParseException(
                    displayPath + " must set exactly one of `version`, `git`, `path`, `sha256`, or"
                            + " `workspace = true` — or `group`/`name` alone for a version managed by a"
                            + " [platform-dependencies] BOM");
        }
        if (sourceCount > 1 && !sha256WithVersion) {
            throw new JkBuildParseException(displayPath
                    + " sets more than one of `version` / `git` / `path` / `sha256` / `workspace`; "
                    + "pick exactly one");
        }

        if (hasWorkspace) {
            Boolean ws = entry.getBoolean("workspace");
            if (!Boolean.TRUE.equals(ws)) {
                throw new JkBuildParseException(displayPath + ".workspace must be `true` (the only legal value)");
            }
            // workspace = true is mutually exclusive with group/name too.
            if (entry.contains("group") || entry.contains("name")) {
                throw new JkBuildParseException(
                        displayPath + " with `workspace = true` must not set `group` or `name`");
            }
            // kind is applied in parseDepEntry after this form returns.
            return resolveWorkspaceDep(name, displayPath, workspace);
        }

        // For non-workspace deps, group/name may come from the table or
        // fall back to the bundled catalog (which keys off the short name).
        // A git source still REQUIRES explicit `group` — it's inherently a
        // user-controlled override where defaulting silently would be
        // surprising.
        String groupExplicit = entry.getString("group");
        String artifactExplicit = entry.getString("name");
        LibraryCatalog.Module catalogHit =
                (groupExplicit == null) ? catalog.lookup(name).orElse(null) : null;

        String group = groupExplicit != null ? groupExplicit : (catalogHit != null ? catalogHit.group() : null);
        String artifact =
                artifactExplicit != null ? artifactExplicit : (catalogHit != null ? catalogHit.artifact() : name);
        if (artifact != null && artifact.isBlank()) {
            throw new JkBuildParseException(displayPath + ".name must not be blank");
        }

        if (hasSha256) {
            if (groupExplicit == null || groupExplicit.isBlank()) {
                throw new JkBuildParseException(displayPath
                        + " with `sha256 = ...` must set a `group` explicitly "
                        + "(catalog shorthand applies only to version-based deps)");
            }
            String sha256 = entry.getString("sha256");
            if (sha256 == null || sha256.isBlank()) {
                throw new JkBuildParseException(displayPath + ".sha256 must not be blank");
            }
            String versionRaw = entry.getString("version");
            if (versionRaw == null || versionRaw.isBlank()) {
                throw new JkBuildParseException(displayPath + " with `sha256 = ...` must also set `version`");
            }
            return Dependency.file(name, group + ":" + artifact, versionRaw, sha256);
        }

        if (hasGit) {
            // git deps are always pure discovery: the coordinate (group, name) and
            // version come from the cloned repo's jk.toml. Specifying `group`,
            // `name`, or `version` in the dep entry is an error.
            for (String forbidden : new String[] {"group", "name", "version"}) {
                if (entry.contains(forbidden)) {
                    throw new JkBuildParseException(displayPath
                            + " with `git` must not set `" + forbidden + "` — the coordinate"
                            + " and version are always read from the cloned repo's jk.toml");
                }
            }
            return Dependency.gitByName(name, parseGitSource(entry, displayPath));
        }

        if (hasPath) {
            // Like git, a path dep is pure discovery: the coordinate and version are read from the
            // target project when it's built (its jk.toml for a jk project, or the derived GAV for a
            // Gradle/Maven project). Specifying `group`/`name`/`version` here is an error.
            for (String forbidden : new String[] {"group", "name", "version"}) {
                if (entry.contains(forbidden)) {
                    throw new JkBuildParseException(displayPath
                            + " with `path` must not set `" + forbidden + "` — the coordinate"
                            + " and version are always read from the target project");
                }
            }
            String pathValue = entry.getString("path");
            if (pathValue == null || pathValue.isBlank()) {
                throw new JkBuildParseException(displayPath + ".path must not be blank");
            }
            return Dependency.pathByName(name, new PathSource(pathValue));
        }

        if (platformManaged) {
            if (group == null || group.isBlank()) {
                throw new JkBuildParseException(displayPath
                        + " has no `version` and no resolvable `group` — a platform-managed dep needs"
                        + " an explicit `group` (or a catalog short name)");
            }
            return Dependency.platformManaged(name, group + ":" + artifact);
        }

        // version-only.
        if (group == null || group.isBlank()) {
            throw new JkBuildParseException(displayPath + " must set a `group` (or use a catalog-known short name)");
        }
        String versionRaw = entry.getString("version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        VersionSelector selector = VersionSelector.parseFloating(versionRaw);
        return Dependency.of(name, group + ":" + artifact, selector);
    }

    static Dependency resolveWorkspaceDep(String name, String displayPath, @Nullable Workspace workspace) {
        // The workspace lookup chain: modules are resolved upstream at
        // merge time (we don't have them here at single-file parse time),
        // so first check [workspace.dependencies], then fall back to
        // emitting a placeholder coord (Dependency.workspace) that
        // WorkspaceMerge can re-resolve against the sibling list.
        if (workspace != null) {
            WorkspaceDependency wd = workspace.dependencies().get(name);
            if (wd != null) {
                return materialize(name, wd);
            }
        }
        // No [workspace.dependencies] match. The parser cannot resolve the
        // sibling here — that requires the full module list, which only
        // WorkspaceMerge / WorkspaceLoader has. Emit a placeholder dep
        // tagged with the short name; WorkspaceMerge resolves it. The
        // unresolved state is encoded as a synthetic workspace:<name>
        // module with a Latest selector; the resolver never sees this
        // because WorkspaceMerge rewrites it first.
        return Dependency.workspace(name);
    }

    static Dependency materialize(String name, WorkspaceDependency wd) {
        String module = wd.module();
        GitSource source = wd.gitSource();
        if (source != null) {
            return Dependency.git(name, module, source);
        }
        // The record admits exactly one of version/source, which no type here can state.
        return Dependency.of(name, module, Objects.requireNonNull(wd.version(), "version"));
    }

    static GitSource parseGitSource(TomlTable obj, String displayPath) {
        String urlRaw = obj.getString("git");
        if (urlRaw == null) {
            throw new JkBuildParseException(displayPath + " requires a `git` URL");
        }

        JkBuildParser.EmbeddedUrlParts parts = splitEmbeddedUrl(urlRaw);

        String tag = obj.getString("tag");
        String branch = obj.getString("branch");
        String rev = obj.getString("rev");
        boolean hasExplicitRef = tag != null || branch != null || rev != null;

        if (parts.refSpec() != null && hasExplicitRef) {
            throw new JkBuildParseException(displayPath
                    + " sets both a URL-embedded ref (`@` or `#` suffix) and an explicit ref"
                    + " key (`tag`, `branch`, or `rev`); use one or the other");
        }

        String explicitPath = obj.getString("path");
        if (parts.subdir() != null && explicitPath != null) {
            throw new JkBuildParseException(displayPath
                    + " sets both a URL-embedded sub-directory (`!` suffix) and an explicit"
                    + " `path` key; use one or the other");
        }
        String path = parts.subdir() != null ? parts.subdir() : explicitPath;

        boolean submodules = obj.getBoolean("submodules", () -> true);
        boolean verifySigned = obj.getBoolean("verify-signed", () -> false);
        if (obj.contains("fetch")) {
            throw new JkBuildParseException(displayPath + ".fetch is no longer supported — every git dependency is"
                    + " resolved once and pinned in jk-lock.toml; a branch ref's tip only moves on an explicit `jk"
                    + " update --git` or `jk fetch`. Remove the `fetch` key.");
        }

        GitRefSpec ref;
        boolean shallow;

        if (parts.refSpec() != null) {
            // URL-embedded refs are always full (deep) clones regardless of ref type.
            ref = parseUrlEmbeddedRefSpec(parts.refSpec());
            shallow = false;
        } else {
            int set = (tag != null ? 1 : 0) + (branch != null ? 1 : 0) + (rev != null ? 1 : 0);
            if (set > 1) {
                throw new JkBuildParseException(displayPath + " must set exactly one of `tag`, `branch`, or `rev`");
            }
            if (tag != null) {
                ref = new GitRefSpec.Tag(tag);
                shallow = true; // explicit tag = → shallow clone
            } else if (branch != null) {
                ref = new GitRefSpec.Branch(branch);
                shallow = false;
            } else if (rev != null) {
                ref = new GitRefSpec.Rev(rev);
                shallow = false;
            } else {
                throw new JkBuildParseException(
                        displayPath + " must set `tag`, `branch`, or `rev` (or embed the ref in the URL)");
            }
        }

        String canonical = GitUrl.canonicalize(parts.baseUrl());
        return new GitSource(canonical, parts.baseUrl(), ref, path, submodules, verifySigned, shallow);
    }

    /**
     * The result of splitting URL-embedded ref and sub-directory out of a raw git URL. At most one
     * of {@link #refSpec} and {@link #subdir} may be non-null when this is produced from a plain
     * URL, but the parser also handles both.
     *
     * @param baseUrl the git repository URL with no embedded suffix
     * @param subdir sub-directory inside the repo, from the {@code !path} suffix, or {@code null}
     * @param refSpec raw ref string prefixed by {@code "@"} or {@code "#"}, or {@code null}
     */
    /**
     * Parse embedded ref ({@code @name} / {@code #sha}) and sub-directory ({@code !subdir}) out of
     * a raw git URL. Either or both may be absent. The two suffixes may appear in either order:
     *
     * <ul>
     * <li>{@code url@ref!subdir} — ref before subdir
     * <li>{@code url!subdir@ref} — subdir before ref
     * <li>{@code url#sha!subdir} / {@code url!subdir#sha} — sha with subdir
     * </ul>
     *
     * <p>The {@code @} ref delimiter is searched only after the last {@code /} or {@code :} in the
     * URL, so the {@code git@host} userinfo form is not confused for an embedded ref. The {@code #}
     * and {@code !} delimiters are searched from the start of the string (they are not valid in
     * standard git URL paths without encoding).
     */
    static JkBuildParser.EmbeddedUrlParts splitEmbeddedUrl(String urlRaw) {
        // @ must follow the authority so we don't mistake "git@github.com" for an embedded branch.
        // Anchor at the first '/' after the scheme+host ("://host/"), or after ':' for SCP form.
        int schemeEnd = urlRaw.indexOf("://");
        int pathStart;
        if (schemeEnd >= 0) {
            int hostSlash = urlRaw.indexOf('/', schemeEnd + 3);
            pathStart = hostSlash >= 0 ? hostSlash : urlRaw.length();
        } else {
            int colon = urlRaw.indexOf(':');
            pathStart = colon >= 0 ? colon : 0;
        }
        int atPos = urlRaw.indexOf('@', pathStart);
        int hashPos = urlRaw.indexOf('#');
        int bangPos = urlRaw.indexOf('!');

        // Ignore an @ that appears after a # (it would be inside the SHA string).
        if (hashPos >= 0 && atPos > hashPos) atPos = -1;

        boolean hasRef = atPos >= 0 || hashPos >= 0;
        boolean hasBang = bangPos >= 0;

        if (!hasRef && !hasBang) return new JkBuildParser.EmbeddedUrlParts(urlRaw, null, null);

        // Determine which delimiter comes first.
        int firstAt = atPos >= 0 ? atPos : Integer.MAX_VALUE;
        int firstHash = hashPos >= 0 ? hashPos : Integer.MAX_VALUE;
        int firstBang = bangPos >= 0 ? bangPos : Integer.MAX_VALUE;
        int first = Math.min(firstAt, Math.min(firstHash, firstBang));

        if (hasBang && bangPos == first) {
            // Pattern: baseUrl!subdir[@ref|#sha]
            String base = urlRaw.substring(0, bangPos);
            String rest = urlRaw.substring(bangPos + 1);
            int atInRest = rest.indexOf('@');
            int hashInRest = rest.indexOf('#');
            if (atInRest >= 0 && (hashInRest < 0 || atInRest < hashInRest)) {
                return new JkBuildParser.EmbeddedUrlParts(
                        base, rest.substring(0, atInRest), "@" + rest.substring(atInRest + 1));
            } else if (hashInRest >= 0) {
                return new JkBuildParser.EmbeddedUrlParts(
                        base, rest.substring(0, hashInRest), "#" + rest.substring(hashInRest + 1));
            } else {
                return new JkBuildParser.EmbeddedUrlParts(
                        base, rest, null); // subdir only, no ref (will error at validation)
            }
        } else {
            // Pattern: baseUrl[@ref|#sha][!subdir]
            int refPos = firstAt < firstHash ? atPos : hashPos;
            char refChar = urlRaw.charAt(refPos);
            String base = urlRaw.substring(0, refPos);
            String refAndRest = urlRaw.substring(refPos + 1);
            int bangInRest = refAndRest.indexOf('!');
            if (bangInRest >= 0) {
                return new JkBuildParser.EmbeddedUrlParts(
                        base,
                        refAndRest.substring(bangInRest + 1),
                        (refChar == '#' ? "#" : "@") + refAndRest.substring(0, bangInRest));
            } else {
                return new JkBuildParser.EmbeddedUrlParts(base, null, (refChar == '#' ? "#" : "@") + refAndRest);
            }
        }
    }

    /**
     * Parse the raw ref string extracted from a URL suffix ({@code "@name"} or {@code "#sha"}).
     * A hex-only string (any length) → {@link GitRefSpec.Rev}; a version-like name (starts with a
     * digit, or {@code v}/{@code r} followed by a digit) → {@link GitRefSpec.Tag}; anything else →
     * {@link GitRefSpec.Branch}. All are {@code shallow = false} — URL-embedded refs always do a
     * full clone even when they resolve to a tag.
     */
    static GitRefSpec parseUrlEmbeddedRefSpec(String prefixedRef) {
        // "#sha" is always a Rev; "@name" is classified by heuristic.
        if (prefixedRef.startsWith("#")) {
            return new GitRefSpec.Rev(prefixedRef.substring(1));
        }
        String name = prefixedRef.substring(1); // strip leading "@"
        if (name.isEmpty()) return new GitRefSpec.Branch(name);
        if (name.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return new GitRefSpec.Rev(name); // "@hexsha" form
        }
        char first = name.charAt(0);
        boolean versionLike = Character.isDigit(first)
                || ((first == 'v' || first == 'r') && name.length() > 1 && Character.isDigit(name.charAt(1)));
        return versionLike ? new GitRefSpec.Tag(name) : new GitRefSpec.Branch(name);
    }

    // Repositories / profiles / features / workspace

    /**
     * {@code [repositories]}. Credential and object-store fields keep their raw {@code ${VAR}} text:
     * expansion happens in {@code RepoCredentialResolver}, at the point a credential is actually
     * used.
     *
     * <p>Interpolating here made the parse environment-dependent, which is wrong in two ways. The
     * parse is memoized on (path, size, mtime), so the first caller's environment pinned everyone
     * else's interpolation; and the authoritative parse runs inside a long-lived engine, so it read
     * the daemon's environment rather than the caller's. Expanding at the point of use puts the
     * lookup where the request's environment is already in scope, and leaves the parse a pure
     * function of the file — fully cacheable, with no environment in its key.
     *
     * <p>URLs are deliberately <b>not</b> interpolated: the lockfile records a repository's URL, so
     * an environment-dependent URL would make a committed lock differ between machines from the same
     * commit. Credentials never reach the lock.
     */
}
