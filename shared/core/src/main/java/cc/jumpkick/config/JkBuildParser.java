// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.DenyPolicy;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Loads {@code jk.toml} into a {@link JkBuild}. Table parsers live in {@code Manifest*}
 * collaborators; this type is the disk/string facade and parse memo.
 */
public final class JkBuildParser {

    private JkBuildParser() {}

    /**
     * Process-lifetime memo of {@link #parseLocal(Path)}, keyed by absolute path and stamped by
     * {@link ManifestStamp}. One entry per file, replaced in place so a long {@code jk watch} cannot
     * grow without bound. Stores the <em>local</em> manifest only; workspace inheritance is applied
     * by {@link #parse(Path)} on top so it always sees a fresh root.
     *
     * <p>The stamp is {@code (size, mtime)} except inside the settle window, so a hit does not
     * re-read the file. {@code parse} fans out over every sibling; without that, a read-only
     * {@code jk status} would re-read the same handful of manifests thousands of times.
     */
    private static final StampedMemo<Path, ManifestStamp, JkBuild> PARSE_CACHE = StampedMemo.create();

    /**
     * Memo of {@link #document(Path)}, keyed by absolute path and stamped by {@link ManifestStamp} —
     * the same staleness rule as {@link #PARSE_CACHE}, for the same reason. Holds the TOML document
     * rather than the {@link JkBuild}, so the single-table entry points below ({@code [deny]},
     * {@code [train]}, {@code [image]}, {@code [test]} tags, {@code [jvm]}) share one disk read and
     * one {@link Interpolation#guard} with the full parse instead of each doing their own.
     */
    private static final StampedMemo<Path, ManifestStamp, TomlParseResult> DOC_CACHE = StampedMemo.create();

