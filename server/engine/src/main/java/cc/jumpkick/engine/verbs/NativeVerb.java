// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSelect;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.NativePreflight;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import cc.jumpkick.runtime.WorkspaceSpec;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin adapter: Graal homes + native-eligible module dirs become a {@link WorkspaceRequest}
 * ({@code target=NATIVE}) on the shared {@link BuildService#buildWorkspace} path.
 */
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
        return JobKind.workspace("native");
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
    public List<String> jobKinds() {
        return List.of("native");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        Path entryDir = Path.of(spec.dir());
        JkBuild entry;
        try {
            entry = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
        }
        Path graal = graalHome();
        if (graal == null) {
            // Without a Graal home a package build once reported as native success — fail the
            // submission loudly instead.
            throw new IllegalArgumentException(
                    "native job: no GraalVM home available (install one with `jk jdk graal`)");
        }
        Map<Path, JkBuild> allModules;
        try {
            allModules = nativeScopes(entryDir, entry);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot load workspace modules: " + e.getMessage());
        }
        Set<Path> selected = JobSelect.selected(entryDir, entry, spec.modules());
        Set<Path> targets = nativeEligibleTargets(allModules, selected);
        if (targets.isEmpty()) {
            // Never "image everything" as a fallback — an unrequested multi-minute native-image
            // of unrelated modules is worse than a clear refusal.
            throw new IllegalArgumentException("native job: no native-eligible module in the selection "
                    + "(needs a [native] table or a unique main class)");
        }
        Map<String, String> graalHomes = new LinkedHashMap<>();
        for (Path d : targets) graalHomes.put(d.toString(), graal.toString());
        // The binary is the job's deliverable; the dashboard/agent surface has no test toggle.
        return ProtoSession.withTrigger(
                ProtoJobs.nativeRequest(
                        spec.dir(),
                        JkDirs.cache().toString(),
                        JkDirs.jdks().toString(),
                        null,
                        true,
                        false,
                        false,
                        false,
                        List.of(),
                        graalHomes,
                        List.of()),
                "web");
    }

    private static Map<Path, JkBuild> nativeScopes(Path entryDir, JkBuild entry) throws IOException {
        if (!entry.isWorkspaceRoot()) {
            return Map.of(entryDir, entry);
        }
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(entryDir, entry);
        Map<Path, JkBuild> ordered = new LinkedHashMap<>();
        for (Path dir : BuildGraph.orderModules(modules)) ordered.put(dir, modules.get(dir));
        return ordered;
    }

    /**
     * Selection ∩ native eligibility, mirroring {@code NativeCommand.graalHomesForModules}:
     * modules with a {@code [native]} table are preferred; when NO module declares one, modules
     * with a unique main are eligible. Never falls back to "every module" — an empty result is
     * the caller's cue to refuse the job.
     */
    static Set<Path> nativeEligibleTargets(Map<Path, JkBuild> allModules, Set<Path> selected) {
        boolean anyNativeTable = false;
        for (JkBuild b : allModules.values()) {
            if (b.nativeImage()) {
                anyNativeTable = true;
                break;
            }
        }
        Set<Path> targets = new LinkedHashSet<>();
        for (var e : allModules.entrySet()) {
            Path dir = e.getKey();
            boolean inSelection = selected == null || selected.contains(BuildGraph.canonicalPath(dir));
            if (!inSelection) continue;
            // enabled = false is an explicit opt-out — never re-enters via the fallback.
            if (e.getValue().nativeExplicitlyDisabled()) continue;
            boolean eligible = e.getValue().nativeImage();
            if (!eligible && !anyNativeTable) {
                eligible = NativePreflight.resolveMain(dir, null) instanceof NativePreflight.Main.Unique;
            }
            if (eligible) targets.add(dir);
        }
        return targets;
    }

    private static @org.jspecify.annotations.Nullable Path graalHome() {
        String g = System.getenv("GRAALVM_HOME");
        if (g == null || g.isBlank()) return null;
        Path p = Path.of(g);
        return Files.isDirectory(p) ? p : null;
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            String mainClass = Jsonl.str(requestLine, "mainClass");
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            List<String> extraArgs = Jsonl.strArray(requestLine, "extraArgs");
            Map<Path, Path> graalByDir = new HashMap<>();
            Jsonl.strMap(requestLine, "graalHomes").forEach((d, h) -> graalByDir.put(Path.of(d), Path.of(h)));
            List<String> moduleTokens = Jsonl.strArray(requestLine, "moduleDirs");
            Set<Path> selected = new LinkedHashSet<>();
            if (!moduleTokens.isEmpty()) {
                JkBuild entry = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                var hit = JobSelect.resolveTokens(entryDir, entry, moduleTokens);
                if (hit != null && !hit.ok()) {
                    host.sendQuiet(
                            writer, host.requestFailedLine(null, new IllegalArgumentException(hit.errorMessage())));
                    return JobOutcome.failed(2);
                }
                if (hit != null) {
                    for (Path p : hit.moduleDirs()) {
                        selected.add(BuildGraph.canonicalPath(p));
                    }
                } else {
                    for (String d : moduleTokens) {
                        if (d != null && !d.isBlank()) {
                            selected.add(Path.of(d).toAbsolutePath().normalize());
                        }
                    }
                }
            }
            if (selected.isEmpty()) selected.addAll(graalByDir.keySet());

            Session session =
                    host.resolveSession(requestLine, cancelToken, false).withJdksDir(jdksDir);
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir, cache, jdksDir, 0, null, skipTests, verbose, 0, null, true, true)
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                    .withSpec(WorkspaceSpec.nativeImage(selected, graalByDir, mainClass, extraArgs));

            long rid = host.eventRequestId();
            if (rid > 0) host.putProgressRoot(rid, entryDir.toString());
            WorkspaceResult result = SessionContext.where(
                    session,
                    () -> BuildService.buildWorkspace(req, host.workspaceListener(writer, entryDir.toString())));
            return WorkspaceTerminal.finish(host, writer, entryDir.toString(), result, cancelToken.cancelled());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(Jsonl.str(requestLine, "dir"), e));
            return null;
        }
    }
}
