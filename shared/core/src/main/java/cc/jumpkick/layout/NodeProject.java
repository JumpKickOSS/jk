// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.NodeTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a node build is, from its {@code package.json}, lockfile and framework config files, with
 * every key the {@code [node]} table writes taking precedence. A pure function of the directory
 * and the table.
 *
 * @param packageManager {@code npm}, {@code pnpm}, {@code yarn} or {@code bun}
 * @param packageManagerVersion the exact version {@code packageManager} or {@code devEngines} names; {@code null} for none
 * @param framework a framework id ({@code vite}, {@code next}, …, {@code plain})
 * @param build what {@code jk build} runs; {@code null} when there is nothing to build
 * @param test the test script; {@code null} for none
 * @param dev the dev-server script; {@code null} for none
 * @param start the argv that runs a server output; {@code null} for a static output
 * @param out the build output directory, relative to the node directory
 * @param devPort the dev server's port; {@code null} when the framework has no known one
 * @param envPrefixes environment variable prefixes the build reads
 */
public record NodeProject(
        String packageManager,
        @Nullable String packageManagerVersion,
        String framework,
        NodeTable.@Nullable Command build,
        @Nullable String test,
        @Nullable String dev,
        @Nullable String start,
        String out,
        @Nullable Integer devPort,
        List<String> envPrefixes) {

    /** The file a node build is recognised by. */
    public static final String PACKAGE_JSON = "package.json";

    /** npm's placeholder test script: present, and never a test suite. */
    private static final String NPM_DEFAULT_TEST = "no test specified";

    public NodeProject {
        envPrefixes = List.copyOf(envPrefixes);
    }

    /** Whether the build output is a server jk can run, rather than static files. */
    public boolean server() {
        return start != null;
    }

    /** The node build in {@code dir} as {@code table} overrides it. */
    public static NodeProject infer(Path dir, NodeTable table) {
        Object pkg = packageJson(dir);
        Manager manager = manager(dir, pkg, table.skip());
        String framework = table.framework() != null ? table.framework() : framework(dir, pkg);
        Defaults d = defaults(framework, dir, pkg);
        Object scripts = MiniJson.get(pkg, "scripts");
        String pm = table.packageManager() != null ? table.packageManager() : manager.name();
        String pmVersion = pm.equals(manager.name()) ? manager.version() : null;
        return new NodeProject(
                pm,
                pmVersion,
                framework,
                table.build() != null ? table.build() : build(framework, scripts),
                table.test() != null ? table.test() : test(scripts),
                table.dev() != null ? table.dev() : dev(scripts),
                table.start() != null ? table.start() : d.start(),
                table.out() != null ? table.out() : d.out(),
                table.devPort() != null ? table.devPort() : d.devPort(),
                table.envPrefixes() != null ? table.envPrefixes() : d.envPrefixes());
    }

    /** The text of {@code package.json}'s script {@code name} in {@code dir}; {@code null} when it has none. */
    public static @Nullable String script(Path dir, String name) {
        return MiniJson.str(MiniJson.get(packageJson(dir), "scripts"), name);
    }

    /** {@code package.json} parsed; an empty object when it is unreadable as JSON. */
    static @Nullable Object packageJson(Path dir) {
        Path file = dir.resolve(PACKAGE_JSON);
        try {
            return MiniJson.parseRelaxed(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw new JkBuildParseException(file + " is not readable JSON: " + e.getMessage());
        }
    }

    private record Manager(String name, @Nullable String version) {}

    /**
     * The lockfile names the manager; {@code packageManager}, then {@code devEngines.packageManager},
     * name it when there is none, and give its version. Yarn 1 is refused unless the build is
     * skipped: then no step runs, and the manager is only named.
     */
    private static Manager manager(Path dir, @Nullable Object pkg, boolean skipped) {
        String declared = MiniJson.str(pkg, "packageManager");
        String name = null;
        String version = null;
        if (declared != null && declared.contains("@")) {
            name = declared.substring(0, declared.indexOf('@')).trim();
            version = declared.substring(declared.indexOf('@') + 1);
            int hash = version.indexOf('+');
            if (hash >= 0) version = version.substring(0, hash);
        } else if (MiniJson.get(MiniJson.get(pkg, "devEngines"), "packageManager") instanceof Map<?, ?> dev) {
            name = MiniJson.str(dev, "name");
            String range = MiniJson.str(dev, "version");
            version = range != null && Character.isDigit(range.charAt(0)) && !range.contains(" ") ? range : null;
        }
        String fromLock = null;
        if (Files.exists(dir.resolve("pnpm-lock.yaml"))) fromLock = "pnpm";
        else if (Files.exists(dir.resolve("yarn.lock"))) fromLock = "yarn";
        else if (Files.exists(dir.resolve("bun.lock")) || Files.exists(dir.resolve("bun.lockb"))) fromLock = "bun";
        else if (Files.exists(dir.resolve("package-lock.json")) || Files.exists(dir.resolve("npm-shrinkwrap.json"))) {
            fromLock = "npm";
        }
        String chosen = fromLock != null ? fromLock : name != null ? name : "npm";
        if (chosen.equals("yarn") && !skipped) refuseYarnClassic(dir, name, version);
        return new Manager(chosen, chosen.equals(name) ? version : null);
    }

    /** Yarn 1 (classic) is not supported: Berry, through {@code packageManager}, is. */
    private static void refuseYarnClassic(Path dir, @Nullable String declared, @Nullable String version) {
        boolean classic;
        if ("yarn".equals(declared) && version != null) {
            classic = version.startsWith("1.");
        } else {
            Path lock = dir.resolve("yarn.lock");
            classic = !Files.exists(dir.resolve(".yarnrc.yml")) || isClassicLock(lock);
        }
        if (classic) {
            throw new JkBuildParseException(dir.resolve(PACKAGE_JSON)
                    + ": Yarn 1 is not supported — run yarn set version stable && yarn install, then commit");
        }
    }

    private static boolean isClassicLock(Path lock) {
        try {
            return Files.exists(lock)
                    && Files.readString(lock, StandardCharsets.UTF_8).contains("yarn lockfile v1");
        } catch (IOException e) {
            return false;
        }
    }

    /** The framework from config files, dependencies and scripts, most specific first. */
    static String framework(Path dir, @Nullable Object pkg) {
        String scripts = scriptText(MiniJson.get(pkg, "scripts"));
        if (Files.exists(dir.resolve("angular.json")) || runs(scripts, "ng")) return "angular";
        if (config(dir, "next.config") || runs(scripts, "next")) return "next";
        if (config(dir, "nuxt.config") || runs(scripts, "nuxt")) return "nuxt";
        if (depends(pkg, "@sveltejs/kit")) return "sveltekit";
        if (config(dir, "astro.config") || runs(scripts, "astro")) return "astro";
        if (config(dir, "react-router.config") || runs(scripts, "react-router")) return "react-router";
        if (depends(pkg, "@tanstack/react-start") || depends(pkg, "@tanstack/solid-start")) return "tanstack-start";
        if (depends(pkg, "@solidjs/start")) return "solid-start";
        if (config(dir, "vite.config") || runs(scripts, "vite")) return "vite";
        return "plain";
    }

    private record Defaults(
            String out,
            @Nullable Integer devPort,
            List<String> envPrefixes,
            @Nullable String start) {}

    /** The framework matrix: output directory, dev port, public env prefixes and the server argv. */
    private static Defaults defaults(String framework, Path dir, @Nullable Object pkg) {
        String startScript = MiniJson.str(MiniJson.get(pkg, "scripts"), "start");
        return switch (framework) {
            case "vite" -> new Defaults("dist", 5173, List.of("VITE_"), null);
            case "tanstack-start", "solid-start" ->
                new Defaults(".output", 3000, List.of("VITE_"), "node .output/server/index.mjs");
            case "next" -> {
                String config = configText(dir, "next.config");
                if (config.contains("'export'") || config.contains("\"export\"")) {
                    yield new Defaults("out", 3000, List.of("NEXT_PUBLIC_"), null);
                }
                String start = config.contains("standalone") ? "node .next/standalone/server.js" : startScript;
                yield new Defaults(".next", 3000, List.of("NEXT_PUBLIC_"), start);
            }
            case "angular" -> new Defaults("dist/" + angularApp(dir, pkg) + "/browser", 4200, List.of(), null);
            case "nuxt" -> new Defaults(".output", 3000, List.of("NUXT_PUBLIC_"), "node .output/server/index.mjs");
            case "sveltekit" ->
                new Defaults(
                        "build",
                        5173,
                        List.of("PUBLIC_"),
                        depends(pkg, "@sveltejs/adapter-node") ? "node build/index.js" : null);
            case "astro" -> new Defaults("dist", 4321, List.of("PUBLIC_"), null);
            case "react-router" ->
                new Defaults("build", 5173, List.of("VITE_"), "react-router-serve build/server/index.js");
            default -> new Defaults("dist", null, List.of(), startScript);
        };
    }

    /** The build script, else Angular's {@code ng build} through npx; {@code null} when nothing builds. */
    private static NodeTable.@Nullable Command build(String framework, @Nullable Object scripts) {
        if (MiniJson.str(scripts, "build") != null) return NodeTable.Command.run("build");
        if (framework.equals("angular")) return NodeTable.Command.npx("ng build");
        return null;
    }

    private static @Nullable String test(@Nullable Object scripts) {
        String test = MiniJson.str(scripts, "test");
        return test == null || test.contains(NPM_DEFAULT_TEST) ? null : "test";
    }

    private static @Nullable String dev(@Nullable Object scripts) {
        for (String name : List.of("dev", "serve", "start")) {
            if (MiniJson.str(scripts, name) != null) return name;
        }
        return null;
    }

    /** Angular's application name: {@code defaultProject}, else the first project, else the package name. */
    private static String angularApp(Path dir, @Nullable Object pkg) {
        Path file = dir.resolve("angular.json");
        if (Files.exists(file)) {
            try {
                Object angular = MiniJson.parseRelaxed(Files.readString(file, StandardCharsets.UTF_8));
                String named = MiniJson.str(angular, "defaultProject");
                if (named != null) return named;
                if (MiniJson.get(angular, "projects") instanceof Map<?, ?> projects && !projects.isEmpty()) {
                    return String.valueOf(projects.keySet().iterator().next());
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // an unreadable angular.json falls through to the package name
            }
        }
        String name = MiniJson.str(pkg, "name");
        return name == null ? "app" : name.substring(name.lastIndexOf('/') + 1);
    }

    /** Whether any script runs {@code tool} as a command word ({@code vite build}, not {@code vitest}). */
    private static boolean runs(String scripts, String tool) {
        return Pattern.compile("(^|[\\s;&|(\"'])" + Pattern.quote(tool) + "(\\s|$)")
                .matcher(scripts)
                .find();
    }

    private static String scriptText(@Nullable Object scripts) {
        if (!(scripts instanceof Map<?, ?> map)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object v : map.values()) sb.append(' ').append(v).append(' ');
        return sb.toString();
    }

    private static boolean depends(@Nullable Object pkg, String name) {
        for (String section : List.of("dependencies", "devDependencies")) {
            if (MiniJson.get(MiniJson.get(pkg, section), name) != null) return true;
        }
        return false;
    }

    private static final List<String> CONFIG_EXTENSIONS = List.of(".js", ".mjs", ".cjs", ".ts", ".mts", ".cts");

    private static boolean config(Path dir, String base) {
        for (String ext : CONFIG_EXTENSIONS) {
            if (Files.exists(dir.resolve(base + ext))) return true;
        }
        return false;
    }

    private static String configText(Path dir, String base) {
        for (String ext : CONFIG_EXTENSIONS) {
            Path file = dir.resolve(base + ext);
            if (!Files.exists(file)) continue;
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }
}