    /**
     * Two-tier staleness stamp for a manifest: {@code (size, mtime)} normally, plus the file's bytes
     * while its mtime is too fresh to trust.
     *
     * <p>A same-length edit landing inside one filesystem mtime tick is invisible to
     * {@code (size, mtime)}, and on Windows the tick is coarse enough that an editor save followed
     * immediately by a build hits it. Bytes are included only while the mtime is fresh, so the extra
     * read happens for one file. Outside {@link #SETTLE_MS} the stamp is one {@code readAttributes}
     * and the read is skipped entirely.
     *
     * <p>Crossing the window changes the stamp shape for the same file, so the first lookup after a
     * manifest settles recomputes once. That is one extra parse, in the safe direction: the rule can
     * only ever be too suspicious, never too trusting.
     *
     * <p>Same reasoning, same constant, as {@code FileHashMemo}'s settle gate on the engine side.
     *
     * @param body the file's contents while its mtime is inside the settle window, else {@code null}
     */
    private record ManifestStamp(
            long size, FileTime modified, @Nullable String body) {

        /** Distrust {@code (size, mtime)} for a file modified within this window. */
        private static final long SETTLE_MS = 2_000;

        static ManifestStamp of(Path file, ByteSource read) throws IOException {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                throw new JkBuildParseException("jk.toml not found: " + file);
            }
            long mtime = attrs.lastModifiedTime().toMillis();
            boolean settled = System.currentTimeMillis() - mtime >= SETTLE_MS;
            return new ManifestStamp(attrs.size(), attrs.lastModifiedTime(), settled ? null : read.read(file));
        }
    }

    /** Reads a manifest's text; separate so {@link ManifestStamp} can stay IO-policy-free. */
    private interface ByteSource {
        String read(Path file) throws IOException;
    }

    /**
     * Test seams: how often a manifest was asked for, and how often that cost a read.
     *
     * <p>The ratio is the property is about, and it is not observable any other way — a
     * memo that returns the right answer while re-reading the file every time passes every
     * correctness test there is. {@code FileHashMemo} exports the same pair for the same reason.
     */
    private static final AtomicLong PARSE_REQUESTS = new AtomicLong();

    private static final AtomicLong MANIFEST_READS = new AtomicLong();

    /** Manifest parses requested since process start (or the last {@link #resetStats()}). */
    public static long parseRequests() {
        return PARSE_REQUESTS.get();
    }

    /** Requests that had to read the file's bytes. */
    public static long manifestReads() {
        return MANIFEST_READS.get();
    }

    /** Test seam. */
    public static void resetStats() {
        PARSE_REQUESTS.set(0);
        MANIFEST_READS.set(0);
    }

    /** {@code Files.readString}, with the absent-file message every entry point shares. */
    private static String readManifest(Path file) throws IOException {
        MANIFEST_READS.incrementAndGet();
        try {
            return Files.readString(file);
        } catch (NoSuchFileException e) {
            throw new JkBuildParseException("jk.toml not found: " + file);
        }
    }

    /** The directory holding {@code manifest}. A manifest at a filesystem root has no module. */
    private static Path moduleDirOf(Path manifest) {
        Path dir = manifest.getParent();
        if (dir == null) throw new JkBuildParseException("jk.toml must live in a directory: " + manifest);
        return dir;
    }

    /**
     * Parse {@code jk.toml} and resolve workspace inheritance / sibling placeholders for the module
     * directory that owns the file. Prefer this for build, publish, status, etc.
     */
    public static JkBuild parse(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        JkBuild resolved = WorkspaceResolve.applyWorkspace(moduleDirOf(abs), parseLocal(file));
        List<PluginDeclaration> user = UserPlugins.fromConfig();
        if (user.isEmpty()) return resolved;
        return resolved.withPlugins(UserPlugins.merge(user, resolved.plugins()));
    }

    /**
     * Parse {@code jk.toml} as written — no workspace inheritance or sibling-coordinate rewrite.
     * Used by {@link WorkspaceLoader} / {@link WorkspaceResolve} to avoid recursive resolve while
     * assembling the workspace.
     */
    public static JkBuild parseLocal(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path key = file.toAbsolutePath().normalize();
        PARSE_REQUESTS.incrementAndGet();
        ManifestStamp stamp = ManifestStamp.of(key, JkBuildParser::readManifest);
        try {
            return PARSE_CACHE.get(key, stamp, () -> {
                try {
                    // The stamp already holds the bytes when the file is unsettled; otherwise this is
                    // the one read, on the miss that needs it.
                    String body = stamp.body() != null ? stamp.body() : readManifest(key);
                    TomlParseResult root = document(key, stamp, body);
                    Path moduleDir = moduleDirOf(key);
                    LibraryCatalog catalog;
                    try {
                        catalog = LibraryCatalog.forProject(moduleDir);
                    } catch (IllegalStateException e) {
                        throw new JkBuildParseException(e.getMessage(), e);
                    }
                    return build(root, catalog, moduleDir);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * <strong>The</strong> read of a {@code jk.toml}: bytes → tomlj → one syntax-error message →
     * one {@link Interpolation#guard}, memoized on {@link ManifestStamp}.
     *
     * <p>Every table in the file comes through here — the full {@link JkBuild} parse and each
     * single-table entry point alike. {@code jk deny} / {@code jk train} / {@code jk image} / the
     * test-tag baseline / the {@code [jvm]} overlay therefore reject the same invalid manifests
     * the build would.
     */
    static TomlParseResult document(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path key = file.toAbsolutePath().normalize();
        ManifestStamp stamp = ManifestStamp.of(key, JkBuildParser::readManifest);
        try {
            return document(key, stamp, null);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * The memoized document for {@code key} at {@code stamp}. {@code body} is the text when a caller
     * already holds it; {@code null} means read it here, and only on a miss.
     */
    private static TomlParseResult document(Path key, ManifestStamp stamp, @Nullable String body) {
        return DOC_CACHE.get(key, stamp, () -> {
            try {
                String text = body != null ? body : stamp.body() != null ? stamp.body() : readManifest(key);
                return document(text);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** As {@link #document(Path)} for text with no file behind it (string parses, tests). */
    private static TomlParseResult document(String toml) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        // Reject ${VAR} outside the whitelisted positions before anything else reads the file, so
        // the message names the position rather than surfacing later as a bewildering "no such
        // version".
        Interpolation.guard(result);
        return result;
    }

    /**
     * The document for {@code file}, or {@code null} when there is no such file. Absence is the
     * caller's business (most side tables have an empty value for it); a file that exists and is
     * malformed is never absence, and throws.
     */
    private static @Nullable TomlParseResult documentIfPresent(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return document(file);
        } catch (IOException e) {
            throw new JkBuildParseException("could not read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Test seam: how many files the parse memo currently holds. */
    static int parseCacheSizeForTest() {
        return PARSE_CACHE.size();
    }

    public static JkBuild reparse(Path file) throws IOException {
        forget(file);
        return parse(file);
    }

    /** Drop memo and re-parse without workspace resolution. */
    public static @Nullable JkBuild reparseLocal(Path file) throws IOException {
        forget(file);
        return parseLocal(file);
    }

    /** Evict both memos for {@code file}; the document memo backs the single-table entry points. */
    private static void forget(Path file) {
        Path key = file.toAbsolutePath().normalize();
        PARSE_CACHE.forget(key);
        DOC_CACHE.forget(key);
    }

    public static JkBuild parse(String toml) {
        return parse(toml, LibraryCatalog.layered());
    }

    /**
     * Test seam: parse against a synthetic library catalog instead of the default layered one.
     * Project short names come from {@code jk-libs.toml} at the workspace root when parsing from
     * disk; string parses use {@code catalog} as-is.
     */
    public static JkBuild parse(String toml, LibraryCatalog catalog) {
        return parse(toml, catalog, null);
    }

    /**
     * Full parse. {@code moduleDir} may be null (string parses); when set, loads third-party
     * manifests from {@link PluginDescriptorStore}. Short-name resolution uses the catalog the
     * caller supplied (disk parse passes {@link LibraryCatalog#forProject}).
     */
    private static JkBuild parse(String toml, LibraryCatalog catalog, @Nullable Path moduleDir) {
        Objects.requireNonNull(toml, "toml");
        return build(document(toml), catalog, moduleDir);
    }

    private static JkBuild build(TomlParseResult result, LibraryCatalog catalog, @Nullable Path moduleDir) {
        Objects.requireNonNull(catalog, "catalog");
        rejectRemovedCatalogConfig(result);
        rejectRemovedProjectTable(result);
        // Workspace roots keep concrete project defaults; members may omit fields and inherit.
        boolean workspaceRoot = hasWorkspaceModules(result);
        Project project = ManifestProject.parseProject(result, workspaceRoot);
        LibraryCatalog effective = catalog;
        Workspace workspace = ManifestTables.parseWorkspace(result, effective);
        JkBuild.Dependencies deps = ManifestDeps.parseDependencies(result, workspace, effective);
        List<RepositorySpec> repos = ManifestTables.parseRepositories(result);
        Profiles profiles = ManifestTables.parseProfiles(result);
        Features features = ManifestTables.parseFeatures(result);
        Map<String, String> manifest = ManifestTables.parseManifest(result);
        List<PluginDeclaration> plugins = ManifestBuild.parsePlugins(result);
        JkBuild.Application application =
                ManifestTables.parseApplication(result).orElse(null);
        if (application != null && PluginModule.isWorker(moduleDir)) {
            throw new JkBuildParseException(
                    "[application] is for apps — a plugin worker (jk-plugin.toml or Plugin service)"
                            + " already implies main "
                            + PluginModule.WORKER_MAIN
                            + "; drop the [application] table");
        }
        JkBuild.NativeConfig nativeConfig =
                ManifestBuild.parseNativeConfig(result).orElse(null);
        if (application != null
                && application.nativeImage()
                && nativeConfig != null
                && nativeConfig.enabled() == JkBuild.NativeMode.DISABLED) {
            throw new JkBuildParseException("[application].native = true conflicts with [native] enabled = false");
        }
        // Cold-store engines install built-ins lazily: fetch owners of referenced tables before
        // the manifest set below is captured, so this very parse validates their configs.
        Map<String, String> builtInFetchFailures = ManifestBuild.ensureBuiltInTables(result);
        List<PluginDescriptor> installedManifests = PluginTableRegistry.manifestsFor(moduleDir, plugins);
        Map<String, PluginConfig> pluginConfigs = ManifestTables.parsePluginTables(result, installedManifests);
        // minified = true enables the minified packager without requiring an empty [minified] table.
        pluginConfigs = ManifestTables.ensureMinifiedPluginConfigured(application, pluginConfigs, installedManifests);
        ManifestBuild.checkUnownedTables(result, moduleDir, plugins, installedManifests, builtInFetchFailures);
        boolean nativeDeclared = nativeConfig != null || (application != null && application.nativeImage());
        deps = ManifestBuild.withPlatformContributions(
                deps, project, nativeDeclared, pluginConfigs, installedManifests);
        JkBuild.Build build = ManifestBuild.parseBuild(result);
        List<JkBuild.KotlinPluginDecl> kotlinPlugins = ManifestBuild.parseKotlinPlugins(result);
        if (!kotlinPlugins.isEmpty()) {
            build = new JkBuild.Build(
                    build.orderAfter(),
                    build.testPluginJars(),
                    build.lint(),
                    kotlinPlugins,
                    build.kspOptions(),
                    build.extraSrc(),
                    build.testExtraSrc(),
                    build.fixtures(),
                    build.testWorkers(),
                    build.testSerialTags(),
                    build.platformPolicy(),
                    build.unmappedPolicy(),
                    build.testEnv());
        }
        // [test] is its own top-level table (test settings are not build inputs), but it folds into
        // the Build block, which already carries the other test-scoped setting, test-plugin-jars.
        List<JkBuild.TestEnvDecl> testEnv = ManifestBuild.parseTestEnv(result);
        if (!testEnv.isEmpty()) {
            build = new JkBuild.Build(
                    build.orderAfter(),
                    build.testPluginJars(),
                    build.lint(),
                    build.kotlinPlugins(),
                    build.kspOptions(),
                    build.extraSrc(),
                    build.testExtraSrc(),
                    build.fixtures(),
                    build.testWorkers(),
                    build.testSerialTags(),
                    build.platformPolicy(),
                    build.unmappedPolicy(),
                    testEnv);
        }
        JkBuild.FormatConfig format = ManifestTables.parseFormat(result);
        JkBuild.Install install = ManifestTables.parseInstall(result).orElse(null);
        Variants variants = ManifestTables.parseVariants(result, workspace, effective, installedManifests);
        // *.workspace = true is for members only — the root is the inheritance source.
        if (project.inheritsFromWorkspace() && workspace != null && !workspace.isEmpty()) {
            throw new JkBuildParseException("workspace root must set concrete project values"
                    + " (`*.workspace = true` is only valid on workspace modules)");
        }
        return new JkBuild(
                project,
                deps,
                repos,
                profiles,
                features,
                workspace,
                manifest,
                plugins,
                application,
                nativeConfig,
                pluginConfigs,
                build,
                format,
                variants,
                install);
    }

    /** The {@link Scope} whose toml section is {@code name}, or null. */
    static @Nullable Scope scopeForSection(String name) {
        for (Scope scope : Scope.values()) {
            if (scope.tomlSection().equals(name)) return scope;
        }
        return null;
    }

    static List<String> requireStringList(TomlTable table, String key, String where) {
        TomlArray arr = table.getArray(key);
        if (arr == null) throw new JkBuildParseException(where + " must be an array of directory strings");
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof String s) || s.isBlank()) {
                throw new JkBuildParseException(where + " must be an array of directory strings");
            }
            out.add(s);
        }
        return out;
    }

    /** Present boolean key → its value; absent → null (caller applies the default). */
    static @Nullable Boolean optionalBool(TomlTable table, String key) {
        return table.contains(key) ? table.getBoolean(key) : null;
    }

    /** Read an optional string key; present-but-non-string is a parse error; absent → null. */
    static @Nullable String stringOrThrow(TomlTable table, String key, String path) {
        if (!table.contains(key)) return null;
        String value = table.getString(key);
        if (value == null) {
            throw new JkBuildParseException(path + " must be a string");
        }
        return value;
    }

    /**
     * Parse the optional top-level {@code [manifest]} table — string-valued custom jar-manifest
     * attributes (e.g. {@code "Implementation-Title"}). {@code Main-Class} is intentionally
     * <em>not</em> read here; it derives from {@code main}.
     */
    /**
     * Reject removed catalog knobs. Project short names live in workspace-root {@code jk-libs.toml};
     * the system catalog is always global → bundled (no host-local file, no {@code catalog=} pin).
     */
    static void rejectRemovedCatalogConfig(TomlTable root) {
        if (root.contains("catalog")) {
            throw new JkBuildParseException("catalog = … was removed — short names always resolve through the system "
                    + "catalog (global + bundled) plus optional workspace-root "
                    + ManifestPaths.LIBRARIES);
        }
        if (root.getTable("libraries") != null) {
            throw new JkBuildParseException(
                    "[libraries] in jk.toml was removed — put short-name → group:artifact entries in "
                            + ManifestPaths.LIBRARIES
                            + " at the workspace root (standalone: project root)");
        }
    }

    /** {@code [project]} is gone — identity keys are bare top-level fields. */
    static void rejectRemovedProjectTable(TomlTable root) {
        if (root.getTable("project") != null) {
            throw new JkBuildParseException("[project] was removed — move its keys to the top level of jk.toml"
                    + " (e.g. name = \"…\", group = \"…\", version = \"…\")");
        }
    }

    /** True when {@code [workspace] modules = [...]} is a non-empty array — a workspace root. */
    static boolean hasWorkspaceModules(TomlTable root) {
        TomlTable ws = root.getTable("workspace");
        if (ws == null || !ws.contains("modules") || !ws.isArray("modules")) return false;
        TomlArray modules = ws.getArray("modules");
        return modules != null && modules.size() > 0;
    }

    /** Convert a tomlj value to a plain JDK type so plugin-api stays tomlj-free. */
    static @Nullable Object tomlToJava(@Nullable Object value) {
        if (value instanceof TomlTable t) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String k : t.keySet()) {
                Object converted = tomlToJava(t.get(k));
                if (converted != null) map.put(k, converted);
            }
            return Collections.unmodifiableMap(map);
        }
        if (value instanceof TomlArray arr) {
            List<Object> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                Object converted = tomlToJava(arr.get(i));
                if (converted != null) list.add(converted);
            }
            return List.copyOf(list);
        }
        return value; // String, Long, Double, Boolean — already JDK types
    }

    static String requireString(TomlTable table, String key, String displayPath) {
        String v = table.getString(key);
        if (v == null) {
            throw new JkBuildParseException("jk.toml is missing required key `" + displayPath + "`");
        }
        return v;
    }

    static List<String> optionalStringList(TomlTable table, String key, String displayPath) {
        if (!table.contains(key)) return List.of();
        Object value = table.get(key);
        if (!(value instanceof TomlArray arr)) {
            throw new JkBuildParseException("expected `" + displayPath + "` to be a list of strings");
        }
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException("expected `" + displayPath + "` to be a list of strings");
            }
            result.add(s);
        }
        return result;
    }

    static Variants parseVariants(
            TomlTable root, Workspace workspace, LibraryCatalog catalog, List<PluginDescriptor> installed) {
        return ManifestTables.parseVariants(root, workspace, catalog, installed);
    }

    static boolean isVersionSpecOrKeyword(String value) {
        return ManifestDeps.isVersionSpecOrKeyword(value);
    }

    record EmbeddedUrlParts(
            String baseUrl,
            @Nullable String subdir,
            @Nullable String refSpec) {}

    static EmbeddedUrlParts splitEmbeddedUrl(String urlRaw) {
        return ManifestDeps.splitEmbeddedUrl(urlRaw);
    }

    /**
     * {@code [test] include-tags} / {@code exclude-tags} — the baseline tag filters when no profile
     * or CLI override applies. Empty when the file or the table is absent.
     *
     * <p>A manifest that exists and does not parse throws. {@link TestTomlTags#EMPTY} means "this
     * project filters no tags"; swallowing a parse error into that would silently widen the suite.
     */
    public static TestTomlTags parseTestTags(Path buildFile) {
        TomlParseResult root = documentIfPresent(buildFile);
        return root == null ? TestTomlTags.EMPTY : ManifestTables.parseTestTags(root);
    }

    /** {@code [deny]} — the dependency policy block. Permissive when the file/table is absent. */
    public static DenyPolicy denyPolicy(Path file) {
        TomlParseResult root = documentIfPresent(file);
        return root == null ? DenyPolicy.permissive() : ManifestDeny.parse(root);
    }

    /** {@code [guards]} — the guard-lane knobs. {@link GuardsConfig#ABSENT} when the file/table is absent. */
    public static GuardsConfig guardsConfig(Path file) {
        TomlParseResult root = documentIfPresent(file);
        return root == null ? GuardsConfig.ABSENT : ManifestGuards.parse(root);
    }

    /** {@code [train]} — AOT training config. {@link TrainConfig#EMPTY} when the table is absent. */
    public static TrainConfig trainConfig(Path file) {
        TomlParseResult root = documentIfPresent(file);
        return root == null ? TrainConfig.EMPTY : ManifestTrain.parse(root, String.valueOf(file));
    }

    /** {@code [image]} — the project layer only; {@link GlobalConfig#image()} is the layer under it. */
    public static ManifestImage.ImageConfigData imageConfig(Path file) {
        TomlParseResult root = documentIfPresent(file);
        return root == null ? ManifestImage.ImageConfigData.EMPTY : ManifestImage.parse(root);
    }

    /** {@code [jvm]} — worker-fork JVM tuning. {@link PluginTuning#NONE} when the table is absent. */
    public static PluginTuning jvmTuning(Path file) {
        TomlParseResult root = documentIfPresent(file);
        return root == null ? PluginTuning.NONE : PluginTunings.fromToml(root);
    }

    public record TestTomlTags(List<String> includeTags, List<String> excludeTags) {
        public static final TestTomlTags EMPTY = new TestTomlTags(List.of(), List.of());

        public TestTomlTags {
            includeTags = includeTags == null ? List.of() : List.copyOf(includeTags);
            excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
        }

        public boolean isEmpty() {
            return includeTags.isEmpty() && excludeTags.isEmpty();
        }
    }

    public static JkBuild withArtifactOverride(JkBuild build, ArtifactOverride override) {
        return ManifestTables.withArtifactOverride(build, override);
    }

    public record ArtifactOverride(boolean assembly, boolean minified) {
        public ArtifactOverride {
            if (minified) assembly = true;
        }
    }

    public static @Nullable ArtifactOverride parseArtifactOverride(String raw) {
        return ManifestTables.parseArtifactOverride(raw);
    }

    static JkBuild reapplyPlatformContributions(@Nullable Path moduleDir, JkBuild module) {
        return ManifestBuild.reapplyPlatformContributions(moduleDir, module);
    }
}
