// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.model.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.maven.model.Model;
import org.jspecify.annotations.Nullable;

/**
 * Where an imported frontend-maven-plugin build lands. In a JVM module whose bundler writes into the
 * module's own war or resources, it is a side-by-side build ({@code [node] dir}); a frontend in a
 * {@code pom} module, or one writing into another module, becomes a generated node module that the
 * consuming module depends on. Either way the bundler writes to {@code dist/} and jk places it, so
 * nothing is built into {@code src/}.
 */
final class FrontendImport {

    /** The node module's output directory once the bundler config is rewritten. */
    static final String OUT = "dist";

    private static final List<String> FRONTEND_FILES = List.of(
            "package.json",
            "package-lock.json",
            "npm-shrinkwrap.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "pnpm-workspace.yaml",
            "bun.lock",
            "bun.lockb",
            ".yarnrc.yml",
            ".yarn",
            ".npmrc",
            ".nvmrc",
            ".node-version",
            ".babelrc",
            ".browserslistrc",
            ".swcrc",
            ".prettierrc",
            ".prettierrc.json",
            ".prettierignore",
            ".stylelintrc",
            ".stylelintrc.js",
            ".stylelintrc.json",
            ".eslintrc",
            ".eslintrc.js",
            ".eslintrc.cjs",
            ".eslintrc.json",
            ".eslintignore");
    private static final List<String> FRONTEND_PREFIXES = List.of(
            "webpack.config.",
            "vite.config.",
            "vitest.config.",
            "jest.config.",
            "babel.config.",
            "postcss.config.",
            "tailwind.config.",
            "eslint.config.",
            "prettier.config.",
            "stylelint.config.",
            "tsconfig");

    /** Directories a front end's config may name that are never its sources. */
    private static final List<String> NEVER_MOVED = List.of("node_modules", OutputDirs.TARGET, "build", "dist", ".git");

    private FrontendImport() {}

    /** A frontend that becomes its own module, with what is known of where its output went. */
    record Relocation(FrontendPlugin.Frontend frontend, Path moduleDir, BundlerOutput.@Nullable Found output) {}

    /** A module's build with its side-by-side frontend applied, or the frontend left for the workspace. */
    record Applied(JkBuild build, @Nullable Relocation relocation, FrontendFiles files) {}

    /** A member module's frontend, placed. {@code standalone}: the POM is imported without a workspace. */
    static Applied member(
            Model model, JkBuild build, ImportReport.Builder report, boolean standalone, boolean inPlace) {
        Path moduleDir = moduleDir(model);
        if (moduleDir == null) return new Applied(build, null, FrontendFiles.NONE);
        FrontendPlugin.Frontend frontend = FrontendPlugin.map(model, moduleDir, report);
        if (frontend == null) return new Applied(build, null, FrontendFiles.NONE);
        BundlerOutput.Found output = BundlerOutput.find(frontend.dir());
        Path out = output == null ? null : output.target(frontend.dir());
        boolean elsewhere = out != null && !out.startsWith(moduleDir);
        if ("pom".equals(model.getPackaging()) || elsewhere) {
            if (standalone) {
                report.warning("`" + FrontendPlugin.ARTIFACT + "` builds a front end "
                        + (elsewhere ? "into `" + out + "`, outside this module" : "in a `pom` module")
                        + "; importing the workspace's root POM makes it a node module of its own");
                return new Applied(build, null, FrontendFiles.NONE);
            }
            return new Applied(build, new Relocation(frontend, moduleDir, output), FrontendFiles.NONE);
        }
        if (frontend.workingDirectory().equals(".")) {
            report.warning("`" + FrontendPlugin.ARTIFACT + "` builds the front end at the module's root, beside"
                    + " its JVM sources: move package.json and the front end's sources into src/main/node,"
                    + " then import again");
            return new Applied(build, null, FrontendFiles.NONE);
        }
        String dir =
                frontend.workingDirectory().equals(NodeTable.SIDE_BY_SIDE_DIR) ? null : frontend.workingDirectory();
        Roots roots = roots(moduleDir, out, report);
        if (inPlace) {
            // The bundler writes where its config says; the output is read from there.
            String outRel = out == null ? null : rel(frontend.dir(), out);
            if (output == null) reportUnreadOutput(frontend, report);
            NodeTable table = placed(frontend.table(), dir, roots, outRel, List.of());
            return new Applied(withFrontend(build, build.project(), frontend, table), null, FrontendFiles.NONE);
        }
        NodeTable table = placed(frontend.table(), dir, roots);
        FrontendFiles files = output == null ? FrontendFiles.NONE : rewrite(output, output.file(), report);
        if (output == null) reportUnreadOutput(frontend, report);
        return new Applied(withFrontend(build, build.project(), frontend, table), null, files);
    }

