// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ReleaseDeliverables;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.publish.CiIdentity;
import cc.jumpkick.release.ReleaseIndex;
import cc.jumpkick.runtime.ModuleJar;
import cc.jumpkick.runtime.base.PublishPlans;
import cc.jumpkick.runtime.base.ReleasePlans;
import cc.jumpkick.runtime.base.ReleaseRun;
import cc.jumpkick.runtime.base.ReleaseStore;
import cc.jumpkick.runtime.base.ReleaseStores;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The release lane's workspace half. Before anything builds, a run refuses a release that is
 * already published without a file it would write, and a final run one that lacks a listed
 * platform's files, so nothing irreversible happens first. After every module uploaded its files,
 * the final run writes {@code SHA256SUMS}, its signatures and {@code index.json}, publishes the
 * release ({@code latest.json} in a bucket, the draft on GitHub or Forgejo, a new release linking
 * the generic package on GitLab), then publishes the Central
 * deployments the library lane held at {@code VALIDATED}. A stage run only reports what it
 * uploaded.
 */
final class ReleaseLane {

    private ReleaseLane() {}

    /** The publish arguments of a run that ships to the release lane, else {@code null}. */
    static TailArgs.@Nullable Publish args(WorkspaceRequest request) {
        if (request.target() != WorkspaceTarget.PUBLISH || request.spec() == null) return null;
        return request.spec().tail() instanceof TailArgs.Publish p && p.runs(PublishLane.RELEASE) ? p : null;
    }

    /**
     * Why the run's release cannot go ahead, one line each, before anything builds: no selected
     * module builds a release file; a stage run on a host whose platform is not listed or with
     * nothing platform-specific to stage; a release already published without a file the run would
     * write; a final run whose listed platforms are not all staged.
     */
    static List<String> refusals(WorkspaceRequest request, Map<Path, JkBuild> units, Predicate<Path> selected) {
        TailArgs.Publish args = args(request);
        if (args == null) return List.of();
        ReleaseRun run;
        try {
            run = ReleaseRun.of(request.entryDir(), args);
        } catch (IOException | RuntimeException e) {
            return List.of(Errors.text(e));
        }
        Map<Path, JkBuild> shipping = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> unit : units.entrySet()) {
            if (selected.test(unit.getKey())
                    && !ModuleJar.skipsRelease(unit.getValue())
                    && !ModuleJar.releaseFiles(unit.getValue(), unit.getKey()).isEmpty()) {
                shipping.put(unit.getKey(), unit.getValue());
            }
        }
        if (shipping.isEmpty()) {
            return List.of("[publish] release: no module builds a fat jar, archive, war or native executable");
        }
        if (run.stage()) {
            if (!run.hostListed()) {
                return List.of(
                        run.host() + " is not in [release] platforms; stage on " + String.join(", ", run.platforms()));
            }
            boolean specific = shipping.values().stream().anyMatch(ReleaseDeliverables::platformSpecific);
            if (!specific) return List.of("nothing to stage: no module builds a runtime image or native executable");
        }
        if (run.dryRun()) return List.of();
        try {
            ReleaseStore store = ReleaseStores.open(run, args);
            List<String> refused = publishedWithout(run, store, shipping);
            if (refused.isEmpty() && !run.stage()) refused = missingPlatforms(run, store, shipping);
            if (refused.isEmpty()) store.prepare();
            return refused;
        } catch (IOException | RuntimeException e) {
            // A forge's refusals name the release themselves; a bucket's need the place.
            if (run.forge() != null) return List.of(Errors.text(e));
            return List.of("could not read " + run.destination() + ": " + Errors.text(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("interrupted reading " + run.destination());
        }
    }

    /**
     * The {@link ReleaseRun#publishedWithout} line for the first file the run would write that a
     * release already out does not hold; empty when the release is not out or holds them all. A
     * release that is out takes no new file, so a re-run that finds them all changes nothing.
     */
    static List<String> publishedWithout(ReleaseRun run, ReleaseStore store, Map<Path, JkBuild> shipping)
            throws IOException, InterruptedException {
        if (!store.published()) return List.of();
        List<String> names = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> unit : shipping.entrySet()) {
            BuildLayout layout = BuildLayout.of(unit.getKey(), unit.getValue());
            for (ReleaseDeliverables.Deliverable d : ModuleJar.releaseFiles(unit.getValue(), unit.getKey())) {
                if (d.neutral() ? !run.stage() : run.hostListed()) names.add(d.name());
            }
            if (run.stage()) continue;
            for (String platform : run.others()) {
                names.addAll(ReleaseDeliverables.platformNames(layout, unit.getValue(), platform));
            }
            if (unit.getValue().pomMetadata().sbom()) names.add(layout.archiveRoot() + ".cdx.json");
        }
        if (!run.stage()) {
            names.add(ReleaseIndex.SUMS);
            names.add(ReleaseIndex.INDEX);
        }
        for (String name : names) {
            if (!store.present(name)) return List.of(run.publishedWithout(name));
        }
        return List.of();
    }

