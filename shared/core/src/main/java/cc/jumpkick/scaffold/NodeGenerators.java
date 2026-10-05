// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The front-end frameworks {@code jk new --lang node -t <framework>} scaffolds, each by running the
 * framework's own generator non-interactively, so a new project is always the framework's newest
 * starter and nothing of jk's rots.
 */
public final class NodeGenerators {

    private NodeGenerators() {}

    /** What {@code jk build} makes of the framework's output: files to serve, or a server to run. */
    public enum Shape {
        STATIC,
        SERVER
    }

    /** One framework: its id, the shape of its output, and the generator argv with {@code {dir}} for the target. */
    public record Framework(String id, Shape shape, List<String> command) {}

    private static final String DIR = "{dir}";

    private static final List<Framework> ALL = List.of(
            vite("react"),
            vite("vue"),
            vite("preact"),
            vite("solid"),
            vite("svelte"),
            new Framework(
                    "next",
                    Shape.SERVER,
                    List.of(
                            "npx",
                            "--yes",
                            "create-next-app@latest",
                            DIR,
                            "--ts",
                            "--eslint",
                            "--app",
                            "--use-npm",
                            "--yes",
                            "--disable-git")),
            new Framework(
                    "angular",
                    Shape.STATIC,
                    List.of(
                            "npx",
                            "--yes",
                            "@angular/cli@latest",
                            "new",
                            DIR,
                            "--defaults",
                            "--skip-git",
                            "--package-manager=npm",
                            "--ssr=false")),
            new Framework(
                    "nuxt",
                    Shape.SERVER,
                    List.of(
                            "npx",
                            "--yes",
                            "create-nuxt@latest",
                            DIR,
                            "--template=minimal",
                            "--packageManager=npm",
                            "--gitInit=false")),
            new Framework(
                    "sveltekit",
                    Shape.SERVER,
                    List.of(
                            "npx",
                            "--yes",
                            "sv@latest",
                            "create",
                            DIR,
                            "--template=minimal",
                            "--types=ts",
                            "--no-add-ons",
                            "--install=npm")),
            new Framework(
                    "astro",
                    Shape.STATIC,
                    List.of(
                            "npx",
                            "--yes",
                            "create-astro@latest",
                            DIR,
                            "--template",
                            "basics",
                            "--install",
                            "--no-git",
                            "--yes")),
            new Framework(
                    "react-router",
                    Shape.SERVER,
                    List.of("npx", "--yes", "create-react-router@latest", DIR, "--yes", "--no-git-init", "--install")),
            new Framework(
                    "tanstack-start",
                    Shape.SERVER,
                    List.of(
                            "npx",
                            "--yes",
                            "@tanstack/create-start@latest",
                            DIR,
                            "--package-manager",
                            "npm",
                            "--no-git")));

    private static Framework vite(String template) {
        return new Framework(
                "vite-" + template,
                Shape.STATIC,
                List.of("npx", "--yes", "create-vite@latest", DIR, "--template", template + "-ts", "--no-interactive"));
    }

    /** Every framework, in the order help lists them. */
    public static List<Framework> all() {
        return ALL;
    }

    /** The framework {@code id} names, case-insensitively. */
    public static Optional<Framework> find(@Nullable String id) {
        if (id == null) return Optional.empty();
        String want = id.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(f -> f.id().equals(want)).findFirst();
    }

    /** The ids, comma-separated, for a message that lists them. */
    public static String ids() {
        return String.join(", ", ALL.stream().map(Framework::id).toList());
    }

    /**
     * The generator's argv for a project in directory {@code dir} (relative to where it runs), with
     * each {@code --param} after it: {@code k=v} as {@code --k=v}, a bare {@code k} as {@code --k},
     * and an argument that already starts with {@code -} as given.
     */
    public static List<String> argv(Framework framework, String dir, List<String> params) {
        List<String> out = new ArrayList<>();
        for (String part : framework.command()) out.add(part.equals(DIR) ? dir : part);
        for (String p : params) {
            if (p.isBlank()) continue;
            out.add(p.startsWith("-") ? p : "--" + p);
        }
        return out;
    }

    /** The lockfile names a frozen install reads; a generator that installed nothing leaves none. */
    public static final List<String> LOCKFILES =
            List.of("package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb");

    /**
     * {@code jk.toml} of a node module named {@code name} pinned to {@code nodeSpec}. A standalone
     * project ({@code group} non-null) carries its own identity; a workspace member inherits it.
     */
    public static String manifest(String name, String nodeSpec, @Nullable String group, @Nullable String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("name = \"").append(name).append("\"\n");
        if (group != null) {
            sb.append("group = \"").append(group).append("\"\n");
            sb.append("version = \"0.1.0\"\n");
        }
        sb.append('\n');
        if (source != null) sb.append("# node from ").append(source).append('\n');
        sb.append("node = ")
                .append(nodeSpec.matches("\\d+") ? nodeSpec : "\"" + nodeSpec + "\"")
                .append('\n');
        return sb.toString();
    }
}