    /** The workspace with every relocated frontend as a generated node module beside the members. */
    record Relocated(JkBuild root, Map<String, JkBuild> members, FrontendFiles files) {}

    static Relocated relocate(
            Path rootDir,
            JkBuild root,
            Map<String, JkBuild> members,
            List<Relocation> relocations,
            ImportReport.Builder report) {
        if (relocations.isEmpty()) return new Relocated(root, members, FrontendFiles.NONE);
        Map<String, JkBuild> out = new LinkedHashMap<>(members);
        List<String> modules =
                new ArrayList<>(root.workspaceOpt().map(Workspace::modules).orElse(List.of()));
        FrontendFiles files = FrontendFiles.NONE;
        for (Relocation r : relocations) {
            FrontendPlugin.Frontend frontend = r.frontend();
            Path webDir = freeDir(r.moduleDir().resolve("web"), out.keySet(), rootDir);
            String webRel = rel(rootDir, webDir);
            String name = freeName(root, out);
            Path target = r.output() == null ? null : r.output().target(frontend.dir());
            String consumer = target == null ? null : consumer(rootDir, out.keySet(), target);
            Roots roots = consumer == null ? Roots.NONE : roots(rootDir.resolve(consumer), target, report);
            if (target != null && consumer == null) {
                report.warning("`" + FrontendPlugin.ARTIFACT + "` writes into `" + rel(rootDir, target)
                        + "`, which is in no module; `" + webRel + "` packages its output as a resource jar");
            }
            NodeTable table = placed(frontend.table(), null, roots);
            Project project = Project.builder(
                            root.project().group(), name, root.project().version())
                    .build();
            JkBuild web = withFrontend(
                    JkBuild.builder(project).build(BuildBlock.EMPTY).build(), project, frontend, table);
            out.put(webRel, web);
            modules.add(webRel);
            List<FrontendFiles.Move> moves = moves(frontend.dir(), webDir, r.output(), target, rootDir, out.keySet());
            for (FrontendFiles.Move m : moves) {
                report.warning("front end moves into the generated node module `" + webRel + "`: `"
                        + rel(rootDir, m.from()) + "` → `" + rel(rootDir, m.to()) + "`");
            }
            FrontendFiles moved = new FrontendFiles(moves, Map.of());
            if (r.output() != null) {
                Path config =
                        webDir.resolve(frontend.dir().relativize(r.output().file()));
                moved = moved.plus(rewrite(r.output(), config, report));
            } else {
                reportUnreadOutput(frontend, report);
            }
            files = files.plus(moved);
            if (consumer != null) {
                out.put(consumer, dependOn(out.get(consumer), name));
                report.warning("`" + consumer + "` depends on the generated node module `" + webRel + "`, which"
                        + " places its output "
                        + (roots.webapp() != null
                                ? "in the war under `" + (roots.webapp().isEmpty() ? "/" : roots.webapp()) + "`"
                                : "on the classpath under `"
                                        + (roots.classpath() == null ? "static" : roots.classpath()) + "`"));
            }
        }
        Workspace ws = root.workspaceOpt().orElseThrow();
        JkBuild newRoot = root.withWorkspace(new Workspace(modules, ws.dependencies()));
        return new Relocated(newRoot, out, files);
    }

