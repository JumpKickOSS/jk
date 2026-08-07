// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Objects;

/**
 * A declared dependency: short {@code library} handle, {@code module} ({@code group:artifact}),
 * version selector, and optional git/path/file source. Source kind is via {@link #isGit()},
 * {@link #isPath()}, {@link #isFile()}, {@link #isWorkspace()} — never by sniffing {@code module}.
 * {@code pinned} is derived (exact / git / path / file → pinned; floating selectors → not).
 * Cross-package feature selection: {@link #requestedFeatures()} / {@link #defaultFeatures()}.
 * Edges may select a {@link #kind()} ({@link DependencyKind#MAIN} default, or
 * {@link DependencyKind#TESTS} for Mill-style test-module deps / Maven test-jar).
 */
public record Dependency(
        String library,
        String module,
        VersionSelector version,
        GitSource gitSource,
        String sha256,
        boolean pinned,
        boolean optional,
        PathSource pathSource,
        /** Feature names requested of a path/workspace/git library's {@code [features]} table. */
        List<String> requestedFeatures,
        /** When true, the library's {@code features.default} list is included. */
        boolean defaultFeatures,
        /** Output kind; always {@link DependencyKind#MAIN} unless {@code kind = "tests"}. */
        DependencyKind kind) {

    /** Synthetic {@code module} for an unresolved workspace sibling; rewritten by {@code WorkspaceMerge}. */
    public static final String WORKSPACE_PREFIX = "workspace:";

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
    }

    /** Defaults pathSource null, no feature selection, default-features true, kind main. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            GitSource gitSource,
            String sha256,
            boolean pinned,
            boolean optional) {
        this(library, module, version, gitSource, sha256, pinned, optional, null, List.of(), true, DependencyKind.MAIN);
    }

    /** Defaults optional false, pathSource null, no feature selection, kind main. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            GitSource gitSource,
            String sha256,
            boolean pinned) {
        this(library, module, version, gitSource, sha256, pinned, false, null, List.of(), true, DependencyKind.MAIN);
    }

    /** Defaults feature selection empty / default-features true, kind main. */
    public Dependency(
            String library,
            String module,
            VersionSelector version,
            GitSource gitSource,
            String sha256,
            boolean pinned,
            boolean optional,
            PathSource pathSource) {
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
                DependencyKind.MAIN);
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
                kind);
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
                kind);
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
                kind == null ? DependencyKind.MAIN : kind);
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

    /**
     * Solver / lock package key for this edge. Workspace/git/path/file deps return {@link #module()}
     * unchanged. Maven GAs with {@link #isTestsKind()} map to {@code g:a:test-jar:tests}.
     */
    public String packageKey() {
        if (isWorkspace() || isGit() || isPath() || isFile()) return module;
        if (isTestsKind() && PackageId.isMavenPackageKey(module) && module.indexOf(':') == module.lastIndexOf(':')) {
            return PackageId.of(group(), name(), "test-jar", "tests").key();
        }
        if (PackageId.isMavenPackageKey(module) && module.indexOf(':') == module.lastIndexOf(':')) {
            return PackageId.ofGa(module).key();
        }
        return module;
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

    public String workspaceName() {
        return workspaceName(module);
    }

    public static boolean isWorkspaceRef(String module) {
        return module != null && module.startsWith(WORKSPACE_PREFIX);
    }

    public static String workspaceName(String module) {
        return isWorkspaceRef(module) ? module.substring(WORKSPACE_PREFIX.length()) : null;
    }

    public static String workspaceRef(String name) {
        return WORKSPACE_PREFIX + name;
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
            VersionSelector version, GitSource gitSource, String sha256, PathSource pathSource) {
        if (gitSource != null || sha256 != null || pathSource != null) return true;
        return version instanceof VersionSelector.Exact;
    }
}
