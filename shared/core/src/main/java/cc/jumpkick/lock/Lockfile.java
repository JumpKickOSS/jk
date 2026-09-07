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
        /** Minimum jk able to run this lock — a floor, never an artifact pin; null on legacy locks. */
        @Nullable String jkMin,
        /** SHA-256 of every {@code jk.toml} that fed this lock; null on legacy locks. */
        @Nullable String manifestsSha256,
        /** Durable auto project identity; null until minted. */
        @Nullable String projectId,
        /** Resolved {@code [native] metadata-repository} pin; null when no module declares one. */
        @Nullable NativeMetadata nativeMetadata) {

    /**
     * What a lock says about one toolchain, on two independent axes.
     *
     * <p>{@code suggested-*} is a record of what created the lock. It does not bind a later build:
     * its major is a floor, so a newer JDK is fine and an older one is not. {@code required-*} is a
     * pin the project asked for with {@code =} in its manifest — the vendor, the version, or both
     * must match exactly, and jk installs that toolchain rather than settling for what is here.
     *
     * <p>The axes are per-field: a lock may require a vendor while only suggesting a version. A
     * field is written on exactly one axis, never both — a required vendor makes the suggested one
     * meaningless. Missing values are {@code ""}, never null.
     */
    public sealed interface ToolchainPin permits JdkPin, GraalPin {
        String suggestedVendor();

        String suggestedVersion();

        String requiredVendor();

        String requiredVersion();

        /** The vendor to honour, required or merely suggested; {@code ""} when the lock names none. */
        default String vendor() {
            return requiredVendor().isEmpty() ? suggestedVendor() : requiredVendor();
        }

        /** The version to honour, required or merely suggested; {@code ""} when the lock names none. */
        default String version() {
            return requiredVersion().isEmpty() ? suggestedVersion() : requiredVersion();
        }

        default boolean hasRequirement() {
            return !requiredVendor().isEmpty() || !requiredVersion().isEmpty();
        }

        default boolean isEmpty() {
            return vendor().isEmpty() && version().isEmpty();
        }

        /**
         * All four fields, for cache keys. A fingerprint that folded the axes together would
         * collide across two locks that ask for very different things.
         */
        default String fingerprint() {
            return String.join("|", suggestedVendor(), suggestedVersion(), requiredVendor(), requiredVersion());
        }
    }

    /** Locked Java JDK. See {@link ToolchainPin}. */
    public record JdkPin(String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion)
            implements ToolchainPin {
        public JdkPin {
            suggestedVendor = blankToEmpty(suggestedVendor);
            suggestedVersion = blankToEmpty(suggestedVersion);
            requiredVendor = blankToEmpty(requiredVendor);
            requiredVersion = blankToEmpty(requiredVersion);
        }

        /** A pin that only records what built the lock — the shape every pin had before pinning existed. */
        public static JdkPin suggested(String vendor, String version) {
            return new JdkPin(vendor, version, "", "");
        }
    }

    /** Locked GraalVM; omit the table when Graal was not in play. See {@link ToolchainPin}. */
    public record GraalPin(
            String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion)
            implements ToolchainPin {
        public GraalPin {
            suggestedVendor = blankToEmpty(suggestedVendor);
            suggestedVersion = blankToEmpty(suggestedVersion);
            requiredVendor = blankToEmpty(requiredVendor);
            requiredVersion = blankToEmpty(requiredVersion);
        }

        /** A pin that only records what built the lock. */
        public static GraalPin suggested(String vendor, String version) {
            return new GraalPin(vendor, version, "", "");
        }
    }

    /** Trimmed text, or {@code ""} for a missing or blank value. Toolchain pins never hold null. */
    static String blankToEmpty(@Nullable String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }

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
     * Resolved first-party project identity for one workspace member (or the standalone root at
     * {@code path = "."}). Captures concrete values after {@code *.workspace = true}
     * inheritance so a re-lock is the only way those pins change.
     */
    public record ModuleEntry(
            String path,
            String group,
            String name,
            String version,
            @Nullable Integer java,
            @Nullable String kotlin,
            @Nullable String groovy,
            @Nullable String scala,
            @Nullable String description,
            @Nullable String sources,
            @Nullable Boolean m2integration,
            @Nullable Boolean m2install) {
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
                @Nullable Integer java,
                @Nullable String kotlin,
                @Nullable String groovy,
                @Nullable String description,
                @Nullable String sources,
                @Nullable Boolean m2integration) {
            this(path, group, name, version, java, kotlin, groovy, description, sources, m2integration, null);
        }

        /** Unset Scala pin. */
        public ModuleEntry(
                String path,
                String group,
                String name,
                String version,
                @Nullable Integer java,
                @Nullable String kotlin,
                @Nullable String groovy,
                @Nullable String description,
                @Nullable String sources,
                @Nullable Boolean m2integration,
                @Nullable Boolean m2install) {
            this(
                    path,
                    group,
                    name,
                    version,
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

    /** Constructor with the jk floor but no module pins. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            @Nullable JdkPin jdk,
            @Nullable String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk,
            @Nullable String jkMin) {
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
            @Nullable JdkPin jdk,
            @Nullable String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk,
            List<ModuleEntry> modules,
            @Nullable String jkMin) {
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
                nativeMetadata);
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
                nativeMetadata);
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
                nativeMetadata);
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
                nativeMetadata);
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
                pin);
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
                nativeMetadata);
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
                nativeMetadata);
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
                nativeMetadata);
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
                nativeMetadata);
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
                nativeMetadata);
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
                nativeMetadata);
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
                nativeMetadata);
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
            @Nullable String checksum,
            @Nullable String path,
            List<Scope> scopes,
            List<String> deps,
            @Nullable String pinnedBy,
            @Nullable GitInfo git,
            /** SHA-256 of the {@code -sources.jar}, or {@code null} when not published. */
            @Nullable String sourcesChecksum) {

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
                @Nullable String checksum,
                @Nullable String path,
                List<Scope> scopes,
                List<String> deps,
                @Nullable String pinnedBy,
                @Nullable GitInfo git) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, git, null);
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
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, null, null);
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
            this(name, version, source, checksum, path, scopes, deps, null, null, null);
        }

        /** Convenience constructor for callers that don't care about scopes (defaults to MAIN). */
        public Artifact(
                String name,
                String version,
                String source,
                @Nullable String checksum,
                @Nullable String path,
                List<String> deps) {
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