    /**
     * The workspace with every relocated frontend built by the module its output lands in, where it
     * stands: that module's {@code [node] dir} names the frontend's directory (above the module when
     * the frontend sits at the workspace root), {@code out} the path its bundler writes, and {@code
     * inputs} the frontend's own files, so the rest of the workspace never keys its build. A frontend
     * whose output is unread, or lands in no module, is a row: only {@code jk import} can give it a
     * module of its own.
     */
    static Relocated consumeInPlace(
            Path rootDir,
            JkBuild root,
            Map<String, JkBuild> members,
            List<Relocation> relocations,
            ImportReport.Builder report) {
        Map<String, JkBuild> out = new LinkedHashMap<>(members);
        for (Relocation r : relocations) {
            FrontendPlugin.Frontend frontend = r.frontend();
            Path target = r.output() == null ? null : r.output().target(frontend.dir());
            String consumer = target == null ? null : consumer(rootDir, out.keySet(), target);
            if (target == null || consumer == null) {
                report.error("`" + FrontendPlugin.ARTIFACT + "` in `" + rel(rootDir, frontend.dir()) + "` writes "
                        + (target == null ? "where its bundler config does not say" : "outside every module")
                        + "; the in-place build cannot place it");
                continue;
            }
            Path consumerDir = rootDir.resolve(consumer).normalize();
            Roots roots = roots(consumerDir, target, report);
            List<String> inputs = new ArrayList<>();
            Path scratch = freeDir(rootDir.resolve("web"), out.keySet(), rootDir);
            for (FrontendFiles.Move m : moves(frontend.dir(), scratch, r.output(), target, rootDir, out.keySet())) {
                inputs.add(rel(frontend.dir(), m.from()));
            }
            NodeTable table = placed(
                    frontend.table(), rel(consumerDir, frontend.dir()), roots, rel(frontend.dir(), target), inputs);
            JkBuild built = Objects.requireNonNull(out.get(consumer), "consumer module");
            out.put(consumer, withFrontend(built, built.project(), frontend, table));
            report.warning("`" + consumer + "` builds the front end in `" + rel(rootDir, frontend.dir())
                    + "` where it stands, and packages what it writes to `" + rel(rootDir, target) + "`");
        }
        return new Relocated(root, out, FrontendFiles.NONE);
    }

    /** Where output that a module wrote into itself goes: under the classpath or into the war. */
    private record Roots(
            @Nullable String classpath, @Nullable String webapp) {
        static final Roots NONE = new Roots(null, null);
    }

    /** The roots for output written to {@code out} inside {@code moduleDir}. */
    private static Roots roots(Path moduleDir, @Nullable Path out, ImportReport.Builder report) {
        if (out == null) return Roots.NONE;
        String rel = rel(moduleDir, out);
        for (String webapp : List.of("src/main/webapp")) {
            if (rel.equals(webapp)) return new Roots(null, "");
            if (rel.startsWith(webapp + "/")) return new Roots(null, rel.substring(webapp.length() + 1));
        }
        for (String classes : List.of("src/main/resources", "target/classes")) {
            if (rel.equals(classes)) return new Roots("", null);
            if (rel.startsWith(classes + "/")) return new Roots(rel.substring(classes.length() + 1), null);
        }
        report.warning("`" + FrontendPlugin.ARTIFACT + "` writes into `" + rel + "`, neither the war's webapp nor"
                + " the classpath; jk packages the output under `static/`");
        return Roots.NONE;
    }

    private static NodeTable placed(NodeTable t, @Nullable String dir, Roots roots) {
        return placed(t, dir, roots, OUT, t.inputs());
    }

    private static NodeTable placed(
            NodeTable t, @Nullable String dir, Roots roots, @Nullable String out, List<String> inputs) {
        return new NodeTable(
                t.packageManager(),
                t.framework(),
                t.install(),
                t.build(),
                t.test(),
                t.dev(),
                t.start(),
                out,
                roots.classpath(),
                roots.webapp(),
                t.envPrefixes(),
                t.devPort(),
                dir,
                t.skip(),
                t.steps(),
                t.exports(),
                inputs);
    }

    private static JkBuild withFrontend(JkBuild build, Project project, FrontendPlugin.Frontend f, NodeTable table) {
        ToolchainSpec spec = f.version() == null ? ToolchainSpec.NONE : ToolchainSpec.parse("node", f.version());
        BuildBlock block = build.build().withNode(table);
        if (f.failuresReport()) block = block.withTestFailures(TestFailureMode.REPORT);
        if (!f.env().isEmpty()) {
            List<EnvDecl> vars = new ArrayList<>(block.env().vars());
            vars.addAll(f.env());
            block = block.withEnv(new EnvConfig(block.env().inherit(), vars));
        }
        return build.withProject(project.withNodeSpec(spec)).withBuild(block);
    }

    private static JkBuild dependOn(@Nullable JkBuild consumer, String name) {
        if (consumer == null) throw new IllegalStateException("no consumer module");
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(consumer.dependencies().byScope());
        List<Dependency> main = new ArrayList<>(byScope.getOrDefault(Scope.MAIN, List.of()));
        main.add(Dependency.workspace(name));
        byScope.put(Scope.MAIN, main);
        return consumer.withDependencies(new JkBuild.Dependencies(byScope));
    }

    /** The member whose directory holds {@code target}, the deepest one when members nest. */
    private static @Nullable String consumer(Path rootDir, Iterable<String> members, Path target) {
        String best = null;
        for (String m : members) {
            if (target.startsWith(rootDir.resolve(m).normalize()) && (best == null || m.length() > best.length())) {
                best = m;
            }
        }
        return best;
    }

