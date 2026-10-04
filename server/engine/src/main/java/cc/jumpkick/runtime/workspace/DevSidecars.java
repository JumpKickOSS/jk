// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.DevReady;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Sidecar;
import cc.jumpkick.runtime.NodeRun;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code [dev]} for one module's dev plan. The sidecars: a dev server for every node build the
 * module runs on — each node module it depends on, named after that module, and its own {@code
 * src/main/node} as {@code <module>-node} — each under the locked Node.js; then the workspace
 * root's {@code [dev.sidecars]} entries, then the module's, the module winning a name clash. An
 * entry named like an inferred dev server is laid over it key by key; any other must have a {@code
 * command}. {@code cwd} is made absolute against the manifest that declared it; {@code env} is the
 * {@code .env} values the real environment does not already set, then the entry's own table on top.
 * The app's own probe: the module's alone — the root does not know which of its members' apps is
 * running. Nothing here touches an action key — none of it is a task.
 */
final class DevSidecars {

    private static final Set<Scope> RUNTIME = Set.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);

    private DevSidecars() {}

    /** {@code [dev] ready} / {@code ready-pattern} / {@code ready-timeout} on the wire, or {@link ExecPlan.Probe#NONE}. */
    static ExecPlan.Probe appReady(JkBuild module) {
        return ExecPlan.Probe.of(module.build().devReady());
    }

    static List<ExecPlan.Sidecar> resolve(Path moduleDir, JkBuild module, Map<String, String> clientEnv)
            throws IOException, InterruptedException {
        Map<String, ExecPlan.Sidecar> inferred = new LinkedHashMap<>();
        String path = clientEnv.getOrDefault("PATH", System.getenv("PATH"));
        if (NodeShape.kind(module, moduleDir) == NodeShape.Kind.SIDE_BY_SIDE) {
            put(inferred, module.project().name() + "-node", NodeRun.dev(moduleDir, module, path));
        }
        if (NodeShape.kind(module, moduleDir) != NodeShape.Kind.MODULE) {
            for (var e : WorkspaceClasspath.closureSiblings(moduleDir, module, RUNTIME)
                    .entrySet()) {
                if (NodeShape.kind(e.getValue(), e.getKey()) != NodeShape.Kind.MODULE) continue;
                put(inferred, e.getValue().project().name(), NodeRun.dev(e.getKey(), e.getValue(), path));
            }
        }
        return combine(inferred, written(moduleDir, module), clientEnv, false);
    }

    /**
     * A workspace root's dev stack: a dev server for each node module in {@code nodeMembers}, named
     * after it, with the root's {@code [dev.sidecars]} laid over them and beside them. Each is a front
     * door unless the root names its own.
     */
    static List<ExecPlan.Sidecar> stack(
            Path root, JkBuild rootBuild, Map<Path, JkBuild> nodeMembers, Map<String, String> clientEnv)
            throws IOException, InterruptedException {
        Map<String, ExecPlan.Sidecar> inferred = new LinkedHashMap<>();
        String path = clientEnv.getOrDefault("PATH", System.getenv("PATH"));
        for (var e : nodeMembers.entrySet()) {
            put(inferred, e.getValue().project().name(), NodeRun.dev(e.getKey(), e.getValue(), path));
        }
        List<Written> written = new ArrayList<>();
        for (Sidecar s : rootBuild.build().devSidecars()) written.add(new Written(root, s));
        return combine(inferred, written, clientEnv, true);
    }

    /** A node module's dev server, named {@code name}, when it has a dev script. */
    static ExecPlan.@Nullable Sidecar server(String name, NodeRun.@Nullable Dev dev) {
        if (dev == null) return null;
        ExecPlan.Probe probe = dev.url().isEmpty()
                ? ExecPlan.Probe.NONE
                : new ExecPlan.Probe(dev.url(), "", DevReady.DEFAULT_TIMEOUT_MILLIS);
        return new ExecPlan.Sidecar(
                name, dev.argv(), dev.dir().toString(), dev.env(), probe, false, Sidecar.Restart.NEVER);
    }

    private static void put(Map<String, ExecPlan.Sidecar> inferred, String name, NodeRun.@Nullable Dev dev) {
        ExecPlan.Sidecar server = server(name, dev);
        if (server != null) inferred.put(name, server);
    }

    /** An entry as the manifest wrote it, and the directory of that manifest. */
    private record Written(Path declaredIn, Sidecar sidecar) {}

    /** The root's entries, then the module's; the module's wins a name clash. */
    private static List<Written> written(Path moduleDir, JkBuild module) throws IOException {
        Map<String, Written> byName = new LinkedHashMap<>();
        Path root = WorkspaceLocator.findRoot(moduleDir).orElse(null);
        if (root != null && !root.equals(moduleDir) && Files.isRegularFile(ManifestPaths.manifestIn(root))) {
            JkBuild rootBuild = JkBuildParser.parse(ManifestPaths.manifestIn(root));
            for (Sidecar s : rootBuild.build().devSidecars()) byName.put(s.name(), new Written(root, s));
        }
        for (Sidecar s : module.build().devSidecars()) byName.put(s.name(), new Written(moduleDir, s));
        return List.copyOf(byName.values());
    }

    /**
     * The inferred dev servers with the written entries laid over them by name, then the written
     * entries that name none. When no entry names a front door, the inferred servers are the front
     * doors: every one when {@code everyDoor}, else the only one.
     */
    private static List<ExecPlan.Sidecar> combine(
            Map<String, ExecPlan.Sidecar> inferred,
            List<Written> written,
            Map<String, String> clientEnv,
            boolean everyDoor)
            throws IOException {
        Map<String, ExecPlan.Sidecar> out = new LinkedHashMap<>(inferred);
        boolean doorWritten = false;
        for (Written w : written) {
            Sidecar s = w.sidecar();
            if (s.declared().contains("front-door") && s.frontDoor()) doorWritten = true;
            ExecPlan.Sidecar base = inferred.get(s.name());
            if (base != null) {
                out.put(s.name(), over(base, resolve(w.declaredIn(), s, clientEnv), s.declared()));
            } else if (s.command().isEmpty()) {
                throw new IOException("[dev.sidecars." + s.name() + "] has no command, and no node module named `"
                        + s.name() + "` to take one from");
            } else {
                out.put(s.name(), resolve(w.declaredIn(), s, clientEnv));
            }
        }
        if (!doorWritten && (everyDoor || inferred.size() == 1)) {
            for (String name : inferred.keySet()) {
                ExecPlan.Sidecar s = out.get(name);
                boolean declaredOff = written.stream()
                        .anyMatch(w -> w.sidecar().name().equals(name)
                                && w.sidecar().declared().contains("front-door"));
                if (s != null && !declaredOff && !s.probe().ready().isEmpty()) {
                    out.put(
                            name,
                            new ExecPlan.Sidecar(
                                    s.name(), s.command(), s.cwd(), s.env(), s.probe(), true, s.restart()));
                }
            }
        }
        return List.copyOf(out.values());
    }

    /** {@code written} laid over {@code base}: each key the manifest wrote replaces the inferred one. */
    private static ExecPlan.Sidecar over(ExecPlan.Sidecar base, ExecPlan.Sidecar written, Set<String> keys) {
        Map<String, String> env = new LinkedHashMap<>(base.env());
        env.putAll(written.env());
        boolean probe = keys.contains("ready") || keys.contains("ready-pattern");
        ExecPlan.Probe p = new ExecPlan.Probe(
                probe ? written.probe().ready() : base.probe().ready(),
                probe ? written.probe().readyPattern() : base.probe().readyPattern(),
                keys.contains("ready-timeout")
                        ? written.probe().readyTimeoutMillis()
                        : base.probe().readyTimeoutMillis());
        return new ExecPlan.Sidecar(
                base.name(),
                keys.contains("command") ? written.command() : base.command(),
                keys.contains("cwd") ? written.cwd() : base.cwd(),
                env,
                p,
                keys.contains("front-door") ? written.frontDoor() : base.frontDoor(),
                keys.contains("restart") ? written.restart() : base.restart());
    }

    private static ExecPlan.Sidecar resolve(Path declaredIn, Sidecar s, Map<String, String> clientEnv) {
        EnvLookup lookup = EnvLookup.forModule(declaredIn, clientEnv::get);
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : lookup.fileNames()) {
            if (!clientEnv.containsKey(name)) {
                String value = lookup.get(name);
                if (value != null) env.put(name, value);
            }
        }
        env.putAll(s.env());
        return new ExecPlan.Sidecar(
                s.name(),
                s.command(),
                declaredIn.resolve(s.cwd()).toAbsolutePath().normalize().toString(),
                env,
                ExecPlan.Probe.of(s.ready()),
                s.frontDoor(),
                s.restart());
    }
}
