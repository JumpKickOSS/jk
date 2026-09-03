// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [project]} block of a {@code jk.toml}: who this module is and which toolchains build
 * it. Validating on construction — a blank group, name or version is not a project — and the owner
 * of Cargo-style workspace inheritance: {@code workspaceInherits} records which fields were written
 * {@code field.workspace = true}, and {@link #resolveFromWorkspaceRoot} is the single place they are
 * filled in from the root. A field left inherited past that point is a build that would publish
 * {@link #VERSION_FROM_WORKSPACE} as a version, which is why the resolve throws rather than
 * defaulting.
 */
public record Project(
        String group,
        String name,
        String version,
        @Nullable String jdk,
        int java,
        @Nullable VersionSelector kotlin,
        @Nullable VersionSelector groovy,
        @Nullable VersionSelector scala,
        SourcesMode sourcesMode,
        @Nullable String description,
        boolean m2integration,
        boolean m2install,
        Layout layout,
        Set<ProjectInherit> workspaceInherits,
        ToolchainSpec jdkSpec) {

    /**
     * Sentinel for string fields declared with {@code <field>.workspace = true} until
     * {@link #resolveFromWorkspaceRoot} runs. Never a publishable group/version.
     */
    public static final String VERSION_FROM_WORKSPACE = "__jk.workspace__";

    public Project {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        if (group.isBlank()) throw new IllegalArgumentException("group must not be blank");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        if (java < 0) {
            throw new IllegalArgumentException("java must be non-negative");
        }
        if (jdk != null && jdk.isBlank()) jdk = null;
        if (sourcesMode == null) sourcesMode = SourcesMode.DISABLED;
        if (layout == null) layout = Layout.AUTO;
        if (description != null && description.isBlank()) description = null;
        workspaceInherits =
                workspaceInherits == null || workspaceInherits.isEmpty() ? Set.of() : Set.copyOf(workspaceInherits);
        if (jdkSpec == null) jdkSpec = ToolchainSpec.NONE;
    }

    /**
     * The pre-{@link ToolchainSpec} arity: a {@code jdk} string alone says only what to resolve,
     * never whether the author pinned it, so the spec reads as undeclared.
     */
    public Project(
            String group,
            String name,
            String version,
            @Nullable String jdk,
            int java,
            @Nullable VersionSelector kotlin,
            @Nullable VersionSelector groovy,
            @Nullable VersionSelector scala,
            SourcesMode sourcesMode,
            @Nullable String description,
            boolean m2integration,
            boolean m2install,
            Layout layout,
            Set<ProjectInherit> workspaceInherits) {
        this(
                group,
                name,
                version,
                jdk,
                java,
                kotlin,
                groovy,
                scala,
                sourcesMode,
                description,
                m2integration,
                m2install,
                layout,
                workspaceInherits,
                ToolchainSpec.NONE);
    }

    /** Unset Scala pin; {@code workspaceInherits} as given. */
    public Project(
            String group,
            String name,
            String version,
            @Nullable String jdk,
            int java,
            @Nullable VersionSelector kotlin,
            @Nullable VersionSelector groovy,
            SourcesMode sourcesMode,
            @Nullable String description,
            boolean m2integration,
            Layout layout,
            Set<ProjectInherit> workspaceInherits) {
        this(
                group,
                name,
                version,
                jdk,
                java,
                kotlin,
                groovy,
                null,
                sourcesMode,
                description,
                m2integration,
                true,
                layout,
                workspaceInherits);
    }

    /** Unset Scala pin and no workspace inheritance flags. */
    public Project(
            String group,
            String name,
            String version,
            @Nullable String jdk,
            int java,
            @Nullable VersionSelector kotlin,
            @Nullable VersionSelector groovy,
            SourcesMode sourcesMode,
            @Nullable String description,
            boolean m2integration,
            Layout layout) {
        this(
                group,
                name,
                version,
                jdk,
                java,
                kotlin,
                groovy,
                null,
                sourcesMode,
                description,
                m2integration,
                true,
                layout,
                Set.of());
    }

    /** AUTO layout, no workspace inheritance flags. */
    public Project(
            String group,
            String name,
            String version,
            @Nullable String jdk,
            int java,
            @Nullable VersionSelector kotlin,
            @Nullable VersionSelector groovy,
            SourcesMode sourcesMode,
            @Nullable String description,
            boolean m2integration) {
        this(
                group,
                name,
                version,
                jdk,
                java,
                kotlin,
                groovy,
                null,
                sourcesMode,
                description,
                m2integration,
                true,
                Layout.AUTO,
                Set.of());
    }

    /** True when any project identity field still needs workspace-root resolution. */
    public boolean inheritsFromWorkspace() {
        return !workspaceInherits.isEmpty();
    }

    public boolean inherits(ProjectInherit field) {
        return workspaceInherits.contains(field);
    }

    /**
     * True when group/version still need a workspace root (cannot be used as a standalone
     * project).
     */
    public boolean requiresWorkspaceRoot() {
        return inherits(ProjectInherit.GROUP)
                || inherits(ProjectInherit.VERSION)
                || VERSION_FROM_WORKSPACE.equals(group)
                || VERSION_FROM_WORKSPACE.equals(version);
    }

    /**
     * Drop inheritance flags for optional fields (everything except {@link ProjectInherit#GROUP}
     * and {@link ProjectInherit#VERSION}). Used for standalone projects that omitted {@code java}
     * / {@code jdk} / … — those stay at local defaults rather than requiring a workspace.
     */
    public Project droppingOptionalInherits() {
        if (workspaceInherits.isEmpty()) return this;
        EnumSet<ProjectInherit> next = EnumSet.copyOf(workspaceInherits);
        next.remove(ProjectInherit.JDK);
        next.remove(ProjectInherit.JAVA);
        next.remove(ProjectInherit.KOTLIN);
        next.remove(ProjectInherit.GROOVY);
        next.remove(ProjectInherit.SCALA);
        next.remove(ProjectInherit.SOURCES);
        next.remove(ProjectInherit.DESCRIPTION);
        next.remove(ProjectInherit.M2INTEGRATION);
        next.remove(ProjectInherit.M2INSTALL);
        next.remove(ProjectInherit.LAYOUT);
        if (next.equals(workspaceInherits)) return this;
        return new Project(
                group,
                name,
                version,
                jdk,
                java,
                kotlin,
                groovy,
                scala,
                sourcesMode,
                description,
                m2integration,
                m2install,
                layout,
                next,
                jdkSpec);
    }

    /** True when this project declared {@code version.workspace = true} and is not yet resolved. */
    public boolean inheritsVersionFromWorkspace() {
        return inherits(ProjectInherit.VERSION) || VERSION_FROM_WORKSPACE.equals(version);
    }

    /**
     * Fill every {@code *.workspace = true} field from {@code root}. Throws if the root still has
     * pending inheritance for a requested field, or lacks a concrete value where required.
     */
    public Project resolveFromWorkspaceRoot(Project root) {
        Objects.requireNonNull(root, "root");
        if (workspaceInherits.isEmpty()) return this;
        if (root.inheritsFromWorkspace()) {
            throw new IllegalArgumentException("workspace root still has unresolved *.workspace inheritance");
        }
        String g = inherits(ProjectInherit.GROUP) ? requireRoot(root.group(), "group") : group;
        String v = inherits(ProjectInherit.VERSION) ? requireRoot(root.version(), "version") : version;
        if (VERSION_FROM_WORKSPACE.equals(v) && !inherits(ProjectInherit.VERSION)) {
            v = requireRoot(root.version(), "version");
        }
        String j = inherits(ProjectInherit.JDK) ? root.jdk() : jdk;
        int ja = inherits(ProjectInherit.JAVA) ? root.java() : java;
        VersionSelector kt = inherits(ProjectInherit.KOTLIN) ? root.kotlin() : kotlin;
        VersionSelector gr = inherits(ProjectInherit.GROOVY) ? root.groovy() : groovy;
        VersionSelector sc = inherits(ProjectInherit.SCALA) ? root.scala() : scala;
        SourcesMode src = inherits(ProjectInherit.SOURCES) ? root.sourcesMode() : sourcesMode;
        String desc = inherits(ProjectInherit.DESCRIPTION) ? root.description() : description;
        boolean m2 = inherits(ProjectInherit.M2INTEGRATION) ? root.m2integration() : m2integration;
        boolean inst = inherits(ProjectInherit.M2INSTALL) ? root.m2install() : m2install;
        Layout lay = inherits(ProjectInherit.LAYOUT) ? root.layout() : layout;
        ToolchainSpec js = inherits(ProjectInherit.JDK) ? root.jdkSpec() : jdkSpec;
        return new Project(g, name, v, j, ja, kt, gr, sc, src, desc, m2, inst, lay, Set.of(), js);
    }

    private static String requireRoot(String value, String field) {
        if (value == null || value.isBlank() || VERSION_FROM_WORKSPACE.equals(value)) {
            throw new IllegalArgumentException(
                    "module inherits " + field + " from the workspace, but the root has no concrete " + field);
        }
        return value;
    }

    /** Same project with a concrete {@code version} (workspace inheritance resolution). */
    public Project withVersion(String newVersion) {
        Objects.requireNonNull(newVersion, "version");
        if (newVersion.isBlank()) throw new IllegalArgumentException("version must not be blank");
        if (newVersion.equals(this.version) && !inherits(ProjectInherit.VERSION)) return this;
        EnumSet<ProjectInherit> next =
                workspaceInherits.isEmpty() ? EnumSet.noneOf(ProjectInherit.class) : EnumSet.copyOf(workspaceInherits);
        next.remove(ProjectInherit.VERSION);
        return new Project(
                group,
                name,
                newVersion,
                jdk,
                java,
                kotlin,
                groovy,
                scala,
                sourcesMode,
                description,
                m2integration,
                m2install,
                layout,
                next,
                jdkSpec);
    }

    /** Library project — bare-major {@code jdk} (0 → unset). */
    public Project(String group, String name, String version, int jdk) {
        this(
                group,
                name,
                version,
                majorSpec(jdk),
                jdk,
                null,
                null,
                null,
                SourcesMode.DISABLED,
                null,
                true,
                true,
                Layout.AUTO,
                Set.of());
    }

    /** A bare-major int as a jdk spec string ({@code 25} → {@code "25"}); 0/negative → unset. */
    private static @Nullable String majorSpec(int major) {
        return major > 0 ? Integer.toString(major) : null;
    }

    /** Fluent builder; only group/name/version are required. */
    public static Builder builder(String group, String name, String version) {
        return new Builder(group, name, version);
    }

    /** Mutable accumulator for {@link Project}. */
    public static final class Builder {
        private final String group;
        private final String name;
        private final String version;
        private @Nullable String jdk;
        private ToolchainSpec jdkSpec = ToolchainSpec.NONE;
        private int java;
        private @Nullable VersionSelector kotlin;
        private @Nullable VersionSelector groovy;
        private @Nullable VersionSelector scala;
        private SourcesMode sourcesMode = SourcesMode.DISABLED;
        private @Nullable String description;
        private boolean m2integration = true;
        private boolean m2install = true;
        private Layout layout = Layout.AUTO;

        private Builder(String group, String name, String version) {
            this.group = group;
            this.name = name;
            this.version = version;
        }

        /** Toolchain JDK spec, e.g. {@code "temurin-25"} or {@code "25"}. */
        public Builder jdk(@Nullable String jdk) {
            this.jdk = jdk;
            return this;
        }

        /** Toolchain JDK from a bare major ({@code 25} → {@code "25"}; 0/negative → unset). */
        public Builder jdkMajor(int major) {
            this.jdk = majorSpec(major);
            return this;
        }

        /** {@code --release} target for javac (0 → falls back to the jdk major). */
        public Builder java(int java) {
            this.java = java;
            return this;
        }

        public Builder kotlin(@Nullable VersionSelector kotlin) {
            this.kotlin = kotlin;
            return this;
        }

        public Builder groovy(@Nullable VersionSelector groovy) {
            this.groovy = groovy;
            return this;
        }

        public Builder scala(@Nullable VersionSelector scala) {
            this.scala = scala;
            return this;
        }

        public Builder sourcesMode(SourcesMode sourcesMode) {
            this.sourcesMode = sourcesMode;
            return this;
        }

        public Builder description(@Nullable String description) {
            this.description = description;
            return this;
        }

        public Builder m2integration(boolean m2integration) {
            this.m2integration = m2integration;
            return this;
        }

        public Builder m2install(boolean m2install) {
            this.m2install = m2install;
            return this;
        }

        public Builder layout(Layout layout) {
            this.layout = layout;
            return this;
        }

        /** The declaration behind {@link #jdk}: what was suggested, what was pinned with {@code =}. */
        public Builder jdkSpec(ToolchainSpec jdkSpec) {
            this.jdkSpec = jdkSpec;
            return this;
        }

        public Project build() {
            return new Project(
                    group,
                    name,
                    version,
                    jdk,
                    java,
                    kotlin,
                    groovy,
                    scala,
                    sourcesMode,
                    description,
                    m2integration,
                    m2install,
                    layout,
                    Set.of(),
                    jdkSpec);
        }
    }

    /** True when this is a Kotlin project (i.e. a {@code kotlin} version is set). */
    public boolean isKotlin() {
        return kotlin != null;
    }

    /** True when this is a Groovy project (i.e. a {@code groovy} version is set). */
    public boolean isGroovy() {
        return groovy != null;
    }

    /** True when this is a Scala project (i.e. a {@code scala} version is set). */
    public boolean isScala() {
        return scala != null;
    }

    /** {@code java} release, or {@code jdk} major when {@code java} is unset. */
    public int javaRelease() {
        return java > 0 ? java : jdkMajor();
    }

    /** Major implied by {@code jdk} ({@code "temurin-25"} → 25); 0 when unset/unparseable. */
    public int jdkMajor() {
        return majorOf(jdk);
    }

    /** First numeric-leading token in a JDK spec (before {@code .}); 0 if none. */
    public static int majorOf(@Nullable String spec) {
        if (spec == null) return 0;
        for (String tok : spec.toLowerCase(Locale.ROOT).split("[-_]")) {
            if (tok.isEmpty() || !Character.isDigit(tok.charAt(0))) continue;
            int dot = tok.indexOf('.');
            try {
                return Integer.parseInt(dot < 0 ? tok : tok.substring(0, dot));
            } catch (NumberFormatException ignored) {
                // not a clean integer — keep scanning later tokens
            }
        }
        return 0;
    }

    /** True when the spec pins a point release (e.g. {@code "25.0.3"}); jk rejects these. */
    public static boolean hasPointRelease(@Nullable String spec) {
        if (spec == null) return false;
        for (String tok : spec.toLowerCase(Locale.ROOT).split("[-_]")) {
            if (!tok.isEmpty() && Character.isDigit(tok.charAt(0)) && tok.indexOf('.') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code "java"} / {@code "kotlin"} / {@code "groovy"} / {@code "scala"} — derived from which compiler field is set. */
    public String languageName() {
        return isKotlin() ? "kotlin" : isGroovy() ? "groovy" : isScala() ? "scala" : "java";
    }
}