    /**
     * What moves from {@code from} into the generated module: the package manager's files, the known
     * tool configs, and every top-level directory the bundler config names, never the output or a
     * member module.
     */
    private static List<FrontendFiles.Move> moves(
            Path from,
            Path webDir,
            BundlerOutput.@Nullable Found output,
            @Nullable Path target,
            Path rootDir,
            Iterable<String> members) {
        List<Path> picked = new ArrayList<>();
        try {
            PathUtil.forEachChild(from, (child, attrs) -> {
                String name = String.valueOf(child.getFileName());
                if (FRONTEND_FILES.contains(name) || FRONTEND_PREFIXES.stream().anyMatch(name::startsWith)) {
                    picked.add(child);
                }
                return true;
            });
        } catch (IOException e) {
            return List.of();
        }
        picked.sort(null);
        List<String> literals = new ArrayList<>(output == null ? List.of() : output.paths());
        for (Path config : List.copyOf(picked)) {
            if (!Files.isRegularFile(config)) continue;
            try {
                literals.addAll(BundlerOutput.paths(Files.readString(config)));
            } catch (IOException e) {
                // a config jk cannot read names no directory to move
            }
        }
        for (String literal : literals) {
            Path dir = sourceRoot(from, literal);
            if (dir == null || picked.contains(dir) || (target != null && target.startsWith(dir))) continue;
            boolean member = false;
            for (String m : members) member |= rootDir.resolve(m).normalize().startsWith(dir);
            if (!member && !dir.equals(from) && !NEVER_MOVED.contains(String.valueOf(dir.getFileName()))) {
                picked.add(dir);
            }
        }
        List<FrontendFiles.Move> moves = new ArrayList<>();
        for (Path p : picked) moves.add(new FrontendFiles.Move(p, webDir.resolve(from.relativize(p))));
        return moves;
    }

    /**
     * The directory a relative path in the bundler config sits in, at the depth a front end keeps
     * its sources: {@code src/main/js} for {@code src/main/js/app.js}, else its first segment.
     */
    private static @Nullable Path sourceRoot(Path from, String literal) {
        String p = literal.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.isEmpty() || p.startsWith("/") || p.startsWith("..")) return null;
        String[] seg = p.split("/");
        int depth = seg[0].equals("src") && seg.length > 2 ? 3 : 1;
        if (seg.length <= depth && seg[seg.length - 1].contains(".")) depth = seg.length - 1;
        if (depth == 0) return null;
        Path dir = from.resolve(String.join("/", Arrays.copyOf(seg, depth))).normalize();
        return Files.isDirectory(dir) ? dir : null;
    }

    private static FrontendFiles rewrite(BundlerOutput.Found output, Path configAt, ImportReport.Builder report) {
        String rewritten = output.rewritten(OUT);
        report.warning("`" + output.file().getFileName() + "` now writes to `" + OUT + "/` instead of `"
                + output.literal() + "`; jk places the output");
        return new FrontendFiles(List.of(), Map.of(configAt, rewritten));
    }

    private static void reportUnreadOutput(FrontendPlugin.Frontend f, ImportReport.Builder report) {
        report.warning("the bundler's output directory in `" + f.workingDirectory() + "` could not be read (no"
                + " webpack or vite config with a literal output path): set `[node] out` to it by hand");
    }

    private static Path freeDir(Path wanted, Iterable<String> members, Path rootDir) {
        Path dir = wanted;
        for (int n = 2; Files.exists(dir) || contains(members, rel(rootDir, dir)); n++) {
            dir = wanted.resolveSibling(wanted.getFileName() + "-" + n);
        }
        return dir;
    }

    private static String freeName(JkBuild root, Map<String, JkBuild> members) {
        List<String> names = new ArrayList<>();
        for (JkBuild m : members.values()) names.add(m.project().name());
        String name = "web";
        if (names.contains(name)) name = root.project().name() + "-web";
        for (int n = 2; names.contains(name); n++) name = root.project().name() + "-web-" + n;
        return name;
    }

    private static boolean contains(Iterable<String> values, String value) {
        for (String v : values) if (v.equals(value)) return true;
        return false;
    }

    private static @Nullable Path moduleDir(Model model) {
        return model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath().toAbsolutePath().normalize();
    }

    static String rel(Path base, Path p) {
        return base.toAbsolutePath()
                .normalize()
                .relativize(p.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
