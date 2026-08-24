// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.docs.JkManual;
import cc.jumpkick.giter8.Giter8Apply;
import cc.jumpkick.giter8.Giter8Maven;
import cc.jumpkick.giter8.Giter8ShortNames;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.giter8.PluginTemplates;
import cc.jumpkick.giter8.TemplateSpec;
import cc.jumpkick.runtime.ProjectIds;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.NewScaffolder;
import cc.jumpkick.templates.OfficialTemplatesFreshen;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shared project creation used by {@code POST /api/projects}. Uses the same
 * {@link NewScaffolder} / {@link Giter8Apply} path as {@code jk new} — no second scaffolder.
 */
public final class NewProjectOps {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");
    private static final Pattern GROUP = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private NewProjectOps() {}

    public record Request(
            String name,
            String parentDir,
            String group,
            String lang,
            String layout,
            String template,
            boolean executable,
            String jdk,
            int javaRelease,
            boolean assembly,
            boolean nativeImage,
            boolean plugin,
            String kotlinModule,
            List<String> deps,
            boolean sample,
            boolean standalone,
            Map<String, String> templateParams,
            boolean relaxParent,
            String targetDir) {
        /** HTTP/MCP compact shape. */
        public Request(
                String name,
                String parentDir,
                String group,
                String lang,
                String layout,
                String template,
                boolean executable) {
            this(
                    name,
                    parentDir,
                    group,
                    lang,
                    layout,
                    template,
                    executable,
                    null,
                    0,
                    false,
                    false,
                    false,
                    null,
                    List.of(),
                    true,
                    true,
                    Map.of(),
                    false,
                    null);
        }
    }

    public record Result(Path path) {}

    /** Creation plus the durable project id (identity.toml materialized under the project home). */
    public record Created(Path path, String projectId, int filesWritten) {
        public Created(Path path, String projectId) {
            this(path, projectId, 0);
        }
    }

    /** What a create would write: target path and template-or-scaffold file list. Writes nothing. */
    public record Preview(String path, String template, List<String> files) {}

