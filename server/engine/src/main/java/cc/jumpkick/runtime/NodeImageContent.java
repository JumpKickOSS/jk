// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a node module's image holds. A server image ships the module's production dependencies and
 * its server output under {@code /app} and starts {@code node} there; a static image ships the
 * build output into nginx's web root with a single-page-app configuration. The image builder
 * receives the layers as directories at image paths; nothing here knows about Jib.
 */
public final class NodeImageContent {

    /** The base of a static image. */
    public static final String STATIC_BASE = "nginx:stable-alpine";

    /** The user a distroless image runs as. */
    public static final String NONROOT = "65532";

    private static final String APP = "/app";
    private static final String DISTROLESS = "gcr.io/distroless/nodejs";
    private static final Set<String> SELF_CONTAINED = Set.of(".output");
    private static final Set<String> NOT_SHIPPED =
            Set.of("node_modules", BuildLayout.TARGET, ".git", ".jk", ".next", ".svelte-kit", ".angular", ".turbo");

    private NodeImageContent() {}

    /** One layer: {@code src} (a directory or file) at the image path {@code dest}. */
    public record Layer(String name, Path src, String dest) {}

    /**
     * The image's content. {@code token} fingerprints every layer and the entrypoint, for the
     * packaging cache key.
     */
    public record Content(
            List<Layer> layers,
            List<String> entrypoint,
            @Nullable String workingDir,
            String token) {}

    /** {@code gcr.io/distroless/nodejs<major>-debian13} for the locked Node.js. */
    public static String defaultBase(NodePin pin) {
        String version = pin.version();
        int dot = version.indexOf('.');
        return DISTROLESS + (dot < 0 ? version : version.substring(0, dot)) + "-debian13";
    }

    /** Whether {@code base} is a distroless Node.js image, whose binary is not on its PATH. */
    static boolean distroless(String base) {
        return base.startsWith(DISTROLESS);
    }

    /** The module's start command, or {@code null} when its output is static files. */
    public static @Nullable String start(JkBuild project, Path moduleDir) {
        Path nodeDir = NodeShape.nodeDir(project, moduleDir);
        return nodeDir == null
                ? null
                : NodeProject.infer(nodeDir, project.node()).start();
    }

    /**
     * A server image of the node module in {@code moduleDir}: the output its start command runs,
     * and, unless that output carries its own, the production dependencies installed with the
     * locked package manager.
     */
    public static Content server(
            TaskContext ctx, BuildPlanner.Inputs in, JkBuild project, Path moduleDir, NodeHome home, String base)
            throws IOException, InterruptedException {
        return server(
                project,
                moduleDir,
                base,
                (node, nodeDir, scratch) ->
                        productionDependencies(ctx, in, project, node, moduleDir, nodeDir, home, scratch));
    }

    /** Installs a node build's production dependencies and answers their {@code node_modules}. */
    interface Installer {
        Path install(NodeProject node, Path nodeDir, Path scratch) throws IOException, InterruptedException;
    }

    /** As above, with {@code installer} doing the production install. */
    static Content server(JkBuild project, Path moduleDir, String base, Installer installer)
            throws IOException, InterruptedException {
        Path nodeDir = requireNodeDir(project, moduleDir);
        NodeProject node = NodeProject.infer(nodeDir, project.node());
        if (node.start() == null) throw new IOException(project.project().name() + " has nothing to start");
        List<String> argv = new ArrayList<>(NodeCommands.split(node.start()));
        Path scratch = BuildLayout.moduleTargetDir(moduleDir).resolve("image-node");
        Path app = scratch.resolve("app");
        PathUtil.deleteRecursivelyOrThrow(app);
        Files.createDirectories(app);
        boolean selfContained;
        if (argv.size() >= 2 && argv.get(1).equals(".next/standalone/server.js")) {
            PathUtil.copyTree(nodeDir.resolve(".next/standalone"), app);
            PathUtil.copyTree(nodeDir.resolve(".next/static"), app.resolve(".next/static"));
            PathUtil.copyTree(nodeDir.resolve("public"), app.resolve("public"));
            argv.set(1, "server.js");
            selfContained = true;
        } else if (SELF_CONTAINED.contains(node.out())) {
            PathUtil.copyTree(nodeDir.resolve(node.out()), app.resolve(node.out()));
            selfContained = true;
        } else if (node.framework().equals("plain")) {
            PathUtil.copyTree(nodeDir, app, dir -> skipped(nodeDir, dir));
            selfContained = false;
        } else {
            PathUtil.copyTree(nodeDir.resolve(node.out()), app.resolve(node.out()));
            PathUtil.copyTree(nodeDir.resolve("public"), app.resolve("public"));
            Files.copy(nodeDir.resolve("package.json"), app.resolve("package.json"));
            selfContained = false;
        }
        List<Layer> layers = new ArrayList<>();
        Path deps = null;
        if (!selfContained && hasDependencies(nodeDir)) {
            deps = installer.install(node, nodeDir, scratch);
            layers.add(new Layer("dependencies", deps, APP + "/node_modules"));
        }
        layers.add(new Layer("app", app, APP));
        List<String> entrypoint = entrypoint(argv, base, deps != null ? deps : nodeDir.resolve("node_modules"));
        String token = "app:" + ClasspathFingerprint.entry(app) + "|deps:"
                + (deps == null ? "" : ClasspathFingerprint.entry(deps)) + "|entry:" + entrypoint;
        return new Content(layers, entrypoint, APP, token);
    }

