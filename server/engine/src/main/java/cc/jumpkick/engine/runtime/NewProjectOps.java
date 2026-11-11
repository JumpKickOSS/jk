// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.docs.AgentChoice;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.giter8.Giter8Apply;
import cc.jumpkick.giter8.Giter8Maven;
import cc.jumpkick.giter8.Giter8ShortNames;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.giter8.JkTemplateToml;
import cc.jumpkick.giter8.MavenVersionLookup;
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
import java.nio.file.InvalidPathException;
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

    /** What answers a template's {@code maven()} properties in place of the repositories; unset in production. */
    private static volatile @Nullable MavenVersionLookup versionLookup;

    /**
     * Test seam: {@code lookup} answers every template's {@code maven()} properties, so a scaffold
     * reaches no repository; {@code null} restores the repositories.
     */
    static void versionLookup(@Nullable MavenVersionLookup lookup) {
        versionLookup = lookup;
    }

    /** The template parameter (and {@code default.properties} key) naming a template's layout. */
    static final String TEMPLATE_LAYOUT_PARAM = "jk_layout";

    /**
     * One project to create. {@code cwd} is the caller's working directory, the one base a relative
     * template path resolves against (as it does when the form is built); {@code null} means
     * {@code parentDir}. A {@code standalone} project with no {@code java} answer targets the
     * machine's default JDK ({@link NewProjectJava}); a module keeps whatever its caller sent.
     * {@code agents} is the agents setting a standalone project is wired for ({@link
     * NewProjectAgents}); null wires nothing, and a module is never wired, its workspace root is.
     */
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
            @Nullable String targetDir,
            @Nullable String cwd,
            @Nullable AgentChoice agents) {
        /** As the canonical shape, wiring no agent files. */
        public Request(
                @Nullable String name,
                @Nullable String parentDir,
                @Nullable String group,
                @Nullable String lang,
                @Nullable String layout,
                @Nullable String template,
                boolean standalone,
                Map<String, String> templateParams,
                boolean relaxParent,
                @Nullable String targetDir,
                @Nullable String cwd) {
            this(
                    name,
                    parentDir,
                    group,
                    lang,
                    layout,
                    template,
                    standalone,
                    templateParams,
                    relaxParent,
                    targetDir,
                    cwd,
                    null);
        }

        /** HTTP compact shape: a standalone project, relative template paths against {@code parentDir}. */
        public Request(
                @Nullable String name,
                @Nullable String parentDir,
                @Nullable String group,
                @Nullable String lang,
                @Nullable String layout,
                @Nullable String template) {
            this(name, parentDir, group, lang, layout, template, true, Map.of(), false, null, null);
        }

        /** This request, wiring the project for {@code choice}. */
        public Request withAgents(@Nullable AgentChoice choice) {
            return new Request(
                    name,
                    parentDir,
                    group,
                    lang,
                    layout,
                    template,
                    standalone,
                    templateParams,
                    relaxParent,
                    targetDir,
                    cwd,
                    choice);
        }

        private boolean wired() {
            return standalone && agents != null;
        }
    }

    public record Result(Path path) {}

    /**
     * Creation plus the durable project id (identity.toml materialized under the project home).
     * {@code filesWritten} counts the template's files; {@code agents} is what the agent wiring
     * wrote and left to the user, empty when the request wired nothing.
     */
    public record Created(Path path, @Nullable String projectId, int filesWritten, AgentSetup.Report agents) {
        public Created(Path path, @Nullable String projectId) {
            this(path, projectId, 0, NO_AGENTS);
        }
    }

    private static final AgentSetup.Report NO_AGENTS = new AgentSetup.Report(List.of(), List.of());

    /**
     * What a create would write: the target path, the template, every file's path under the target,
     * and the {@code jk.toml} it would hold. Writes nothing.
     */
    public record Preview(String path, @Nullable String template, List<String> files, String manifest) {}

    /** Validated inputs shared by {@link #create} and {@link #preview}. */
    private record Prepared(
            String name,
            Path parent,
            Path cwd,
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
     * creation already succeeded; a null id just means id-routing waits for the first build. Then
     * the agent wiring the request asks for, so every surface's project gets the same files.
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
        AgentSetup.Report agents =
                req.wired() ? NewProjectAgents.wire(result.path(), Objects.requireNonNull(req.agents())) : NO_AGENTS;
        return new Created(result.path(), projectId, files, agents);
    }

    /**
     * The exact file set a create would produce, discovered by scaffolding into a scratch
     * directory and deleting it — never a guessed list, and the real target is untouched. Runs
     * the same validation as {@link #create}, so an occupied target refuses here too. The agent
     * files a create would wire are listed with the template's.
     */
    public static Preview preview(Request req) throws IOException {
        Prepared prep = prepare(req);
        Path tmpRoot = JkDirs.tmp();
        OwnerOnlyFiles.createDirectories(tmpRoot);
        Path scratch = Files.createTempDirectory(tmpRoot, "jk-new-preview-");
        Path probe = scratch.resolve(prep.name());
        try {
            scaffoldInto(prep, probe);
            if (req.wired()) NewProjectAgents.wire(probe, Objects.requireNonNull(req.agents()));
            List<String> files = new ArrayList<>();
            try (var walk = Files.walk(probe)) {
                walk.filter(Files::isRegularFile)
                        .map(f -> probe.relativize(f).toString().replace('\\', '/'))
                        .sorted()
                        .forEach(files::add);
            }
            String manifest = Files.readString(probe.resolve(ManifestPaths.MANIFEST));
            return new Preview(prep.target().toString(), prep.template(), List.copyOf(files), manifest);
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
        if (req.standalone() && !params.containsKey("java")) {
            String host = NewProjectJava.hostMajor();
            if (host != null) params.put("java", host);
        }
        boolean offline = SessionContext.current().config().offlineOr(false);
        // Resolution lang stays null unless the user asked: a bare name then searches every
        // language, so a kotlin-only template is reached without --lang.
        String langName = prep.lang();
        Located located = locate(prep.template(), langName, prep.cwd());
        Path templateRoot = located.root();
        Path extracted = located.extracted();
        Optional<TemplateSpec> spec = located.spec();
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
            Map<String, @Nullable String> bound;
            try {
                // The catalogue lint again, for a template reached by path or URL rather than
                // through the index; then the asks settle and bind.
                bound = TemplateAnswers.bind(JkTemplateToml.load(templateRoot).orElse(null), params);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("template " + prep.template() + ": " + e.getMessage(), e);
            }
            MavenVersionLookup lookup = versionLookup;
            Giter8Apply.apply(
                    templateRoot,
                    target,
                    bound,
                    lookup != null
                            ? lookup
                            : Giter8Maven.fromRepositories(
                                    () -> RepoGroupBuilder.buildDefault(JkStores.storeCas()), offline));
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
                    "invalid name '" + name + "': use lowercase letters, digits and -, starting with a letter");
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

        String cwdRaw = req.cwd() == null ? "" : req.cwd().strip();
        Path cwd = cwdRaw.isEmpty() ? parent : PathUtil.resolveUserPath(cwdRaw);
        return new Prepared(name, parent, cwd, target, group, lang, template, req);
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
     * and its default layout. Writes nothing; a template in a jar is extracted and removed again. A
     * template directory's id is its absolute path, so a scaffold that sends the id back reaches the
     * same directory whatever its own base.
     */
    public static TemplateForm templateForm(String ref, @Nullable String lang, Path cwd) throws IOException {
        String langName = lang == null || lang.isBlank() ? null : parseLang(lang);
        Located located = locate(ref, langName, cwd);
        Path root = located.root();
        Path extracted = located.extracted();
        Optional<TemplateSpec> spec = located.spec();
        try {
            JkTemplateToml.Meta meta = JkTemplateToml.load(root).orElse(null);
            List<String> layouts = spec.map(TemplateSpec::layouts)
                    .orElse(meta == null ? List.of(Layout.TOKEN_TRADITIONAL, Layout.TOKEN_SIMPLE) : meta.layouts());
            String preferred = templateLayout(null, Map.of(), root).token();
            String defaultLayout = layouts.contains(preferred) ? preferred : layouts.getFirst();
            return new TemplateForm(located.id(ref), layouts, defaultLayout, meta);
        } finally {
            if (extracted != null) PathUtil.deleteRecursivelyOrThrow(extracted);
        }
    }

    /**
     * Where a template ref led: the tree to read, the catalogue entry when it is one, and the temp
     * copy to delete when it came out of a jar.
     */
    private record Located(
            Path root,
            Optional<TemplateSpec> spec,
            @Nullable Path extracted,
            boolean local) {
        /** The id a form answers with: a directory's absolute path, a catalogue entry's id, else the ref. */
        String id(String ref) {
            if (local) return root.toString();
            return spec.map(TemplateSpec::id).orElse(ref);
        }
    }

    /**
     * Resolves {@code ref} against {@code cwd}: a template directory there first (so a catalogue
     * name that is also a template directory under {@code cwd} is the directory), then the
     * catalogue, then a remote clone.
     */
    private static Located locate(String ref, @Nullable String lang, Path cwd) throws IOException {
        Optional<Path> local = localTemplate(ref, cwd);
        if (local.isPresent()) return new Located(local.get(), Optional.empty(), null, true);
        Optional<TemplateSpec> spec = resolveIndexed(ref, lang, cwd);
        if (spec.isPresent() && inJar(spec.get())) {
            Path extracted = extract(spec.get());
            return new Located(extracted, spec, extracted, false);
        }
        if (spec.isPresent() && spec.get().root() != null) {
            return new Located(Objects.requireNonNull(spec.get().root()), spec, null, false);
        }
        Path root = resolveTemplate(ref, lang, cwd);
        // resolveTemplate may have freshened the official cache; re-resolve so layout metadata is
        // honored identically on cold and warm caches.
        if (spec.isEmpty()) spec = resolveIndexed(ref, lang, cwd);
        return new Located(root, spec, null, false);
    }

    /**
     * {@code ref} as a template directory: absolute, or relative to {@code cwd}. A directory counts
     * only when it holds a template ({@link #isTemplateRoot}), so a catalogue name that matches some
     * other directory still reaches the catalogue; a URL is never a directory.
     */
    static Optional<Path> localTemplate(String ref, Path cwd) {
        String r = ref.strip();
        if (r.isEmpty() || r.contains("://") || r.startsWith("git@")) return Optional.empty();
        Path dir;
        try {
            dir = cwd.resolve(r).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        return isTemplateRoot(dir) ? Optional.of(dir) : Optional.empty();
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
        Optional<Path> local = localTemplate(ref, cwd);
        if (local.isPresent()) return local.get();

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
            throw new IllegalArgumentException("template not found: " + ref + "\nlooked in $JK_TEMPLATES, "
                    + JkDirs.templates() + ", templates/ and " + cfg.officialUrl() + "\nto add one, write "
                    + JkDirs.templates().resolve("<lang>").resolve("<framework>") + "/"
                    + (twoSegments ? ref.substring(ref.indexOf('/') + 1) : ref) + ".g8 with a .jk-template.toml");
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
        TemplateClone.Result result = TemplateClone.run(args, TemplateClone.TIMEOUT);
        if (result.exit() != 0) {
            PathUtil.deleteRecursivelyOrThrow(staging);
            String out = result.output();
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

    /** A template's directory: it holds a {@code .jk-template.toml}, a {@code default.properties} or {@code src/main/g8}. */
    static boolean isTemplateRoot(Path p) {
        if (p == null || !Files.isDirectory(p)) return false;
        return Files.isRegularFile(p.resolve(".jk-template.toml"))
                || Files.isRegularFile(p.resolve("default.properties"))
                || Files.isDirectory(p.resolve("src/main/g8"));
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
