// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.scaffold.Giter8LocalApply;
import cc.jumpkick.scaffold.Giter8TemplateIndex;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.NewScaffolder;
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
 * {@link NewScaffolder} / {@link Giter8LocalApply} path as {@code jk new} — no second scaffolder.
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
            String framework,
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
                boolean executable,
                String framework) {
            this(
                    name,
                    parentDir,
                    group,
                    lang,
                    layout,
                    template,
                    executable,
                    framework,
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

        public Request(
                String name,
                String parentDir,
                String group,
                String lang,
                String layout,
                String template,
                boolean executable,
                String framework,
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
                boolean relaxParent) {
            this(
                    name,
                    parentDir,
                    group,
                    lang,
                    layout,
                    template,
                    executable,
                    framework,
                    jdk,
                    javaRelease,
                    assembly,
                    nativeImage,
                    plugin,
                    kotlinModule,
                    deps,
                    sample,
                    standalone,
                    templateParams,
                    relaxParent,
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
        scaffoldInto(prep, prep.target());
        return new Result(prep.target());
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
            var identity = cc.jumpkick.builds.ProjectIdentity.resolve(result.path());
            cc.jumpkick.builds.ProjectIdentity.IdentityFile.write(
                    cc.jumpkick.builds.ProjectBuilds.projectHome(identity.id()), identity);
            cc.jumpkick.runtime.ProjectIds.refresh(result.path().toString());
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
        Path tmpRoot = cc.jumpkick.util.JkDirs.tmp();
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
            Path templateRoot = resolveTemplate(prep.template(), prep.parent());
            Map<String, String> params = new LinkedHashMap<>();
            if (req.templateParams() != null) params.putAll(req.templateParams());
            params.putIfAbsent("name", prep.name());
            params.putIfAbsent("package", prep.group());
            params.putIfAbsent("organization", prep.group());
            if (prep.group() != null && !prep.group().isBlank()) {
                params.putIfAbsent("group", prep.group());
            }
            Giter8LocalApply.apply(templateRoot, target, params);
            if (!Files.isRegularFile(target.resolve("jk.toml"))) {
                throw new IOException("template did not produce jk.toml: " + prep.template());
            }
            return;
        }
        boolean spring = "spring".equalsIgnoreCase(nullToEmpty(req.framework()));
        boolean grails = "grails".equalsIgnoreCase(nullToEmpty(req.framework()));
        boolean quarkus = "quarkus".equalsIgnoreCase(nullToEmpty(req.framework()));
        boolean micronaut = "micronaut".equalsIgnoreCase(nullToEmpty(req.framework()));
        Optional<String> main = Optional.empty();
        if (prep.executable() && !req.plugin()) {
            boolean compact = "simple".equalsIgnoreCase(prep.layout());
            main = Optional.of(
                    switch (prep.lang()) {
                        case JAVA -> prep.group() + ".Main";
                        case KOTLIN -> compact ? "MainKt" : prep.group() + ".MainKt";
                        case GROOVY -> compact ? "Main" : prep.group() + ".Main";
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
                spring,
                grails,
                quarkus,
                micronaut,
                req.plugin(),
                prep.lang(),
                prep.layout(),
                req.kotlinModule() == null || req.kotlinModule().isBlank()
                        ? Optional.empty()
                        : Optional.of(req.kotlinModule()),
                req.deps() == null ? List.of() : req.deps(),
                req.sample(),
                target);
        NewScaffolder.write(inputs, req.standalone(), NewProjectOps::frameworkScaffold);
    }

    private static NewScaffolder.ScaffoldFiles frameworkScaffold(NewInputs inputs) throws IOException {
        var params = new LinkedHashMap<String, String>();
        params.put("plugin", inputs.frameworkPluginFlag());
        params.put("lang", inputs.lang().hoconValue());
        params.put("package", inputs.group());
        params.put("group", inputs.group());
        params.put("name", inputs.name());
        params.put("version", "0.1.0");
        params.putIfAbsent("quarkus.version", cc.jumpkick.model.ToolDefaults.QUARKUS_PLATFORM_FLOOR);
        params.put("simpleLayout", String.valueOf(inputs.isSimpleLayout()));
        params.put("sample", String.valueOf(inputs.sample()));
        params.put("baseToml", cc.jumpkick.scaffold.NewJkBuildRenderer.render(inputs));
        var files = cc.jumpkick.runtime.GenerateOps.generate(inputs.directory(), "scaffold", params);
        if (files.error() != null && !files.error().isBlank()) {
            throw new IOException(files.error());
        }
        return new NewScaffolder.ScaffoldFiles(files.paths(), files.contents());
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
        Path parent = cc.jumpkick.util.PathUtil.resolveUserPath(parentRaw);
        if (!req.relaxParent()) assertAllowedParent(parent);
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("parentDir is not a directory: " + parent);
        }

        Path target;
        String targetRaw = req.targetDir() == null ? "" : req.targetDir().strip();
        if (!targetRaw.isEmpty()) {
            target = cc.jumpkick.util.PathUtil.resolveUserPath(targetRaw).normalize();
            // targetDir gets the same allowlist gate as parentDir — the old
            // target.startsWith(target.getParent()) check was a tautology, so a
            // relaxParent=false wire caller could scaffold anywhere (JK-2166).
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

    /**
     * Resolve a template ref: absolute/relative path, short name via {@link
     * Giter8TemplateIndex#resolveShortName} (local roots + monorepo dogfood + official cache
     * freshen from the public {@code JumpKickOSS/jk-templates} repo).
     */
    static Path resolveTemplate(String ref, Path cwd) throws IOException {
        Path asPath = Path.of(ref);
        if (asPath.isAbsolute() && isTemplateRoot(asPath)) {
            return asPath.normalize();
        }
        if (cwd != null) {
            Path rel = cwd.resolve(ref).normalize();
            if (Files.isDirectory(rel) && isTemplateRoot(rel)) return rel;
        }

        if (ref.matches("[a-z][a-z0-9-]*")) {
            // Disk + official monorepo cache (freshen clones if missing / incomplete).
            Optional<Path> indexed = Giter8TemplateIndex.resolveShortName(ref, cwd);
            if (indexed.isPresent()) return indexed.get();

            JkTemplatesConfig cfg = JkTemplatesConfig.resolve();
            throw new IllegalArgumentException("template short name not found: "
                    + ref
                    + " (looked under $JK_TEMPLATES, ~/.jk/templates, monorepo templates/,"
                    + " official cache; try `jk new --template "
                    + ref
                    + "` once to populate the cache, or install under ~/.jk/templates/"
                    + ref
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
        Path cache = Path.of(System.getProperty("user.home"), ".jk", "cache", "templates");
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
     * exists" mid-clone (JK-2166).
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
            default -> throw new IllegalArgumentException("lang must be java|kotlin|groovy");
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
