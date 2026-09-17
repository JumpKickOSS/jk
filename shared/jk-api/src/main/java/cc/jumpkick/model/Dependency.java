// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A declared dependency: short {@code library} handle, {@code module} ({@code group:artifact}),
 * version selector, and optional git/path/file source. Source kind is via {@link #isGit()},
 * {@link #isPath()}, {@link #isFile()}, {@link #isWorkspace()} — never by sniffing {@code module}.
 * {@code pinned} is derived (exact / git / path / file → pinned; floating selectors → not).
 * Cross-package feature selection: {@link #requestedFeatures()} / {@link #defaultFeatures()}.
 * Edges may select a {@link #kind()} ({@link DependencyKind#MAIN} default, or
 * {@link DependencyKind#TESTS} for Mill-style test-module deps / Maven test-jar).
 * {@link #fixtures()} is a separate flag: put a sibling's fixtures output on this
 * module's test classpath. It does not imply {@link DependencyKind#TESTS}. A Maven coordinate may
 * name a {@link #classifier()} ({@code natives-linux}, {@code linux-x86_64}): the edge is then the
 * classified jar of the module, {@code g:a:jar:classifier} in the solver and the lock. {@link
 * #exclusions()} lists the coordinates pruned from the edge's subtree at lock time.
 */
public record Dependency(
        String library,
        String module,
        VersionSelector version,
        @Nullable GitSource gitSource,
        @Nullable String sha256,
        boolean pinned,
        boolean optional,
        @Nullable PathSource pathSource,
        /** Feature names requested of a path/workspace/git library's {@code [features]} table. */
        List<String> requestedFeatures,
        /** When true, the library's {@code features.default} list is included. */
        boolean defaultFeatures,
        /** Output kind; always {@link DependencyKind#MAIN} unless {@code kind = "tests"}. */
        DependencyKind kind,
        /**
         * {@code fixtures = true} on a workspace test-dependency: consume the sibling's fixtures
         * output directory. Independent of {@link #kind()}.
         */
        boolean fixtures,
        /** The Maven classifier of the artifact this edge wants; {@code null} for the plain jar. */
        @Nullable String classifier,
        /**
         * Coordinates pruned from this edge's subtree, each {@code group:artifact} or
         * {@code group:*}; see {@link #exclusion(String)} for the grammar. Empty for most edges.
         */
        List<String> exclusions,
        /**
         * The plugin table that implied this edge, {@code quarkus} for the platform BOM a
         * {@code [quarkus]} table brings; {@code null} for an edge the manifest declares.
         */
        @Nullable String impliedBy) {

    /**
     * Synthetic {@code module} for an unresolved workspace sibling, {@code workspace:<name>} or
     * {@code workspace:<group>/<name>} when the edge names the group; rewritten by {@code WorkspaceMerge}.
     */
    public static final String WORKSPACE_PREFIX = "workspace:";

    /** Separates the group from the name in a qualified placeholder; a Maven group never contains it. */
    public static final char WORKSPACE_GROUP_SEPARATOR = '/';

    /** Synthetic {@code module} for a bare-name git dep (write-only; use {@link #isGit()}). */
    public static final String GIT_PREFIX = "git:";

    /** Synthetic {@code module} for a bare-name path dep (write-only; use {@link #isPath()}). */
    public static final String PATH_PREFIX = "path:";

    public Dependency {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(version, "version");
        if (!module.contains(":") || module.indexOf(':') != module.lastIndexOf(':')) {
            throw new IllegalArgumentException("dependency module must be 'group:artifact' (got: " + module + ")");
        }
        int sourceBits = (gitSource != null ? 1 : 0) + (sha256 != null ? 1 : 0) + (pathSource != null ? 1 : 0);
        if (sourceBits > 1) {
            throw new IllegalArgumentException("dependency cannot set more than one of git, path, or sha256 sources");
        }
        pinned = derivePinned(version, gitSource, sha256, pathSource);
        requestedFeatures = requestedFeatures == null ? List.of() : List.copyOf(requestedFeatures);
        kind = kind == null ? DependencyKind.MAIN : kind;
        if (classifier != null && (classifier.isBlank() || classifier.indexOf(':') >= 0)) {
            throw new IllegalArgumentException(
                    "dependency classifier must be a non-blank word without ':' (got: " + classifier + ")");
        }
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        for (String exclusion : exclusions) exclusion(exclusion);
    }

    /**
     * Validates one exclusion and returns it: {@code group:artifact}, {@code group:*} for every
     * artifact of a group, {@code *:artifact} for the artifact whatever its group, or {@code *:*}
     * for the whole subtree — Maven's four {@code <exclusion>} spellings. A {@code *} is a whole
     * side or nothing ({@code org.*:a} is refused), and so is a version or a third field.
     */
    public static String exclusion(String spelling) {
        Objects.requireNonNull(spelling, "exclusion");
        int colon = spelling.indexOf(':');
        if (colon <= 0 || colon != spelling.lastIndexOf(':') || colon == spelling.length() - 1) {
            throw new IllegalArgumentException(
                    "exclusion must be 'group:artifact', 'group:*', '*:artifact' or '*:*' (got: " + spelling + ")");
        }
        String group = spelling.substring(0, colon);
        String artifact = spelling.substring(colon + 1);
        if (group.isBlank() || partialWildcard(group) || partialWildcard(artifact)) {
            throw new IllegalArgumentException(
                    "an exclusion's group and artifact are each a Maven name or '*' (got: " + spelling + ")");
        }
        return spelling;
    }

    private static boolean partialWildcard(String side) {
        return side.contains("*") && !side.equals("*");
    }

    /** Every component but the provenance; the manifest declares the edge. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            @Nullable GitSource gitSource,
            @Nullable String sha256,
            boolean pinned,
            boolean optional,
            @Nullable PathSource pathSource,
            List<String> requestedFeatures,
            boolean defaultFeatures,
            DependencyKind kind,
            boolean fixtures,
            @Nullable String classifier,
            List<String> exclusions) {
        this(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions,
                null);
    }

    /** Every component but the classifier; the edge is the plain jar. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            @Nullable GitSource gitSource,
            @Nullable String sha256,
            boolean pinned,
            boolean optional,
            @Nullable PathSource pathSource,
            List<String> requestedFeatures,
            boolean defaultFeatures,
            DependencyKind kind,
            boolean fixtures) {
        this(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                null,
                List.of());
    }

    /** Every component but the exclusions; the edge prunes nothing. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            @Nullable GitSource gitSource,
            @Nullable String sha256,
            boolean pinned,
            boolean optional,
            @Nullable PathSource pathSource,
            List<String> requestedFeatures,
            boolean defaultFeatures,
            DependencyKind kind,
            boolean fixtures,
            @Nullable String classifier) {
        this(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                List.of());
    }

    /** Defaults pathSource null, no feature selection, default-features true, kind main. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            @Nullable GitSource gitSource,
            @Nullable String sha256,
            boolean pinned,
            boolean optional) {
        this(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                null,
                List.of(),
                true,
                DependencyKind.MAIN,
                false);
    }

    /** Defaults optional false, pathSource null, no feature selection, kind main. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            @Nullable GitSource gitSource,
            @Nullable String sha256,
            boolean pinned) {
        this(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                false,
                null,
                List.of(),
                true,
                DependencyKind.MAIN,
                false);
    }

    /** Defaults feature selection empty / default-features true, kind main. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            @Nullable GitSource gitSource,
            @Nullable String sha256,
            boolean pinned,
            boolean optional,
            @Nullable PathSource pathSource) {
        this(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                List.of(),
                true,
                DependencyKind.MAIN,
                false);
    }

    /** The same edge asking for {@code version} instead. */
    public Dependency withVersion(VersionSelector version) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions,
                impliedBy);
    }

    public Dependency withOptional(boolean optional) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions,
                impliedBy);
    }

    public Dependency withFeatures(List<String> features, boolean defaultFeatures) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                features == null ? List.of() : features,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions,
                impliedBy);
    }

    public Dependency withKind(DependencyKind kind) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind == null ? DependencyKind.MAIN : kind,
                fixtures,
                classifier,
                exclusions,
                impliedBy);
    }

    public Dependency withFixtures(boolean fixtures) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions,
                impliedBy);
    }

    /** The same edge naming the classified artifact; {@code null} returns to the plain jar. */
    public Dependency withClassifier(@Nullable String classifier) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions,
                impliedBy);
    }

    /** The same edge pruning {@code exclusions} from its subtree; each is validated by {@link #exclusion(String)}. */
    public Dependency withExclusions(List<String> exclusions) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions == null ? List.of() : exclusions,
                impliedBy);
    }

    /** The same edge as the one a plugin's {@code table} implies rather than a declared one. */
    public Dependency withImpliedBy(String table) {
        return new Dependency(
                library,
                module,
                version,
                gitSource,
                sha256,
                pinned,
                optional,
                pathSource,
                requestedFeatures,
                defaultFeatures,
                kind,
                fixtures,
                classifier,
                exclusions,
                Objects.requireNonNull(table, "table"));
    }

    /**
     * True when the consumer selected library features ({@code features = [...]} and/or
     * {@code default-features = false}). Unset feature keys leave this false so path deps keep
     * prior resolve behavior.
     */
    public boolean hasFeatureSelection() {
        return !requestedFeatures.isEmpty() || !defaultFeatures;
    }

    /** True when this edge requests a dependency's tests kind (Mill {@code *.test} / Maven test-jar). */
    public boolean isTestsKind() {
        return kind == DependencyKind.TESTS;
    }

    /** True when this edge consumes a sibling's fixtures output directory. */
    public boolean isFixtures() {
        return fixtures;
    }

    /**
     * Solver / lock package key for this edge. Workspace/git/path/file deps return {@link #module()}
     * unchanged. Maven GAs with {@link #isTestsKind()} map to {@code g:a:test-jar:tests}; one with
     * a {@link #classifier()} maps to {@code g:a:jar:classifier}.
     */
    public String packageKey() {
        if (isWorkspace() || isGit() || isPath() || isFile()) return module;
        if (!PackageId.isMavenPackageKey(module) || module.indexOf(':') != module.lastIndexOf(':')) return module;
        if (isTestsKind())
            return PackageId.of(group(), name(), "test-jar", "tests").key();
        return PackageId.of(group(), name(), PackageId.DEFAULT_TYPE, classifier == null ? "" : classifier)
                .key();
    }

    public Dependency(String module, VersionSelector version) {
        this(artifactOf(module), module, version, null, null, false);
    }

    public static Dependency of(String library, String module, VersionSelector version) {
        return new Dependency(library, module, version, null, null, false);
    }

    public static Dependency git(String module, GitSource source) {
        return new Dependency(artifactOf(module), module, VersionSelector.parse("=git"), source, null, false);
    }

    public static Dependency git(String library, String module, GitSource source) {
        return new Dependency(library, module, VersionSelector.parse("=git"), source, null, false);
    }

    public static Dependency gitByName(String name, GitSource source) {
        return git(name, GIT_PREFIX + name, source);
    }

    public static Dependency pathByName(String name, PathSource source) {
        Objects.requireNonNull(source, "source");
        return new Dependency(
                name, PATH_PREFIX + name, VersionSelector.parse("=path"), null, null, false, false, source);
    }

    public static Dependency workspace(String name) {
        return new Dependency(name, workspaceRef(name), new VersionSelector.Latest("workspace"), null, null, false);
    }

    /**
     * A workspace edge qualified by the sibling's {@code group}, the spelling that picks one of two
     * members carrying the same name ({@code edqs = { workspace = true, group = "org.tb" }}).
     */
    public static Dependency workspace(String name, String group) {
        Objects.requireNonNull(group, "group");
        return new Dependency(
                name, workspaceRef(name, group), new VersionSelector.Latest("workspace"), null, null, false);
    }

    public static Dependency workspace(String name, DependencyKind kind) {
        return workspace(name).withKind(kind);
    }

    public static Dependency file(String library, String module, String version, String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        return new Dependency(library, module, VersionSelector.parse("=" + version), null, sha256, false);
    }

    public static final String PLATFORM_MANAGED_VERSION = "platform-managed";

    public static Dependency platformManaged(String library, String module) {
        return new Dependency(
                library, module, VersionSelector.parse("=" + PLATFORM_MANAGED_VERSION), null, null, false);
    }

    public boolean isPlatformManaged() {
        return version instanceof VersionSelector.Exact e && PLATFORM_MANAGED_VERSION.equals(e.version());
    }

    public boolean isGit() {
        return gitSource != null;
    }

    public boolean isPath() {
        return pathSource != null;
    }

    public boolean isFile() {
        return sha256 != null;
    }

    public boolean isWorkspace() {
        return isWorkspaceRef(module);
    }

    public @Nullable String workspaceName() {
        return workspaceName(module);
    }

    /** The group a qualified workspace edge names; {@code null} for a bare edge or a non-workspace dep. */
    public @Nullable String workspaceGroup() {
        return workspaceGroup(module);
    }

    public static boolean isWorkspaceRef(@Nullable String module) {
        return module != null && module.startsWith(WORKSPACE_PREFIX);
    }

    /** The sibling name a placeholder spells, with the group qualifier (if any) stripped. */
    public static @Nullable String workspaceName(String module) {
        if (!isWorkspaceRef(module)) return null;
        String ref = module.substring(WORKSPACE_PREFIX.length());
        int slash = ref.indexOf(WORKSPACE_GROUP_SEPARATOR);
        return slash < 0 ? ref : ref.substring(slash + 1);
    }

    /** The group a qualified placeholder ({@code workspace:<group>/<name>}) spells; {@code null} when bare. */
    public static @Nullable String workspaceGroup(String module) {
        if (!isWorkspaceRef(module)) return null;
        String ref = module.substring(WORKSPACE_PREFIX.length());
        int slash = ref.indexOf(WORKSPACE_GROUP_SEPARATOR);
        return slash < 0 ? null : ref.substring(0, slash);
    }

    /**
     * The {@code group:name} coordinate a qualified placeholder resolves to without a sibling
     * list, or {@code null} for a bare placeholder or a non-workspace module.
     */
    public static @Nullable String workspaceCoordinate(String module) {
        String group = workspaceGroup(module);
        return group == null ? null : group + ":" + workspaceName(module);
    }

    public static String workspaceRef(String name) {
        return WORKSPACE_PREFIX + name;
    }

    public static String workspaceRef(String name, String group) {
        return WORKSPACE_PREFIX + group + WORKSPACE_GROUP_SEPARATOR + name;
    }

    public String group() {
        return module.substring(0, module.indexOf(':'));
    }

    public String name() {
        return module.substring(module.indexOf(':') + 1);
    }

    private static String artifactOf(String module) {
        Objects.requireNonNull(module, "module");
        int idx = module.indexOf(':');
        if (idx < 0) return module;
        return module.substring(idx + 1);
    }

    private static boolean derivePinned(
            VersionSelector version,
            @Nullable GitSource gitSource,
            @Nullable String sha256,
            @Nullable PathSource pathSource) {
        if (gitSource != null || sha256 != null || pathSource != null) return true;
        return version instanceof VersionSelector.Exact;
    }
}
