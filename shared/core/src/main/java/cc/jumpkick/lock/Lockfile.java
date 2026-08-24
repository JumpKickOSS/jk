// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory {@code jk-lock.toml} (schema {@code version = 1}). Optional fields ({@code jdk},
 * {@code kotlin}, {@code scala}, plugins, SDK, modules, toolchain, {@code manifests-sha256}) may be
 * null/empty for older lockfiles. Additive only — schema stays at 1 until 1.0.
 */
public record Lockfile(
        int version,
        String generatedBy,
        String resolutionAlgorithm,
        String jdk,
        String kotlin,
        String scala,
        List<Artifact> artifacts,
        List<PluginEntry> plugins,
        List<SdkEntry> sdk,
        List<ModuleEntry> modules,
        /** Minimum jk able to run this lock — a floor, never an artifact pin; null on legacy locks. */
        String jkMin,
        /** SHA-256 of every {@code jk.toml} that fed this lock; null on legacy locks. */
        String manifestsSha256,
        /** Durable auto project identity; null until minted. */
        String projectId) {

    /**
     * Resolved first-party project identity for one workspace member (or the standalone root at
     * {@code path = "."}). Captures concrete values after {@code *.workspace = true}
     * inheritance so a re-lock is the only way those pins change.
     */
    public record ModuleEntry(
            String path,
            String group,
            String name,
            String version,
            String jdk,
            Integer java,
            String kotlin,
            String groovy,
            String scala,
            String description,
            String sources,
            Boolean m2integration,
            Boolean m2install) {
        public ModuleEntry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
        }

        /** Unset Scala pin; {@code m2install} default (null). */
        public ModuleEntry(
                String path,
                String group,
                String name,
                String version,
                String jdk,
                Integer java,
                String kotlin,
                String groovy,
                String description,
                String sources,
                Boolean m2integration) {
            this(path, group, name, version, jdk, java, kotlin, groovy, description, sources, m2integration, null);
        }

        /** Unset Scala pin. */
        public ModuleEntry(
                String path,
                String group,
                String name,
                String version,
                String jdk,
                Integer java,
                String kotlin,
                String groovy,
                String description,
                String sources,
                Boolean m2integration,
                Boolean m2install) {
            this(
                    path,
                    group,
                    name,
                    version,
                    jdk,
                    java,
                    kotlin,
                    groovy,
                    null,
                    description,
                    sources,
                    m2integration,
                    m2install);
        }
    }

    public static final int CURRENT_VERSION = 1;
    public static final int MIN_SUPPORTED_VERSION = 1;
    public static final String RESOLUTION_ALGORITHM = "pubgrub-v1";

    public Lockfile {
        Objects.requireNonNull(generatedBy, "generatedBy");
        Objects.requireNonNull(resolutionAlgorithm, "resolutionAlgorithm");
        Objects.requireNonNull(artifacts, "artifacts");
        artifacts = List.copyOf(artifacts);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        sdk = sdk == null ? List.of() : List.copyOf(sdk);
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /** Unset Scala compiler pin. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk,
            List<ModuleEntry> modules,
            String jkMin,
            String manifestsSha256,
            String projectId) {
        this(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                null,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId);
    }

    /** Constructor without the jk floor. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk) {
        this(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                artifacts,
                plugins,
                sdk,
                List.of(),
                null,
                null,
                null);
    }

    /** Constructor with the jk floor but no module pins. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk,
            String jkMin) {
        this(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                artifacts,
                plugins,
                sdk,
                List.of(),
                jkMin,
                null,
                null);
    }

    /** Constructor with modules + the jk floor, no manifests digest. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk,
            List<ModuleEntry> modules,
            String jkMin) {
        this(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                null,
                null);
    }

    /** This lock with the jk floor set. */
    public Lockfile withJkMin(String floor) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                floor,
                manifestsSha256,
                projectId);
    }

    /** This lock with a content digest of the manifests used to produce it. */
    public Lockfile withManifestsSha256(String digest) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                digest,
                projectId);
    }

    /** This lock with a durable project identity. */
    public Lockfile withProjectId(String id) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                id);
    }

    /** Constructor without SDK entries. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins) {
        this(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, plugins, List.of());
    }

    /** Constructor without plugin entries. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts) {
        this(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, List.of());
    }

    /** Constructor that stamps a JDK but no Kotlin version. */
    public Lockfile(int version, String generatedBy, String resolutionAlgorithm, String jdk, List<Artifact> artifacts) {
        this(version, generatedBy, resolutionAlgorithm, jdk, null, artifacts, List.of());
    }

    /** Constructor that does not stamp a JDK. */
    public Lockfile(int version, String generatedBy, String resolutionAlgorithm, List<Artifact> artifacts) {
        this(version, generatedBy, resolutionAlgorithm, null, null, artifacts, List.of());
    }

    /** Return a copy with the resolved Kotlin compiler version stamped in. */
    public Lockfile withKotlin(String kotlinVersion) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlinVersion,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId);
    }

    /** Return a copy with the resolved Scala 3 compiler version stamped in. */
    public Lockfile withScala(String scalaVersion) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                scalaVersion,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId);
    }

    /** Return a copy with the given plugin entries (replaces any existing). */
    public Lockfile withPlugins(List<PluginEntry> newPlugins) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                scala,
                artifacts,
                newPlugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId);
    }

    /** Return a copy with the given provisioned-SDK component pins (replaces any existing). */
    public Lockfile withSdk(List<SdkEntry> newSdk) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                scala,
                artifacts,
                plugins,
                newSdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId);
    }

    /** Return a copy with resolved first-party module identity pins (replaces any existing). */
    public Lockfile withModules(List<ModuleEntry> newModules) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                newModules,
                jkMin,
                manifestsSha256,
                projectId);
    }

    public static Lockfile empty(String jkVersion) {
        return empty(jkVersion, null);
    }

    /** Empty artifact set with a resolved JDK pinned for the project. */
    public static Lockfile empty(String jkVersion, String jdk) {
        return new Lockfile(
                CURRENT_VERSION,
                "jk " + jkVersion,
                RESOLUTION_ALGORITHM,
                jdk,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
    }

    /** Provisioned SDK component pin (sdkmanager path + revision). */
    public record SdkEntry(String component, String revision) {
        public SdkEntry {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(revision, "revision");
        }
    }

    /** Third-party plugin pin: Maven {@code group:name}, version, {@code sha256:<hex>}. */
    public record PluginEntry(String coordinate, String version, String checksum) {
        public PluginEntry {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(checksum, "checksum");
        }

        /** Raw hex SHA-256 (strips a {@code "sha256:"} prefix). */
        public String sha256Hex() {
            return checksum.startsWith("sha256:") ? checksum.substring(7) : checksum;
        }
    }

    public record Artifact(
            String name,
            String version,
            String source,
            String checksum,
            String path,
            List<Scope> scopes,
            List<String> deps,
            String pinnedBy,
            GitInfo git,
            /** SHA-256 of the {@code -sources.jar}, or {@code null} when not published. */
            String sourcesChecksum) {

        public Artifact {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(deps, "deps");
            Objects.requireNonNull(scopes, "scopes");
            // Canonicalize scope order for stable lockfile output.
            EnumSet<Scope> set = EnumSet.noneOf(Scope.class);
            set.addAll(scopes);
            scopes = new ArrayList<>(set);
            deps = List.copyOf(deps);
        }

        /** Without sources checksum (the common case). */
        public Artifact(
                String name,
                String version,
                String source,
                String checksum,
                String path,
                List<Scope> scopes,
                List<String> deps,
                String pinnedBy,
                GitInfo git) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, git, null);
        }

        /** Without git provenance — the common Maven-coordinate case. */
        public Artifact(
                String name,
                String version,
                String source,
                String checksum,
                String path,
                List<Scope> scopes,
                List<String> deps,
                String pinnedBy) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, null, null);
        }

        /** Without {@code pinnedBy}. */
        public Artifact(
                String name,
                String version,
                String source,
                String checksum,
                String path,
                List<Scope> scopes,
                List<String> deps) {
            this(name, version, source, checksum, path, scopes, deps, null, null, null);
        }

        /** Convenience constructor for callers that don't care about scopes (defaults to MAIN). */
        public Artifact(String name, String version, String source, String checksum, String path, List<String> deps) {
            this(name, version, source, checksum, path, List.of(Scope.MAIN), deps, null, null, null);
        }

        public boolean inAnyScope(Set<Scope> include) {
            for (Scope s : scopes) if (include.contains(s)) return true;
            return false;
        }

        /** The {@code group} segment of {@link #name}. */
        public String moduleGroup() {
            if (!PackageId.isMavenPackageKey(name)) {
                int c = name.indexOf(':');
                return c < 0 ? name : name.substring(0, c);
            }
            return PackageId.parse(name).group();
        }

        /** The {@code artifact} segment of {@link #name} (not type/classifier). */
        public String moduleArtifact() {
            if (!PackageId.isMavenPackageKey(name)) {
                int c = name.indexOf(':');
                return c < 0 ? "" : name.substring(c + 1);
            }
            return PackageId.parse(name).artifact();
        }

        /**
         * Human-facing package identity without version: {@code g:a} for a default jar, or
         * {@link PackageId#display()} when a classifier / non-jar type is present ({@code g:a!aar}).
         * Progress labels prefer {@link #displayCoord()}; this is the version-less half for themed
         * formatters.
         */
        public String displayIdentity() {
            if (!PackageId.isMavenPackageKey(name)) return name;
            try {
                return PackageId.parse(name).display();
            } catch (RuntimeException e) {
                return name;
            }
        }

        /**
         * Human-facing coordinate for fetch progress and short diagnostics — same shape as
         * {@link Coordinate#toString()}: {@code g:a:v} for a default jar; classifier and {@code !type}
         * only when non-default ({@code g:a:v:linux-x86_64}, {@code g:a:v!aar}).
         */
        public String displayCoord() {
            return coordinate().toString();
        }

        /**
         * Canonical package key for this row. Bare legacy {@code g:a} names normalize to
         * {@code g:a:jar:}.
         */
        public String packageKey() {
            if (!PackageId.isMavenPackageKey(name)) return name;
            return PackageId.parse(name).key();
        }

        /**
         * True when {@code moduleOrKey} refers to this row — exact name/key match, or the same
         * {@code group:artifact} as a full package key ({@code g:a:jar:}). Used by {@code jk why},
         * tests, and lookups that still speak GA after package identity gained type/classifier.
         */
        public boolean matchesModule(String moduleOrKey) {
            if (moduleOrKey == null || moduleOrKey.isBlank()) return false;
            if (name.equals(moduleOrKey) || packageKey().equals(moduleOrKey)) return true;
            String thisGa = gaOf(name);
            String thatGa = gaOf(moduleOrKey);
            return thisGa != null && thisGa.equals(thatGa);
        }

        private static String gaOf(String nameOrKey) {
            if (!PackageId.isMavenPackageKey(nameOrKey)) return nameOrKey;
            try {
                return PackageId.parse(nameOrKey).ga();
            } catch (RuntimeException e) {
                return nameOrKey;
            }
        }

        /** This artifact as a {@link Coordinate} at its {@link #version}. */
        public Coordinate coordinate() {
            // The optional `path` field carries the artifact's real file name when the packaging
            // is not a plain jar (an androidx AAR) — the coordinate's type follows it, so every
            // fetch/locate path (sync, repo store, IDE fetch) asks for the right extension.
            if (isAar()) {
                return new Coordinate(moduleGroup(), moduleArtifact(), version, null, "aar");
            }
            if (!PackageId.isMavenPackageKey(name)) {
                return Coordinate.of(moduleGroup(), moduleArtifact(), version);
            }
            return PackageId.parse(name).withVersion(version);
        }

        /** True when the locked artifact is an Android AAR (path or package type). */
        public boolean isAar() {
            if (path != null && path.endsWith(".aar")) return true;
            return PackageId.isMavenPackageKey(name)
                    && "aar".equals(PackageId.parse(name).type());
        }

        /** Raw hex SHA-256 of the jar (strips a {@code "sha256:"} prefix), or {@code null}. */
        public String checksumHex() {
            return stripSha256(checksum);
        }

        /** Raw hex SHA-256 of the {@code -sources.jar} (strips the prefix), or {@code null}. */
        public String sourcesChecksumHex() {
            return stripSha256(sourcesChecksum);
        }

        private static String stripSha256(String c) {
            if (c == null) return null;
            return c.startsWith("sha256:") ? c.substring(7) : c;
        }

        /**
         * Provenance for a git-source artifact: the canonical repo URL, the resolved commit SHA, and
         * the original ref token (e.g. {@code tag:v1}). Present only for git-built artifacts; null for
         * Maven coordinates.
         */
        public record GitInfo(String url, String rev, String ref) {
            public GitInfo {
                Objects.requireNonNull(url, "url");
                Objects.requireNonNull(rev, "rev");
            }
        }
    }
}
