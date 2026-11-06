// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.giter8.Giter8Apply;
import cc.jumpkick.giter8.Giter8Maven;
import cc.jumpkick.giter8.Giter8ShortNames;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.giter8.JkTemplateToml;
import cc.jumpkick.giter8.PluginTemplates;
import cc.jumpkick.giter8.ShippedTemplates;
import cc.jumpkick.giter8.TemplateAnswers;
import cc.jumpkick.giter8.TemplateSpec;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.ProxyEnvironment;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Layout;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.scaffold.NewProjectSeed;
import cc.jumpkick.scaffold.NewProjectStep;
import cc.jumpkick.templates.OfficialTemplatesFreshen;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Shared project creation used by {@code jk new}, MCP {@code new} and {@code POST /api/projects}: every
 * project comes from a template through {@link Giter8Apply}, and no template is the default one
 * ({@link NewProjectStep#DEFAULT_TEMPLATE}).
 */
public final class NewProjectOps {

    static final Pattern NAME = NewProjectStep.NAME;
    static final Pattern GROUP = NewProjectStep.GROUP;

    private NewProjectOps() {}

    /** The template parameter (and {@code default.properties} key) naming a template's layout. */
    static final String TEMPLATE_LAYOUT_PARAM = "jk_layout";

    public record Request(
            @Nullable String name,
            @Nullable String parentDir,
            @Nullable String group,
            @Nullable String lang,
            @Nullable String layout,
            @Nullable String template,
            boolean standalone,
            Map<String, String> templateParams,
            boolean relaxParent,
            @Nullable String targetDir) {
        /** HTTP/MCP compact shape. */
        public Request(
                @Nullable String name,
                @Nullable String parentDir,
                @Nullable String group,
                @Nullable String lang,
                @Nullable String layout,
                @Nullable String template) {
            this(name, parentDir, group, lang, layout, template, true, Map.of(), false, null);
        }
    }

    public record Result(Path path) {}

    /** Creation plus the durable project id (identity.toml materialized under the project home). */
    public record Created(Path path, @Nullable String projectId, int filesWritten) {
        public Created(Path path, @Nullable String projectId) {
            this(path, projectId, 0);
        }
    }

    /** What a create would write: target path and template-or-scaffold file list. Writes nothing. */
    public record Preview(String path, @Nullable String template, List<String> files) {}

    /** Validated inputs shared by {@link #create} and {@link #preview}. */
    private record Prepared(
            String name,
            Path parent,
            Path target,
            String group,
            @Nullable String lang,
            String template,
            Request req) {}

    public static Result create(Request req) throws IOException {
        Prepared prep = prepare(req);
        boolean existedBefore = Files.isDirectory(prep.target());
        try {
            scaffoldInto(prep, prep.target());
        } catch (IOException | RuntimeException e) {
            cleanupFailedTarget(prep.target(), existedBefore);
            throw e;
        }
        return new Result(prep.target());
    }

    /**
     * A failed scaffold must not block the retry behind a manual {@code rm -rf}: {@link #prepare}
     * guaranteed the target was absent or empty, so everything under it is ours to remove. A
     * pre-existing (empty) directory is kept, only emptied; one jk created is removed entirely.
     */
    private static void cleanupFailedTarget(Path target, boolean existedBefore) {
        try {
            if (!Files.isDirectory(target)) return;
            try (var children = Files.list(target)) {
                for (Path child : children.toList()) {
                    PathUtil.deleteRecursivelyOrThrow(child);
                }
            }
            if (!existedBefore) Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // cleanup is best-effort; don't mask the scaffold error
        }
    }

    /**
     * As {@link #create}, then resolve and persist the durable project identity so id-routed
     * surfaces ({@code GET /api/project?project=<id>}, MCP cards) can map the id back to the
     * checkout immediately — the scaffolder writes no lock. Identity persistence is best-effort:
     * creation already succeeded; a null id just means id-routing waits for the first build.
     */
    public static Created createWithIdentity(Request req) throws IOException {
        Result result = create(req);
        String projectId = null;
        try {
            var identity = ProjectIdentity.resolve(result.path());
            ProjectIdentity.IdentityFile.write(ProjectBuilds.projectHome(identity.id()), identity);
            ProjectIds.refresh(result.path().toString());
            projectId = identity.id();
        } catch (RuntimeException | IOException e) {
            // best-effort — see javadoc
            Log.debug("createWithIdentity: best-effort", e);
        }
        int files = 0;
        try (var walk = Files.walk(result.path())) {
            files = (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException ignored) {
            // count is advisory
        }
        return new Created(result.path(), projectId, files);
    }

    /**
     * The exact file set a create would produce, discovered by scaffolding into a scratch
     * directory and deleting it — never a guessed list, and the real target is untouched. Runs
     * the same validation as {@link #create}, so an occupied target refuses here too.
     */
    public static Preview preview(Request req) throws IOException {
        Prepared prep = prepare(req);
        Path tmpRoot = JkDirs.tmp();
        OwnerOnlyFiles.createDirectories(tmpRoot);
        Path scratch = Files.createTempDirectory(tmpRoot, "jk-new-preview-");
        Path probe = scratch.resolve(prep.name());
        try {
            scaffoldInto(prep, probe);
            List<String> files = new ArrayList<>();
            try (var walk = Files.walk(probe)) {
                walk.filter(Files::isRegularFile)
                        .map(f -> probe.relativize(f).toString().replace('\\', '/'))
                        .sorted()
                        .forEach(files::add);
            }
            return new Preview(prep.target().toString(), prep.template(), List.copyOf(files));
        } finally {
            PathUtil.deleteRecursivelyOrThrow(scratch);
        }
    }

    private static void scaffoldInto(Prepared prep, Path target) throws IOException {
        Request req = prep.req();
        Map<String, String> params = new LinkedHashMap<>();
        if (req.templateParams() != null) params.putAll(req.templateParams());
        params.putIfAbsent("name", prep.name());
        params.putIfAbsent("package", prep.group());
        params.putIfAbsent("organization", prep.group());
        if (prep.group() != null && !prep.group().isBlank()) {
            params.putIfAbsent("group", prep.group());
        }
        boolean offline = SessionContext.current().config().offlineOr(false);
        Path templateRoot;
        Path extracted = null;
        // Resolution lang stays null unless the user asked: a bare name then searches every
        // language, so a kotlin-only template is reached without --lang.
        String langName = prep.lang();
        var spec = resolveIndexed(prep.template(), langName, prep.parent());
        if (spec.isPresent() && inJar(spec.get())) {
            extracted = extract(spec.get());
            templateRoot = extracted;
        } else if (spec.isPresent() && spec.get().root() != null) {
            templateRoot = spec.get().root();
        } else {
            templateRoot = resolveTemplate(prep.template(), langName, prep.parent());
            // resolveTemplate may have freshened the official cache; re-resolve so layout
            // metadata is honored identically on cold and warm caches.
            if (spec.isEmpty()) {
                spec = resolveIndexed(prep.template(), langName, prep.parent());
            }
        }
        Layout layout = templateLayout(req.layout(), params, templateRoot);
        params.put(TEMPLATE_LAYOUT_PARAM, layout.token());
        if (layout == Layout.SIMPLE) {
            if (spec.isPresent() && !spec.get().supportsLayout(Giter8ShortNames.LAYOUT_SIMPLE)) {
                throw new IOException("template " + prep.template() + " does not support --layout "
                        + Layout.SIMPLE.token() + " (declared layouts: "
                        + String.join(", ", spec.get().layouts()) + ")");
            }
            // Set for path/remote templates too (no indexed metadata): a dual-layout
            // template honors it, a single-layout one ignores it — never a silent drop
            // that renders a different tree than the flag asked for.
            params.putIfAbsent(Giter8ShortNames.LAYOUT_SIMPLE, "yes");
        }
        try {
            Map<String, String> bound;
            try {
                // The catalogue lint again, for a template reached by path or URL rather than
                // through the index; then the asks settle and bind.
                bound = TemplateAnswers.bind(JkTemplateToml.load(templateRoot).orElse(null), params);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("template " + prep.template() + ": " + e.getMessage(), e);
            }
            Giter8Apply.apply(
                    templateRoot,
                    target,
                    bound,
                    Giter8Maven.fromRepositories(() -> RepoGroupBuilder.buildDefault(JkStores.storeCas()), offline));
        } catch (IOException e) {
            throw new IOException(
                    "applying template " + prep.template() + " lang=" + langName + ": " + e.getMessage(), e);
        } finally {
            if (extracted != null) {
                try {
                    PathUtil.deleteRecursivelyOrThrow(extracted);
                } catch (IOException ignored) {
                    // extract is under JkDirs.tmp(); don't mask the apply error
                }
            }
        }
        if (!Files.isRegularFile(target.resolve(ManifestPaths.MANIFEST))) {
            throw new IOException("template did not produce jk.toml: " + prep.template());
        }
        if (req.standalone()) NewProjectSeed.finish(target);
    }

    private static Prepared prepare(Request req) throws IOException {
        if (req == null) throw new IllegalArgumentException("missing body");
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) throw new IllegalArgumentException("missing \"name\"");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "invalid name '" + name + "' (lowercase letters, digits and -, starting with a letter)");
        }
        String parentRaw = req.parentDir() == null ? "" : req.parentDir().strip();
        if (parentRaw.isEmpty()) throw new IllegalArgumentException("missing \"parentDir\"");
        // Same rules as the activity Build path: ~ and relatives resolve against user.home.
        Path parent = PathUtil.resolveUserPath(parentRaw);
        if (!req.relaxParent()) assertAllowedParent(parent);
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("parentDir is not a directory: " + parent);
        }

        Path target;
        String targetRaw = req.targetDir() == null ? "" : req.targetDir().strip();
        if (!targetRaw.isEmpty()) {
            target = PathUtil.resolveUserPath(targetRaw).normalize();
            // targetDir gets the same allowlist gate as parentDir.
            if (!req.relaxParent()) assertAllowedParent(target.getParent() != null ? target.getParent() : target);
        } else {
            target = parent.resolve(name).normalize();
            if (!target.startsWith(parent)) {
                throw new IllegalArgumentException("name escapes parentDir");
            }
        }
        if (Files.exists(target.resolve(ManifestPaths.MANIFEST))) {
            throw new IllegalStateException("project already exists: " + target);
        }
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                if (stream.findAny().isPresent()) {
                    throw new IllegalStateException("target directory is not empty: " + target);
                }
            }
        }

        String group = req.group() == null || req.group().isBlank()
                ? "com.example"
                : req.group().strip();
        if (!GROUP.matcher(group).matches()) {
            throw new IllegalArgumentException("invalid group (Java package form expected)");
        }

        String lang = req.lang() == null || req.lang().isBlank() ? null : parseLang(req.lang());
        parseLayout(req.layout());
        String template = req.template() == null || req.template().isBlank()
                ? NewProjectStep.DEFAULT_TEMPLATE
                : req.template().strip();

        return new Prepared(name, parent, target, group, lang, template, req);
    }

    /**
     * A template as the new-project form asks it: the id its ref resolved to (the ref itself for a
     * path or URL), the layouts it renders, the one it renders by default, and its descriptor, or
     * {@code null} for a template that has none.
     */
    public record TemplateForm(
            String id, List<String> layouts, String defaultLayout, JkTemplateToml.@Nullable Meta meta) {
        public TemplateForm {
            layouts = List.copyOf(layouts);
        }
    }

    /**
     * Resolves {@code ref} as a create would, reads its descriptor (the catalogue lint included)
     * and its default layout. Writes nothing; a template in a jar is extracted and removed again.
     */
    public static TemplateForm templateForm(String ref, @Nullable String lang, Path cwd) throws IOException {
        String langName = lang == null || lang.isBlank() ? null : parseLang(lang);
        Optional<TemplateSpec> spec = resolveIndexed(ref, langName, cwd);
        Path root;
        Path extracted = null;
        if (spec.isPresent() && inJar(spec.get())) {
            extracted = extract(spec.get());
            root = extracted;
        } else if (spec.isPresent() && spec.get().root() != null) {
            root = Objects.requireNonNull(spec.get().root());
        } else {
            root = resolveTemplate(ref, langName, cwd);
            if (spec.isEmpty()) spec = resolveIndexed(ref, langName, cwd);
        }
        try {
            JkTemplateToml.Meta meta = JkTemplateToml.load(root).orElse(null);
            List<String> layouts = spec.map(TemplateSpec::layouts)
                    .orElse(meta == null ? List.of(Layout.TOKEN_TRADITIONAL, Layout.TOKEN_SIMPLE) : meta.layouts());
            String preferred = templateLayout(null, Map.of(), root).token();
            String defaultLayout = layouts.contains(preferred) ? preferred : layouts.getFirst();
            return new TemplateForm(spec.map(TemplateSpec::id).orElse(ref), layouts, defaultLayout, meta);
        } finally {
            if (extracted != null) PathUtil.deleteRecursivelyOrThrow(extracted);
        }
    }

    /** A template a jar holds (a plugin's, or one jk ships), which is extracted before it is read. */
    private static boolean inJar(TemplateSpec spec) {
        return TemplateSpec.SOURCE_PLUGIN.equals(spec.source()) || TemplateSpec.SOURCE_SHIPPED.equals(spec.source());
    }

    /** {@code spec}'s tree in a temp dir under {@link JkDirs#tmp()}; the caller deletes it. */
    private static Path extract(TemplateSpec spec) throws IOException {
        if (TemplateSpec.SOURCE_SHIPPED.equals(spec.source())) return ShippedTemplates.materialize(spec);
        return PluginTemplates.materialize(
                Objects.requireNonNull(spec.pluginId(), "pluginId"), spec.language(), spec.framework(), spec.name());
    }

    private static Optional<TemplateSpec> resolveIndexed(@Nullable String ref, @Nullable String lang, Path cwd) {
        try {
            return Giter8TemplateIndex.resolve(ref, lang, Giter8TemplateIndex.searchRoots(cwd));
        } catch (IllegalArgumentException e) {
            throw e;
        }
    }

    private static Optional<Path> indexedRoot(@Nullable String ref, @Nullable String lang, Path cwd)
            throws IOException {
        Optional<TemplateSpec> spec = resolveIndexed(ref, lang, cwd);
        if (spec.isEmpty()) {
            try {
                OfficialTemplatesFreshen.refreshQuiet(s -> {}, ProxyEnvironment.ambient()::gitHttpProxy);
            } catch (Throwable e) {
                // best-effort
                Log.debug("resolveIndexed: best-effort", e);
            }
            Giter8TemplateIndex.invalidate();
            spec = resolveIndexed(ref, lang, cwd);
        }
        if (spec.isEmpty()) return Optional.empty();
        TemplateSpec s = spec.get();
        if (s.root() != null) return Optional.of(s.root());
        return Optional.empty();
    }

    /**
     * Resolve a template ref: absolute/relative path, catalog/plugin id via {@link
     * Giter8TemplateIndex} (local roots + monorepo dogfood + official cache freshen).
     */
    static Path resolveTemplate(String ref, Path cwd) throws IOException {
        return resolveTemplate(ref, null, cwd);
    }

    static Path resolveTemplate(String ref, @Nullable String lang, Path cwd) throws IOException {
        Path asPath = Path.of(ref);
        if (asPath.isAbsolute() && isTemplateRoot(asPath)) {
            return asPath.normalize();
        }
        if (cwd != null) {
            Path rel = cwd.resolve(ref).normalize();
            if (Files.isDirectory(rel) && isTemplateRoot(rel)) return rel;
        }

        boolean twoSegments = ref.matches("[a-z][a-z0-9-]*/[a-z][a-z0-9-]*");
        if (ref.matches("[a-z][a-z0-9-]*") || twoSegments || ref.matches("[a-z]+/[a-z][a-z0-9-]*/[a-z][a-z0-9-]*")) {
            Optional<Path> indexed = indexedRoot(ref, lang, cwd);
            if (indexed.isPresent()) return indexed.get();

            // A two-segment ref that misses the catalog may be a GitHub owner/repo shorthand —
            // catalog first (framework/name is the documented meaning), remote clone on miss.
            if (twoSegments && looksRemoteTemplate(ref)) {
                return cloneRemoteTemplate(ref);
            }
            JkTemplatesConfig cfg = JkTemplatesConfig.resolve();
            throw new IllegalArgumentException("template short name not found: "
                    + ref
                    + " (looked under $JK_TEMPLATES, "
                    + JkDirs.templates()
                    + ", monorepo templates/; try `jk new -t "
                    + ref
                    + "` once to populate the store, or drop a .g8 with .jk-template.toml under "
                    + JkDirs.templates().resolve("<lang>").resolve("<framework>")
                    + "/"
                    + (twoSegments ? ref.substring(ref.indexOf('/') + 1) : ref)
                    + ".g8; official="
                    + cfg.officialUrl()
                    + ")");
        }
        if (looksRemoteTemplate(ref)) {
            return cloneRemoteTemplate(ref);
        }
        throw new IllegalArgumentException("unknown template ref: " + ref);
    }

    private static boolean looksRemoteTemplate(String ref) {
        String r = ref.strip();
        if (r.startsWith("https://") || r.startsWith("http://") || r.startsWith("git@") || r.startsWith("ssh://")) {
            return true;
        }
        if (r.startsWith(".") || r.startsWith("/") || r.contains("\\")) return false;
        return r.matches("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*(?:\\.g8)?(?:#.+)?");
    }

    /** Per-template-key clone serialization: the resident engine serves concurrent jk new. */
    private static final ConcurrentHashMap<String, Object> CLONE_LOCKS = new ConcurrentHashMap<>();

    private static Path cloneRemoteTemplate(String ref) throws IOException {
        Path cache = JkDirs.templates();
        Files.createDirectories(cache);
        String key = ref.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        Path dest = cache.resolve(key);
        synchronized (CLONE_LOCKS.computeIfAbsent(key, k -> new Object())) {
            if (!Files.isDirectory(dest) || isEmptyDir(dest)) {
                if (Files.exists(dest)) PathUtil.deleteRecursivelyOrThrow(dest);
                cloneInto(ref, cache, key, dest);
            }
        }
        if (isTemplateRoot(dest)) return dest.toAbsolutePath().normalize();
        try (var stream = Files.list(dest)) {
            Optional<Path> nested = stream.filter(Files::isDirectory)
                    .filter(NewProjectOps::isTemplateRoot)
                    .findFirst();
            if (nested.isPresent()) return nested.get().toAbsolutePath().normalize();
        }
        throw new IOException("git clone succeeded but no Giter8 layout under " + dest);
    }

    /**
     * Clone {@code ref} into a per-process staging dir, then rename into place — a concurrent
     * clone from ANOTHER engine process loses the rename instead of failing "destination
     * exists" mid-clone.
     */
    private static void cloneInto(String ref, Path cache, String key, Path dest) throws IOException {
        String url = ref;
        String rev = null;
        if (ref.matches("[A-Za-z0-9].*/[A-Za-z0-9].*") && !ref.contains("://") && !ref.startsWith("git@")) {
            String body = ref;
            int hash = body.lastIndexOf('#');
            if (hash > 0) {
                rev = body.substring(hash + 1);
                body = body.substring(0, hash);
            }
            if (body.endsWith(".g8")) body = body.substring(0, body.length() - 3);
            url = "https://github.com/" + body + ".git";
        }
        Path staging = cache.resolve(key + ".tmp-" + ProcessHandle.current().pid());
        if (Files.exists(staging)) PathUtil.deleteRecursivelyOrThrow(staging);
        List<String> args = new ArrayList<>();
        args.add("git");
        args.add("clone");
        args.add("--depth");
        args.add("1");
        if (rev != null && !rev.isBlank()) {
            args.add("--branch");
            args.add(rev);
        }
        args.add(url);
        args.add(staging.toString());
        Process p = JobWorkers.start(new ProcessBuilder(args).redirectErrorStream(true));
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git clone timed out after 120s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("git clone interrupted", e);
        }
        if (p.exitValue() != 0) {
            PathUtil.deleteRecursivelyOrThrow(staging);
            throw new IOException("git clone failed: " + (out.isBlank() ? "(no output)" : out.strip()));
        }
        try {
            Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException raced) {
            // Another process renamed first — its clone is equivalent; keep it.
            if (Files.isDirectory(dest) && !isEmptyDir(dest)) {
                PathUtil.deleteRecursivelyOrThrow(staging);
            } else {
                throw raced;
            }
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    static boolean isTemplateRoot(Path p) {
        if (p == null || !Files.isDirectory(p)) return false;
        if (Files.isRegularFile(p.resolve("default.properties"))) return true;
        if (Files.isDirectory(p.resolve("src/main/g8"))) return true;
        return Files.isRegularFile(p.resolve("src/main/g8/default.properties"));
    }

    /**
     * Restrict create to under the engine owner's home (and /tmp) — dashboard single-user posture.
     */
    static void assertAllowedParent(Path parent) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path tmp =
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path p = parent.toAbsolutePath().normalize();
        // normalize() is textual, so a symlink satisfies the allowlist while the writes land
        // wherever it points — and java.io.tmpdir is world-writable, so an unprivileged local user
        // can plant one. Compare resolved paths for anything that already exists.
        if (allowed(realOrSelf(p), realOrSelf(home), realOrSelf(tmp)) && allowed(p, home, tmp)) {
            return;
        }
        throw new IllegalArgumentException(
                "parentDir must be under $HOME or the system temp directory (got " + p + ")");
    }

    private static boolean allowed(Path p, Path home, Path tmp) {
        return p.startsWith(home) || p.equals(home) || p.startsWith(tmp) || p.equals(tmp);
    }

    /**
     * {@code path} with symlinks resolved; the nearest existing ancestor's real path when the leaf
     * does not exist yet (a new project's parent may be created on demand).
     */
    private static Path realOrSelf(Path path) {
        for (Path p = path; p != null; p = p.getParent()) {
            if (!Files.exists(p)) continue;
            try {
                Path real = p.toRealPath();
                Path rest = p.equals(path) ? null : p.relativize(path);
                return rest == null ? real : real.resolve(rest).normalize();
            } catch (IOException e) {
                return path; // unresolvable — fall back to the lexical form
            }
        }
        return path;
    }

    /** A language a template is filed under, lowercased; anything else is refused. */
    private static String parseLang(String lang) {
        String l = lang.strip().toLowerCase(Locale.ROOT);
        if (!NewProjectStep.LANGS.contains(l)) {
            throw new IllegalArgumentException("lang must be " + String.join("|", NewProjectStep.LANGS));
        }
        return l;
    }

    /**
     * The layout a template scaffold renders, first one set wins: an explicit {@code --layout}
     * (blank or {@code auto} leaves the choice to the template), a {@code jk_layout} template
     * parameter, the template's own {@code jk_layout} default in {@code default.properties}, then
     * traditional.
     */
    static Layout templateLayout(@Nullable String requested, Map<String, String> params, Path templateRoot) {
        Layout explicit = parseLayout(requested);
        if (requested != null && !requested.isBlank() && explicit != Layout.AUTO) return explicit;
        String param = params.get(TEMPLATE_LAYOUT_PARAM);
        if (param != null && !param.isBlank()) return scaffoldLayout(Layout.parse(param.strip()));
        return Giter8Apply.defaultProperty(templateRoot, TEMPLATE_LAYOUT_PARAM)
                .map(Giter8ShortNames::normalizeLayout)
                .filter(Giter8ShortNames.LAYOUT_SIMPLE::equals)
                .map(simple -> Layout.SIMPLE)
                .orElse(Layout.TRADITIONAL);
    }

    /** A template places its own tree, so {@code auto} there means the traditional one. */
    private static Layout scaffoldLayout(Layout layout) {
        return layout == Layout.AUTO ? Layout.TRADITIONAL : layout;
    }

    /** Scaffold default is traditional placement; blank → {@link Layout#TRADITIONAL}. */
    private static Layout parseLayout(@Nullable String layout) {
        if (layout == null || layout.isBlank()) return Layout.TRADITIONAL;
        return Layout.parse(layout);
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
