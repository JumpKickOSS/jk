// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NativeVerb implements HostedVerb {

    private final VerbHost host;

    public NativeVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.NATIVE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("native");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-native-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
                Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
                String mainClass = Jsonl.str(requestLine, "mainClass");
                boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                List<String> extraArgs = Jsonl.strArray(requestLine, "extraArgs");
                Map<Path, Path> graalByDir = new HashMap<>();
                Jsonl.strMap(requestLine, "graalHomes").forEach((d, h) -> graalByDir.put(Path.of(d), Path.of(h)));
                List<Path> selectedDirs = new ArrayList<>();
                for (String d : Jsonl.strArray(requestLine, "moduleDirs")) {
                    if (d != null && !d.isBlank())
                        selectedDirs.add(Path.of(d).toAbsolutePath().normalize());
                }
                Session session =
                        host.resolveSession(requestLine, cancelToken, false).withJdksDir(jdksDir);
                SessionContext.where(session, () -> {
                    nativeCascade(
                            session.workingDir(),
                            session.cacheDir(),
                            jdksDir,
                            mainClass,
                            extraArgs,
                            graalByDir,
                            selectedDirs,
                            skipTests,
                            verbose,
                            writer);
                    return null;
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }

    private void nativeCascade(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            List<String> extraArgs,
            Map<Path, Path> graalByDir,
            List<Path> selectedDirs,
            boolean skipTests,
            boolean verbose,
            BufferedWriter writer) {
        JkBuild root;
        try {
            root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
        } catch (RuntimeException | IOException e) {
            host.sendQuiet(
                    writer,
                    ProtoEvents.workspaceFinish(
                            false, cc.jumpkick.model.command.Exit.CONFIG, List.of(String.valueOf(e.getMessage()))));
            return;
        }

        var scopes = new LinkedHashMap<Path, JkBuild>();
        // Canonical (real-path) identities of the modules the CLIENT selected — engine-added
        // prereqs are absent and build jar-only; null = no selection, all native-compile.
        Set<Path> selectedCanonical = null;
        if (root.isWorkspaceRoot()) {
            Map<Path, JkBuild> modulesByDir;
            try {
                modulesByDir = cc.jumpkick.config.WorkspaceLoader.loadModules(entryDir, root);
            } catch (RuntimeException | IOException e) {
                host.sendQuiet(
                        writer,
                        ProtoEvents.workspaceFinish(
                                false, cc.jumpkick.model.command.Exit.CONFIG, List.of(String.valueOf(e.getMessage()))));
                return;
            }
            // -m / --modules: keep selected modules + transitive build prereqs. Identities are the
            // graph's canonical (real) paths so symlinked checkouts do not silently drop prereqs,
            // and an unresolvable graph fails the request instead of degrading.
            if (selectedDirs != null && !selectedDirs.isEmpty()) {
                Set<Path> want = new LinkedHashSet<>();
                for (Path p : selectedDirs) want.add(cc.jumpkick.runtime.BuildGraph.canonicalPath(p));
                selectedCanonical = Set.copyOf(want);
                try {
                    var graph = cc.jumpkick.runtime.BuildGraph.resolve(entryDir, root);
                    if (graph.hasErrors()) {
                        host.sendQuiet(
                                writer,
                                ProtoEvents.workspaceFinish(
                                        false, cc.jumpkick.model.command.Exit.CONFIG, List.copyOf(graph.errors())));
                        return;
                    }
                    Map<Path, Set<Path>> edges = graph.edges();
                    ArrayDeque<Path> q = new ArrayDeque<>(want);
                    while (!q.isEmpty()) {
                        Path d = q.poll();
                        for (Path pre : edges.getOrDefault(d, Set.of())) {
                            Path n = cc.jumpkick.runtime.BuildGraph.canonicalPath(pre);
                            if (want.add(n)) q.add(n);
                        }
                    }
                } catch (IOException e) {
                    host.sendQuiet(
                            writer,
                            ProtoEvents.workspaceFinish(
                                    false,
                                    cc.jumpkick.model.command.Exit.CONFIG,
                                    List.of("module selection: cannot resolve the build graph — " + e.getMessage())));
                    return;
                }
                Map<Path, JkBuild> filtered = new LinkedHashMap<>();
                for (var e : modulesByDir.entrySet()) {
                    Path d = cc.jumpkick.runtime.BuildGraph.canonicalPath(e.getKey());
                    if (want.contains(d)) filtered.put(e.getKey(), e.getValue());
                }
                modulesByDir = filtered;
            }
            for (Path dir : cc.jumpkick.runtime.BuildGraph.orderModules(modulesByDir)) {
                scopes.put(dir, modulesByDir.get(dir));
            }
        } else {
            scopes.put(entryDir, root);
        }

        // Assemble every module's plan up front and send the whole plan burst first, so the
        // client's aggregate bar calibrates to the workspace total before any module runs.
        var plans = new LinkedHashMap<Path, cc.jumpkick.run.BuildPlan>();
        var coords = new LinkedHashMap<Path, String>();
        for (var scope : scopes.entrySet()) {
            Path dir = scope.getKey();
            boolean allowNative = selectedCanonical == null
                    || selectedCanonical.contains(cc.jumpkick.runtime.BuildGraph.canonicalPath(dir));
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.NativePlans.moduleBuildPlan(
                    dir,
                    scope.getValue(),
                    cache,
                    jdksDir,
                    graalByDir.get(dir),
                    mainClass,
                    extraArgs,
                    skipTests,
                    verbose,
                    allowNative);
            plans.put(dir, plan);
            coords.put(dir, cc.jumpkick.runtime.LockPlans.coordLabel(scope.getValue(), dir));
        }
        for (var entry : plans.entrySet()) {
            String dirTag = entry.getKey().toString();
            cc.jumpkick.run.BuildPlan plan = entry.getValue();
            host.sendQuiet(
                    writer,
                    ProtoEvents.planModule(
                            dirTag,
                            coords.get(entry.getKey()),
                            plan.name(),
                            (int) Math.min(Integer.MAX_VALUE, plan.estimatedTotalWeight()),
                            false));
            for (Task p : plan.steps()) {
                host.sendQuiet(
                        writer,
                        ProtoEvents.planStep(
                                dirTag,
                                p.name(),
                                p.label(),
                                cc.jumpkick.engine.listen.BridgingPlanListener.phaseWire(
                                        p.group().orElse(null))));
            }
        }
        host.sendQuiet(writer, ProtoEvents.planDone(plans.size()));

        for (var entry : plans.entrySet()) {
            Path dir = entry.getKey();
            String dirTag = dir.toString();
            cc.jumpkick.run.BuildPlan plan = entry.getValue();
            host.sendQuiet(writer, ProtoEvents.moduleStart(dirTag));
            plan.addListener(host.planListener(dirTag, writer, plan));
            long startNanos = System.nanoTime();
            BuildPlanResult result = plan.run();
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            int exitCode = result.success() ? 0 : cc.jumpkick.runtime.NativePlans.failureExitCode(plan, result);
            boolean didWork = !result.success() || cc.jumpkick.runtime.BuildService.moduleDidWork(result);
            host.sendQuiet(
                    writer,
                    ProtoEvents.moduleFinish(dirTag, coords.get(dir), result.success(), exitCode, millis, didWork));
            if (!result.success()) {
                host.sendQuiet(writer, ProtoEvents.workspaceFinish(false, exitCode, List.of()));
                return;
            }
        }
        host.sendQuiet(writer, ProtoEvents.workspaceFinish(true, 0, List.of()));
    }
}