    /** Validated inputs shared by {@link #create} and {@link #preview}. */
    private record Prepared(
            String name,
            Path parent,
            Path target,
            String group,
            NewInputs.Language lang,
            String layout,
            String template,
            boolean executable,
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
                    deleteRecursively(child);
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
        Files.createDirectories(tmpRoot);
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
            deleteRecursively(scratch);
        }
    }

    private static void scaffoldInto(Prepared prep, Path target) throws IOException {
        Request req = prep.req();
        if (prep.template() != null) {
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
            // Resolution lang stays null unless the user asked: parseLang's java default is a
            // scaffolding default, not a search restriction — kotlin-only bare names must hit.
            String langName = req.lang() == null || req.lang().isBlank()
                    ? null
                    : prep.lang().hoconValue();
            var spec = resolveIndexed(prep.template(), langName, prep.parent());
            if (spec.isPresent() && TemplateSpec.SOURCE_PLUGIN.equals(spec.get().source())) {
                var s = spec.get();
                extracted = PluginTemplates.materialize(s.pluginId(), s.language(), s.framework(), s.name());
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
            if ("simple".equalsIgnoreCase(prep.layout())) {
                if (spec.isPresent() && !spec.get().supportsLayout(Giter8ShortNames.LAYOUT_SIMPLE)) {
                    throw new IOException(
                            "template " + prep.template() + " does not support --layout simple" + " (declared layouts: "
                                    + String.join(", ", spec.get().layouts()) + ")");
                }
                // Set for path/remote templates too (no indexed metadata): a dual-layout
                // template honors it, a single-layout one ignores it — never a silent drop
                // that renders a different tree than the flag asked for.
                params.putIfAbsent("simple", "yes");
            }
            try {
                Giter8Apply.apply(templateRoot, target, params, Giter8Maven.central(offline));
            } catch (IOException e) {
                throw new IOException(
                        "applying template " + prep.template() + " lang=" + langName + ": " + e.getMessage(), e);
            } finally {
                if (extracted != null) {
                    try {
                        deleteRecursively(extracted);
                    } catch (IOException ignored) {
                        // extract is under JkDirs.tmp(); don't mask the apply error
                    }
                }
            }
            if (!Files.isRegularFile(target.resolve("jk.toml"))) {
                throw new IOException("template did not produce jk.toml: " + prep.template());
            }
            if (req.standalone()) {
                JkManual.ensureAgentsGuide(target);
            }
            return;
        }
        Optional<String> main = Optional.empty();
        if (prep.executable() && !req.plugin()) {
            boolean compact = "simple".equalsIgnoreCase(prep.layout());
            main = Optional.of(
                    switch (prep.lang()) {
                        case JAVA -> prep.group() + ".Main";
                        case KOTLIN -> compact ? "MainKt" : prep.group() + ".MainKt";
                        case GROOVY -> compact ? "Main" : prep.group() + ".Main";
                        case SCALA -> compact ? "Main" : prep.group() + ".Main";
                    });
        }
        int hostMajor = Runtime.version().feature();
        int jdkMajor = req.javaRelease() > 0 ? req.javaRelease() : hostMajor;
        String jdk = req.jdk() == null || req.jdk().isBlank() ? String.valueOf(jdkMajor) : req.jdk();
        NewInputs inputs = new NewInputs(
                prep.group(),
                prep.name(),
                jdk,
                jdkMajor,
                jdkMajor,
                Optional.empty(),
                main,
                req.assembly(),
                req.nativeImage(),
                req.plugin(),
                prep.lang(),
                prep.layout(),
                req.kotlinModule() == null || req.kotlinModule().isBlank()
                        ? Optional.empty()
                        : Optional.of(req.kotlinModule()),
                req.deps() == null ? List.of() : req.deps(),
                req.sample(),
                target);
        NewScaffolder.write(inputs, req.standalone());
    }

    private static Prepared prepare(Request req) throws IOException {
        if (req == null) throw new IllegalArgumentException("missing body");
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) throw new IllegalArgumentException("missing \"name\"");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid name (use letters, digits, . _ -; start with a letter)");
        }
        String parentRaw = req.parentDir() == null ? "" : req.parentDir().strip();
        if (parentRaw.isEmpty()) throw new IllegalArgumentException("missing \"parentDir\"");
        // Same rules as the activity Build path: ~ and relatives resolve against user.home.
        Path parent = cc.jumpkick.host.PathUtil.resolveUserPath(parentRaw);
        if (!req.relaxParent()) assertAllowedParent(parent);
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("parentDir is not a directory: " + parent);
        }

        Path target;
        String targetRaw = req.targetDir() == null ? "" : req.targetDir().strip();
        if (!targetRaw.isEmpty()) {
            target = cc.jumpkick.host.PathUtil.resolveUserPath(targetRaw).normalize();
            // targetDir gets the same allowlist gate as parentDir.
            if (!req.relaxParent()) assertAllowedParent(target.getParent() != null ? target.getParent() : target);
        } else {
            target = parent.resolve(name).normalize();
            if (!target.startsWith(parent)) {
                throw new IllegalArgumentException("name escapes parentDir");
            }
        }
        if (Files.exists(target.resolve("jk.toml"))) {
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

        NewInputs.Language lang = parseLang(req.lang());
        String layout = parseLayout(req.layout());
        String template = req.template() == null || req.template().isBlank()
                ? null
                : req.template().strip();

        return new Prepared(name, parent, target, group, lang, layout, template, req.executable(), req);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    private static Optional<TemplateSpec> resolveIndexed(String ref, String lang, Path cwd) {
        try {
            return Giter8TemplateIndex.resolve(ref, lang, Giter8TemplateIndex.searchRoots(cwd));
        } catch (IllegalArgumentException e) {
            throw e;
        }
    }

    private static Optional<Path> indexedRoot(String ref, String lang, Path cwd) throws IOException {
        Optional<TemplateSpec> spec = resolveIndexed(ref, lang, cwd);
        if (spec.isEmpty()) {
            try {
                OfficialTemplatesFreshen.refreshQuiet(s -> {});
            } catch (Throwable ignored) {
                // best-effort
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

    static Path resolveTemplate(String ref, String lang, Path cwd) throws IOException {
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
                    + ", monorepo templates/; try `jk new --template "
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
                if (Files.exists(dest)) deleteRecursively(dest);
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
        if (Files.exists(staging)) deleteRecursively(staging);
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
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
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
            deleteRecursively(staging);
            throw new IOException("git clone failed: " + (out.isBlank() ? "(no output)" : out.strip()));
        }
        try {
            Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException raced) {
            // Another process renamed first — its clone is equivalent; keep it.
            if (Files.isDirectory(dest) && !isEmptyDir(dest)) {
                deleteRecursively(staging);
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

    private static NewInputs.Language parseLang(String lang) {
        if (lang == null || lang.isBlank()) return NewInputs.Language.JAVA;
        return switch (lang.strip().toLowerCase(Locale.ROOT)) {
            case "java" -> NewInputs.Language.JAVA;
            case "kotlin" -> NewInputs.Language.KOTLIN;
            case "groovy" -> NewInputs.Language.GROOVY;
            case "scala" -> NewInputs.Language.SCALA;
            default -> throw new IllegalArgumentException("lang must be java|kotlin|groovy|scala");
        };
    }

    private static String parseLayout(String layout) {
        if (layout == null || layout.isBlank()) return "traditional";
        String l = layout.strip().toLowerCase(Locale.ROOT);
        if ("simple".equals(l) || "traditional".equals(l)) return l;
        throw new IllegalArgumentException("layout must be traditional|simple");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