    /** One {@link ReleaseRun#lacks} line per listed platform some shipping module has no staged file for. */
    static List<String> missingPlatforms(ReleaseRun run, ReleaseStore store, Map<Path, JkBuild> shipping)
            throws IOException, InterruptedException {
        List<String> refused = new ArrayList<>();
        for (String platform : run.others()) {
            search:
            for (Map.Entry<Path, JkBuild> unit : shipping.entrySet()) {
                BuildLayout layout = BuildLayout.of(unit.getKey(), unit.getValue());
                for (String name : ReleaseDeliverables.platformNames(layout, unit.getValue(), platform)) {
                    if (!store.present(name)) {
                        refused.add(run.lacks(platform));
                        break search;
                    }
                }
            }
        }
        return refused;
    }

    /** {@code result} with the release lane's outcome, or failed with why the lane stopped. */
    static WorkspaceResult complete(WorkspaceRunPhase.Run run, WorkspaceResult result) {
        WorkspaceRequest request = run.prepared().resources().request();
        TailArgs.Publish args = args(request);
        if (args == null || !result.success() || result.cancelled()) return result;
        List<ReleaseOutcome.File> files = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        List<String> held = new ArrayList<>();
        for (ModulePlan module : run.prepared().plans().values()) {
            files.addAll(module.plan().get(ReleasePlans.FILES).orElse(List.of()));
            expected.addAll(module.plan().get(ReleasePlans.EXPECTED).orElse(List.of()));
            module.plan()
                    .get(PublishPlans.DEPLOYMENT)
                    .filter(d -> "VALIDATED".equals(d.state()))
                    .ifPresent(d -> held.add(d.id()));
        }
        ReleaseRun release;
        try {
            release = ReleaseRun.of(request.entryDir(), args);
        } catch (IOException | RuntimeException e) {
            return result.failed(Exit.FAILURE, Errors.text(e), null);
        }
        Final lane = new Final(release, args, files, held);
        try {
            return result.withRelease(lane.run(expected, request.entryDir()));
        } catch (IOException | RuntimeException e) {
            return result.failed(Exit.FAILURE, Errors.text(e), lane.outcome(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result.failed(Exit.FAILURE, "interrupted publishing " + release.destination(), lane.outcome(null));
        }
    }

    /** The final run's own steps over the files the modules uploaded. */
    static final class Final {
        private final ReleaseRun release;
        private final TailArgs.Publish args;
        private final List<ReleaseOutcome.File> files;
        private final List<String> held;
        private final List<String> released = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private @Nullable ReleaseStore store;
        private Function<String, @Nullable String> env = BuildEnv::get;

        Final(ReleaseRun release, TailArgs.Publish args, List<ReleaseOutcome.File> files, List<String> held) {
            this.release = release;
            this.args = args;
            this.files = new ArrayList<>(files);
            this.held = held;
        }

        /** Overrides the environment a keyless signature reads its CI identity from. */
        Final env(Function<String, @Nullable String> env) {
            this.env = env;
            return this;
        }

        /** Overrides the store the run opens: a test hands in one over a fake server. */
        Final over(ReleaseStore store) {
            this.store = store;
            return this;
        }

        ReleaseOutcome outcome(@Nullable String finish) {
            return new ReleaseOutcome(
                    release.destination(), release.stage(), release.dryRun(), files, finish, released, notes);
        }

        ReleaseOutcome run(List<String> expected, Path entryDir) throws IOException, InterruptedException {
            if (release.stage()) return outcome(release.dryRun() ? release.plannedFinish() : null);
            JkBuild root = release.rootBuild();
            boolean sign = root.pomMetadata().sign();
            Path keyFile = sign ? args.keyFile() : null;
            boolean keyless = sign && CiIdentity.available(env);
            List<String> signatures = new ArrayList<>();
            if (keyFile != null) signatures.add(ReleaseIndex.SUMS + ".asc");
            if (keyless) signatures.add(ReleaseIndex.SUMS + ".sigstore.json");
            if (sign && signatures.isEmpty() && !release.dryRun()) notes.add(unsigned(release.tag()));
            if (release.dryRun()) {
                for (String e : expected) {
                    String[] parts = e.split("/", 3);
                    files.add(file(parts[2], parts[0], parts[1], -1, "", ReleaseOutcome.PLANNED));
                }
                for (String name : documents(signatures))
                    files.add(file(name, "", null, -1, "", ReleaseOutcome.PLANNED));
                return outcome(release.plannedFinish());
            }
            ReleaseStore store = this.store != null ? this.store : ReleaseStores.open(release, args);
            for (String e : expected) {
                String[] parts = e.split("/", 3);
                Optional<ReleaseStore.Held> staged = store.held(parts[2]);
                if (staged.isEmpty()) throw new IOException(release.lacks(parts[1]));
                files.add(file(
                        parts[2],
                        parts[0],
                        parts[1],
                        staged.get().size(),
                        staged.get().sha256(),
                        ReleaseOutcome.STAGED));
            }
            List<ReleaseIndex.Entry> entries = new ArrayList<>();
            for (ReleaseOutcome.File f : files) {
                entries.add(new ReleaseIndex.Entry(
                        f.name(), f.module(), f.platform(), f.size(), f.sha256(), store.url(f.name())));
            }
            Path dir = Files.createDirectories(
                    release.root().resolve(OutputDirs.TARGET).resolve("release").resolve(release.version()));
            byte[] sums = ReleaseIndex.sha256sums(entries);
            Path sumsFile = Files.write(dir.resolve(ReleaseIndex.SUMS), sums);
            files.add(put(store, ReleaseIndex.SUMS, sums));
            List<String> unsigned = new ArrayList<>();
            for (String name : signatures) {
                if (store.present(name)) {
                    Path local = dir.resolve(name);
                    files.add(file(
                            name, "", null, Files.exists(local) ? Files.size(local) : -1, "", ReleaseOutcome.EXISTING));
                } else {
                    unsigned.add(name);
                }
            }
            if (!unsigned.isEmpty()) {
                boolean asc = unsigned.contains(ReleaseIndex.SUMS + ".asc");
                boolean bundle = unsigned.contains(ReleaseIndex.SUMS + ".sigstore.json");
                for (Path signature : PublishPlans.signRelease(
                        entryDir, sumsFile, asc ? keyFile : null, args.gpgPassphrase(), bundle)) {
                    files.add(put(store, String.valueOf(signature.getFileName()), Files.readAllBytes(signature)));
                }
            }
            byte[] index = ReleaseIndex.index(release.name(), release.version(), release.tag(), entries);
            Files.write(dir.resolve(ReleaseIndex.INDEX), index);
            files.add(put(store, ReleaseIndex.INDEX, index));
            String finish = store.publish();
            if (!held.isEmpty()
                    && PublishTail.holdsForRelease(args)
                    && !root.pomMetadata().hold()) {
                released.addAll(PublishPlans.releaseDeployments(entryDir, PublishTail.request(args), held));
            }
            return outcome(finish);
        }

        /** The line a release that signing is on for, and that nothing could sign, says. */
        static String unsigned(String tag) {
            return "release " + tag + " is unsigned: set [publish] key-file, or sign = false";
        }

        private static List<String> documents(List<String> signatures) {
            List<String> out = new ArrayList<>();
            out.add(ReleaseIndex.SUMS);
            out.addAll(signatures);
            out.add(ReleaseIndex.INDEX);
            return out;
        }

        private static ReleaseOutcome.File put(ReleaseStore store, String name, byte[] body)
                throws IOException, InterruptedException {
            ReleaseStore.Put put = store.put(name, body);
            return file(
                    name,
                    "",
                    null,
                    body.length,
                    Hashing.sha256Hex(body),
                    put == ReleaseStore.Put.UPLOADED ? ReleaseOutcome.UPLOADED : ReleaseOutcome.EXISTING);
        }

        private static ReleaseOutcome.File file(
                String name, String module, @Nullable String platform, long size, String sha256, String status) {
            String role = platform == null ? ReleaseOutcome.FINAL : ReleaseOutcome.STAGING;
            return new ReleaseOutcome.File(name, module, platform, size, sha256, role, status);
        }
    }
}
