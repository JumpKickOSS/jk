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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shared project creation used by {@code POST /api/projects} (JK-1193). Uses the same
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
            String framework) {}

    public record Result(Path path) {}

    public static Result create(Request req) throws IOException {
        if (req == null) throw new IllegalArgumentException("missing body");
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) throw new IllegalArgumentException("missing \"name\"");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid name (use letters, digits, . _ -; start with a letter)");
        }
        String parentRaw = req.parentDir() == null ? "" : req.parentDir().strip();
        if (parentRaw.isEmpty()) throw new IllegalArgumentException("missing \"parentDir\"");
        Path parent = Path.of(parentRaw);
        if (!parent.isAbsolute()) throw new IllegalArgumentException("parentDir must be an absolute path");
        parent = parent.toAbsolutePath().normalize();
        assertAllowedParent(parent);
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("parentDir is not a directory: " + parent);
        }

        Path target = parent.resolve(name).normalize();
        if (!target.startsWith(parent)) {
            throw new IllegalArgumentException("name escapes parentDir");
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

        if (template != null) {
            Path templateRoot = resolveTemplate(template, parent);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("name", name);
            params.put("package", group);
            params.put("organization", group);
            Giter8LocalApply.apply(templateRoot, target, params);
            if (!Files.isRegularFile(target.resolve("jk.toml"))) {
                throw new IOException("template did not produce jk.toml: " + template);
            }
            return new Result(target);
        }

        boolean spring = "spring".equalsIgnoreCase(nullToEmpty(req.framework()));
        boolean grails = "grails".equalsIgnoreCase(nullToEmpty(req.framework()));
        boolean quarkus = "quarkus".equalsIgnoreCase(nullToEmpty(req.framework()));
        if (spring || grails || quarkus) {
            // Framework scaffolds need ScaffoldOps; wire plain path first — frameworks via CLI for now
            // until we inject ScaffoldOps here. Reject with a clear message.
            throw new IllegalArgumentException("framework scaffolds from the web are not enabled yet; use: jk new --"
                    + (spring ? "spring" : grails ? "grails" : "quarkus")
                    + " "
                    + name);
        }

        boolean executable = req.executable();
        Optional<String> main = Optional.empty();
        if (executable) {
            main = Optional.of(
                    switch (lang) {
                        case JAVA -> group + ".Main";
                        case KOTLIN -> group + ".MainKt";
                        case GROOVY -> group + ".Main";
                    });
        }

        int jdkMajor = Runtime.version().feature();
        NewInputs inputs = new NewInputs(
                group,
                name,
                String.valueOf(jdkMajor),
                jdkMajor,
                jdkMajor,
                Optional.empty(),
                main,
                false,
                false,
                false,
                false,
                false,
                false,
                lang,
                layout,
                Optional.empty(),
                List.of(),
                true,
                target);
        NewScaffolder.write(inputs, true, null);
        return new Result(target);
    }

    /**
     * Resolve a template ref: absolute/relative path, short name via {@link
     * Giter8TemplateIndex#resolveShortName} (local roots + monorepo dogfood + official cache
     * freshen from the public {@code jkbuild/jk-templates} repo).
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
        throw new IllegalArgumentException("unknown template ref: " + ref);
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
        // can plant one. Compare resolved paths for anything that already exists (JK-1485).
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
        if (layout == null || layout.isBlank()) return "simple";
        String l = layout.strip().toLowerCase(Locale.ROOT);
        if ("simple".equals(l) || "traditional".equals(l)) return l;
        throw new IllegalArgumentException("layout must be simple|traditional");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