    /**
     * A static image: the build output at nginx's web root, served as a single-page app — unknown
     * paths fall back to {@code index.html}, and the hashed {@code assets/} are cached for a year.
     */
    public static Content staticSite(JkBuild project, Path moduleDir) throws IOException {
        Path nodeDir = requireNodeDir(project, moduleDir);
        NodeProject node = NodeProject.infer(nodeDir, project.node());
        Path out = nodeDir.resolve(node.out());
        if (!Files.isDirectory(out)) {
            throw new IOException(
                    project.project().name() + ": no " + node.out() + "/ to serve — build the module first");
        }
        Path conf = BuildLayout.moduleTargetDir(moduleDir).resolve("image-node/default.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, NGINX_CONF, StandardCharsets.UTF_8);
        List<Layer> layers = List.of(
                new Layer("site", out, "/usr/share/nginx/html"),
                new Layer("nginx", conf, "/etc/nginx/conf.d/default.conf"));
        return new Content(
                layers,
                List.of(),
                null,
                "site:" + ClasspathFingerprint.entry(out) + "|conf:" + Hashing.sha256Hex(NGINX_CONF));
    }

    static final String NGINX_CONF = """
            server {
                listen 80;
                root /usr/share/nginx/html;
                location /assets/ {
                    add_header Cache-Control "public, max-age=31536000, immutable";
                    try_files $uri =404;
                }
                location / {
                    try_files $uri $uri/ /index.html;
                }
            }
            """;

    /**
     * {@code argv} as the image's entrypoint: {@code node} is the base's binary ({@code
     * /nodejs/bin/node} on distroless, which has no {@code PATH} entry for it), and any other
     * command is the bin script its package declares, run by that binary — an image has no
     * {@code .bin} links.
     */
    static List<String> entrypoint(List<String> argv, String base, Path nodeModules) throws IOException {
        String node = distroless(base) ? "/nodejs/bin/node" : "node";
        List<String> out = new ArrayList<>();
        out.add(node);
        if (argv.get(0).equals("node")) {
            out.addAll(argv.subList(1, argv.size()));
            return out;
        }
        String script = binScript(nodeModules, argv.get(0));
        if (script == null) {
            throw new IOException("the start command `" + String.join(" ", argv) + "` is not a node program jk can"
                    + " run in an image — set [node] start = \"node <file>\"");
        }
        out.add(script);
        out.addAll(argv.subList(1, argv.size()));
        return out;
    }

    /** {@code node_modules/<package>/<file>} declaring {@code command} as a bin, or {@code null}. */
    static @Nullable String binScript(Path nodeModules, String command) throws IOException {
        List<Path> packages = new ArrayList<>();
        PathUtil.forEachChild(nodeModules, (child, attrs) -> {
            String name = String.valueOf(child.getFileName());
            if (name.startsWith("@")) {
                PathUtil.forEachChild(child, (scoped, a) -> {
                    packages.add(scoped);
                    return true;
                });
            } else if (!name.startsWith(".")) {
                packages.add(child);
            }
            return true;
        });
        packages.sort(null);
        for (Path pkg : packages) {
            Path manifest = pkg.resolve("package.json");
            if (!Files.isRegularFile(manifest)) continue;
            Object json = MiniJson.parseRelaxed(Files.readString(manifest, StandardCharsets.UTF_8));
            Object bin = MiniJson.get(json, "bin");
            String file = null;
            if (bin instanceof String s && command.equals(lastSegment(MiniJson.str(json, "name")))) file = s;
            if (bin instanceof Map<?, ?> m && m.get(command) instanceof String s) file = s;
            if (file != null) {
                String rel = nodeModules.relativize(pkg).toString().replace('\\', '/');
                return "node_modules/" + rel + "/" + (file.startsWith("./") ? file.substring(2) : file);
            }
        }
        return null;
    }

    private static String lastSegment(@Nullable String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    /**
     * The production dependencies of {@code nodeDir}, installed into a scratch copy of the module
     * so a local {@code file:} dependency resolves, with links copied rather than symlinked. The
     * install is skipped while its manifest, lockfile and Node.js are what the last one used.
     */
    private static Path productionDependencies(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JkBuild project,
            NodeProject node,
            Path moduleDir,
            Path nodeDir,
            NodeHome home,
            Path scratch)
            throws IOException, InterruptedException {
        Path prod = scratch.resolve("prod");
        Path stamp = scratch.resolve("prod.stamp");
        String key = installKey(nodeDir, home);
        Path modules = prod.resolve("node_modules");
        if (Files.isDirectory(modules)
                && Files.isRegularFile(stamp)
                && Files.readString(stamp, StandardCharsets.UTF_8).equals(key)) {
            return modules;
        }
        PathUtil.deleteRecursivelyOrThrow(prod);
        PathUtil.copyTree(nodeDir, prod, dir -> skipped(nodeDir, dir));
        Map<String, String> env = NodeEnv.of(in, project, node, home, moduleDir, true);
        List<String> argv = new ArrayList<>(home.managerCommand());
        argv.addAll(productionInstall(home.packageManager()));
        if (home.packageManager() == PackageManager.YARN) env.put("YARN_NODE_LINKER", "node-modules");
        ctx.label("install production dependencies");
        NodeProcess.Result r = NodeProcess.run(ctx, argv, prod, env);
        if (!r.ok()) {
            throw new IOException(
                    "the production install failed (exit " + r.exit() + "): " + String.join("\n", r.tail()));
        }
        Files.writeString(stamp, key, StandardCharsets.UTF_8);
        return modules;
    }

    /** The manager's install of production dependencies alone, links copied in. */
    static List<String> productionInstall(PackageManager manager) {
        return switch (manager) {
            case NPM -> List.of("ci", "--omit=dev", "--install-links", "--no-audit", "--no-fund", "--prefer-offline");
            case PNPM -> List.of("install", "--frozen-lockfile", "--prod", "--config.node-linker=hoisted");
            case YARN -> List.of("workspaces", "focus", "--all", "--production");
            case BUN -> List.of("install", "--frozen-lockfile", "--production");
        };
    }

    private static String installKey(Path nodeDir, NodeHome home) throws IOException {
        StringBuilder sb = new StringBuilder(home.version()).append('|').append(home.packageManager());
        for (String f : List.of("package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock")) {
            Path p = nodeDir.resolve(f);
            if (Files.isRegularFile(p))
                sb.append('|').append(f).append('=').append(Hashing.sha256Hex(Files.readAllBytes(p)));
        }
        return sb.toString();
    }

    private static boolean hasDependencies(Path nodeDir) throws IOException {
        Path pkg = nodeDir.resolve("package.json");
        if (!Files.isRegularFile(pkg)) return false;
        return MiniJson.get(MiniJson.parseRelaxed(Files.readString(pkg, StandardCharsets.UTF_8)), "dependencies")
                        instanceof Map<?, ?> m
                && !m.isEmpty();
    }

    private static boolean skipped(Path nodeDir, Path dir) {
        return nodeDir.equals(dir.getParent()) && NOT_SHIPPED.contains(String.valueOf(dir.getFileName()));
    }

    private static Path requireNodeDir(JkBuild project, Path moduleDir) throws IOException {
        Path nodeDir = NodeShape.nodeDir(project, moduleDir);
        if (nodeDir == null) throw new IOException("no node build in " + moduleDir);
        return nodeDir;
    }
}
