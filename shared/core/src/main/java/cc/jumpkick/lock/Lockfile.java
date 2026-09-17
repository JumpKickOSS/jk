// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * In-memory {@code jk-lock.toml} (schema {@code version = 1}). Optional fields ({@code [jdk]},
 * {@code [graal]}, {@code kotlin}, {@code scala}, plugins, SDK, modules, {@code manifests-sha256})
 * may be null/empty for older lockfiles. Additive only — schema stays at 1 until 1.0.
 */
public record Lockfile(
        int version,
        String generatedBy,
        String resolutionAlgorithm,
        @Nullable JdkPin jdk,
        @Nullable GraalPin graal,
        @Nullable String kotlin,
        @Nullable String scala,
        List<Artifact> artifacts,
        List<PluginEntry> plugins,
        List<SdkEntry> sdk,
        List<ModuleEntry> modules,
        /** Minimum jk able to run this lock — a floor, never an artifact pin; null when the lock names none. */
        @Nullable String jkMin,
        /** SHA-256 of every {@code jk.toml} that fed this lock; null when the lock carries no digest. */
        @Nullable String manifestsSha256,
        /** Durable auto project identity; null until minted. */
        @Nullable String projectId,
        /** Resolved {@code [native] metadata-repository} pin; null when no module declares one. */
        @Nullable NativeMetadata nativeMetadata,
        /** The code archive that wrote this lock; null when the writer ran from none, or the lock predates the stamp. */
        @Nullable WriterBuild writerBuild) {

    /**
     * The GraalVM reachability-metadata repository release a native build reads, resolved from
     * {@code [native] metadata-repository} at lock time.
     *
     * <p>Not an {@code [[artifact]]} row: it is on no classpath and in no scope, and the graph the
     * artifact table describes is the one the solver produced. It is an input to {@code
     * native-image} all the same — the repository decides which third-party reflection config the
     * image keeps — so leaving it unpinned made two machines on the same lock produce different
     * binaries. Version plus the zip's digest is everything {@code jk sync} needs to materialize it
     * offline.
     */
    public record NativeMetadata(String version, @Nullable String checksum) {
        public NativeMetadata {
            Objects.requireNonNull(version, "version");
        }

        /** Raw hex SHA-256 of the repository zip (strips a {@code "sha256:"} prefix), or null. */
        public @Nullable String checksumHex() {
            if (checksum == null) return null;
            return checksum.startsWith("sha256:") ? checksum.substring(7) : checksum;
        }
    }

    /**
     * The lockfile schema version. Frozen at 1 until JumpKick 1.0: the shape changes in place
     * (additive fields; a removed field is simply refused), never by minting a new number.
     */
    public static final int CURRENT_VERSION = 1;

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

    /** Unset Scala / Graal pins. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            @Nullable JdkPin jdk,
            @Nullable String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk,
            List<ModuleEntry> modules,
            @Nullable String jkMin,
            @Nullable String manifestsSha256,
            @Nullable String projectId) {
        this(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                null,
                kotlin,
                null,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                null,
                null);
    }

    /** Constructor without the jk floor. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            @Nullable JdkPin jdk,
            @Nullable String kotlin,
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

    /** This lock with the jk floor set. */
    public Lockfile withJkMin(@Nullable String floor) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                floor,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** This lock as written by another jk: {@code generated-by} names the writer, nothing else moves. */
    public Lockfile withGeneratedBy(String writer) {
        return new Lockfile(
                version,
                writer,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** This lock as written by the build {@code build} names; null when the writer ran from no archive. */
    public Lockfile withWriterBuild(@Nullable WriterBuild build) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                build);
    }

    /** This lock with a content digest of the manifests used to produce it. */
    public Lockfile withManifestsSha256(String digest) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                digest,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** This lock with a durable project identity. */
    public Lockfile withProjectId(String id) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                id,
                nativeMetadata,
                writerBuild);
    }

    /**
     * This lock with {@code newArtifacts} in place of its rows. Two callers rebuilt the record by
     * hand to do it — a git-provenance stamp and a sources backfill — and every field added since
     * has had to be threaded through both.
     */
    public Lockfile withArtifacts(List<Artifact> newArtifacts) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                newArtifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** This lock with the resolved reachability-metadata repository pin. */
    public Lockfile withNativeMetadata(NativeMetadata pin) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                pin,
                writerBuild);
    }

    /** This lock with a resolved JDK pin. */
    public Lockfile withJdk(JdkPin pin) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                pin,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** This lock with a resolved GraalVM pin (null clears it). */
    public Lockfile withGraal(GraalPin pin) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                pin,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** Constructor without SDK entries. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            @Nullable JdkPin jdk,
            @Nullable String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins) {
        this(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, plugins, List.of());
    }

    /** Constructor without plugin entries. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            @Nullable JdkPin jdk,
            @Nullable String kotlin,
            List<Artifact> artifacts) {
        this(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, List.of());
    }

    /** Constructor that stamps a JDK but no Kotlin version. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            @Nullable JdkPin jdk,
            List<Artifact> artifacts) {
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
                graal,
                kotlinVersion,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** Return a copy with the resolved Scala 3 compiler version stamped in. */
    public Lockfile withScala(String scalaVersion) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scalaVersion,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** Return a copy with the given plugin entries (replaces any existing). */
    public Lockfile withPlugins(List<PluginEntry> newPlugins) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                newPlugins,
                sdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /** Return a copy with the given provisioned-SDK component pins (replaces any existing). */
    public Lockfile withSdk(List<SdkEntry> newSdk) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                newSdk,
                modules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /**
     * This lock as the module at {@code memberPath} (a {@link ModuleEntry#path}) reads it: for every
     * coordinate a partition row lists the member on, that row alone; for every other coordinate,
     * the workspace's plain row alone. The row set a module's classpath, package and run are made
     * of; the lock on disk is unchanged. See {@link MemberRows}.
     */
    public Lockfile forMember(String memberPath) {
        return withArtifacts(MemberRows.narrow(artifacts, memberPath));
    }

    /** Return a copy with resolved first-party module identity pins (replaces any existing). */
    public Lockfile withModules(List<ModuleEntry> newModules) {
        return new Lockfile(
                version,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                newModules,
                jkMin,
                manifestsSha256,
                projectId,
                nativeMetadata,
                writerBuild);
    }

    /**
     * The platform BOMs this lock resolved: {@code group:artifact} → the version that pinned a
     * managed artifact, read off the {@code pinned-by} rows. A BOM that manages nothing here is
     * absent, and so is a row a {@code [managed-dependencies]} entry pinned ({@code jk.toml:<handle>}),
     * which names no BOM.
     */
    public Map<String, String> platformPins() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Artifact a : artifacts) {
            String by = a.pinnedBy();
            if (by == null) continue;
            int colon = by.lastIndexOf(':');
            if (colon <= 0 || colon == by.length() - 1 || by.indexOf(':') == colon) continue;
            out.putIfAbsent(by.substring(0, colon), by.substring(colon + 1));
        }
        return out;
    }

    public static Lockfile empty(String jkVersion) {
        return empty(jkVersion, null);
    }

    /** Empty artifact set with a resolved JDK pinned for the project. */
    public static Lockfile empty(String jkVersion, @Nullable JdkPin jdk) {
        return new Lockfile(
                CURRENT_VERSION,
                "jk " + jkVersion,
                RESOLUTION_ALGORITHM,
                jdk,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
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

    /**
     * One {@code [[plugin]]} row: Maven {@code group:name}, version, and at most one of a jar
     * {@link #checksum} ({@code sha256:<hex>}, the bytes a fetched plugin must have) or a workspace
     * {@link #path} (the module directory, relative to the lock, that builds the plugin — its
     * identity is its source, so no jar digest is recorded). A row with neither is a first-party
     * plugin at a pre-release version, pinned by version alone ({@link #versionOnly}).
     */
    public record PluginEntry(
            String coordinate,
            String version,
            @Nullable String checksum,
            @Nullable String path) {
        public PluginEntry {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            if (checksum != null && path != null) {
                throw new IllegalArgumentException("[[plugin]] " + coordinate + " names both `checksum` and `path`"
                        + " — a row is verified by one of them, not both");
            }
        }

        /** A plugin fetched as a jar, pinned to its bytes. */
        public PluginEntry(String coordinate, String version, String checksum) {
            this(coordinate, version, checksum, null);
        }

        /** A plugin built from the workspace module at {@code path}. */
        public static PluginEntry workspace(String coordinate, String version, String path) {
            return new PluginEntry(coordinate, version, null, path);
        }

        /**
         * A first-party plugin pinned by version alone: the jar ships inside the jk install of that
         * version, and while the version is a pre-release its published bytes still move, so a
         * digest would pin a moment rather than a release. The row gains its digest once the
         * version is a stable release.
         */
        public static PluginEntry versionOnly(String coordinate, String version) {
            return new PluginEntry(coordinate, version, null, null);
        }

        /** True when the plugin is a workspace module: verified by being built, not by a digest. */
        public boolean isWorkspace() {
            return path != null;
        }

        /** True when the row carries neither digest nor module path: the version is the whole pin. */
        public boolean isVersionOnly() {
            return checksum == null && path == null;
        }

        /** Raw hex SHA-256 (strips a {@code "sha256:"} prefix); {@code null} for a workspace plugin. */
        public @Nullable String sha256Hex() {
            if (checksum == null) return null;
            return checksum.startsWith("sha256:") ? checksum.substring(7) : checksum;
        }
    }

    /**
     * Separates an edge's {@code module@version} ref from the selector that produced it inside a
     * {@code deps} line: {@code "g:a:jar:@2.21 <- ^2.0"}. A ref never contains a space and a
     * selector never contains this token, so the split is exact; a line without it is an edge
     * whose declaration the lock does not carry.
     */
    public static final String DECLARED_SEPARATOR = " <- ";

    public record Artifact(
            String name,
            String version,
            String source,
            @Nullable String checksum,
            @Nullable String path,
            List<Scope> scopes,
            List<String> deps,
            @Nullable String pinnedBy,
            @Nullable GitInfo git,
            /** SHA-256 of the {@code -sources.jar}, or {@code null} when not published. */
            @Nullable String sourcesChecksum,
            /**
             * The selector this row's POM (or manifest) declared for each edge in {@link #deps},
             * keyed by the edge's {@code module@version} ref — the version that was asked for, beside
             * the one the solve picked. An edge with no entry declared nothing the lock knows of.
             */
            Map<String, String> declared,
            /**
             * The edges an exclusion pruned from this row's POM, one {@code group:artifact <- origin}
             * line each ({@code jk.toml:<handle>} for a manifest exclusion, {@code g:a@version} for a
             * POM's); the coordinate may still sit in the lock through another path.
             */
            List<String> excludedBy,
            /**
             * The workspace members whose classpath reads this row instead of the coordinate's plain
             * row, by {@link ModuleEntry#path}; empty for the workspace's own row. See {@link
             * Lockfile#forMember}.
             */
            List<String> members) {

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
            declared = declared == null || declared.isEmpty() ? Map.of() : Map.copyOf(declared);
            excludedBy = excludedBy == null || excludedBy.isEmpty() ? List.of() : List.copyOf(excludedBy);
            members = members == null || members.isEmpty()
                    ? List.of()
                    : members.stream().sorted().distinct().toList();
        }

        /** The workspace's own row: no member partition. */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<Scope> scopes,
                List<String> deps,
                @Nullable String pinnedBy,
                @Nullable GitInfo git,
                @Nullable String sourcesChecksum,
                Map<String, String> declared,
                List<String> excludedBy) {
            this(
                    name,
                    version,
                    source,
                    checksum,
                    path,
                    scopes,
                    deps,
                    pinnedBy,
                    git,
                    sourcesChecksum,
                    declared,
                    excludedBy,
                    List.of());
        }

        /** Every edge kept: nothing pruned. */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<Scope> scopes,
                List<String> deps,
                @Nullable String pinnedBy,
                @Nullable GitInfo git,
                @Nullable String sourcesChecksum,
                Map<String, String> declared) {
            this(
                    name,
                    version,
                    source,
                    checksum,
                    path,
                    scopes,
                    deps,
                    pinnedBy,
                    git,
                    sourcesChecksum,
                    declared,
                    List.of(),
                    List.of());
        }

        /** This row with {@code git} as its provenance; every other field kept. */
        public Artifact withGit(GitInfo git) {
            return new Artifact(
                    name,
                    version,
                    source,
                    checksum,
                    path,
                    scopes,
                    deps,
                    pinnedBy,
                    git,
                    sourcesChecksum,
                    declared,
                    excludedBy,
                    members);
        }

        /** This row with the {@code -sources.jar} digest; every other field kept. */
        public Artifact withSourcesChecksum(@Nullable String sourcesChecksum) {
            return new Artifact(
                    name,
                    version,
                    source,
                    checksum,
                    path,
                    scopes,
                    deps,
                    pinnedBy,
                    git,
                    sourcesChecksum,
                    declared,
                    excludedBy,
                    members);
        }

        /** This row as the partition the listed members read; every other field kept. */
        public Artifact withMembers(List<String> members) {
            return new Artifact(
                    name,
                    version,
                    source,
                    checksum,
                    path,
                    scopes,
                    deps,
                    pinnedBy,
                    git,
                    sourcesChecksum,
                    declared,
                    excludedBy,
                    members);
        }

        /** This row with {@code scopes}; every other field kept. */
        public Artifact withScopes(List<Scope> scopes) {
            return new Artifact(
                    name,
                    version,
                    source,
                    checksum,
                    path,
                    scopes,
                    deps,
                    pinnedBy,
                    git,
                    sourcesChecksum,
                    declared,
                    excludedBy,
                    members);
        }

        /** True when this row is one member partition of its coordinate rather than the workspace's row. */
        public boolean isPartition() {
            return !members.isEmpty();
        }

        /** Every edge without a declared selector. */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<Scope> scopes,
                List<String> deps,
                @Nullable String pinnedBy,
                @Nullable GitInfo git,
                @Nullable String sourcesChecksum) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, git, sourcesChecksum, Map.of());
        }

        /** The selector declared for the edge {@code depRef} ({@code module@version}), or null. */
        public @Nullable String declaredFor(String depRef) {
            return declared.get(depRef);
        }

        /** Without sources checksum (the common case). */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<Scope> scopes,
                List<String> deps,
                @Nullable String pinnedBy,
                @Nullable GitInfo git) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, git, null, Map.of());
        }

        /** Without git provenance — the common Maven-coordinate case. */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<Scope> scopes,
                List<String> deps,
                @Nullable String pinnedBy) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, null, null, Map.of());
        }

        /** Without {@code pinnedBy}. */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<Scope> scopes,
                List<String> deps) {
            this(name, version, source, checksum, path, scopes, deps, null, null, null, Map.of());
        }

        /** Convenience constructor for callers that don't care about scopes (defaults to MAIN). */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<String> deps) {
            this(name, version, source, checksum, path, List.of(Scope.MAIN), deps, null, null, null, Map.of());
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
         * Canonical package key for this row. Bare {@code g:a} names normalize to
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
        public @Nullable String checksumHex() {
            return stripSha256(checksum);
        }

        /** Raw hex SHA-256 of the {@code -sources.jar} (strips the prefix), or {@code null}. */
        public @Nullable String sourcesChecksumHex() {
            return stripSha256(sourcesChecksum);
        }

        private static @Nullable String stripSha256(@Nullable String c) {
            if (c == null) return null;
            return c.startsWith("sha256:") ? c.substring(7) : c;
        }

        /**
         * Provenance for a git-source artifact: the canonical repo URL, the resolved commit SHA, and
         * the original ref token (e.g. {@code tag:v1}). Present only for git-built artifacts; null for
         * Maven coordinates.
         */
        public record GitInfo(
                String url, String rev, @Nullable String ref) {
            public GitInfo {
                Objects.requireNonNull(url, "url");
                Objects.requireNonNull(rev, "rev");
            }
        }
    }
}
