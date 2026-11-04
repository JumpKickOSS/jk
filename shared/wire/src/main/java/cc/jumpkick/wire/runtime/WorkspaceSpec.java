// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.model.InstallDestination;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The goal of a {@link WorkspaceRequest} and the modules it selects. Empty {@link
 * #selectedModules()} means the whole graph; {@code graalByDir} names the GraalVM home found on
 * this machine for each module whose build links a native image, and {@code graalInstalls} the
 * GraalVM spec the engine installs, as part of the run, for each module that has none yet;
 * {@link #tail()} carries the goal's own arguments.
 */
public record WorkspaceSpec(
        WorkspaceTarget target,
        Set<Path> selectedModules,
        Map<Path, Path> graalByDir,
        Map<Path, String> graalInstalls,
        @Nullable TailArgs tail) {

    public static final WorkspaceSpec DEFAULT = of(WorkspaceTarget.PACKAGE);

    public WorkspaceSpec {
        target = target == null ? WorkspaceTarget.PACKAGE : target;
        selectedModules = selectedModules == null ? Set.of() : Set.copyOf(selectedModules);
        graalByDir = graalByDir == null ? Map.of() : Map.copyOf(graalByDir);
        graalInstalls = graalInstalls == null ? Map.of() : Map.copyOf(graalInstalls);
    }

    /** A spec with no GraalVM to install: every home is found or none is needed. */
    public WorkspaceSpec(
            WorkspaceTarget target, Set<Path> selectedModules, Map<Path, Path> graalByDir, @Nullable TailArgs tail) {
        this(target, selectedModules, graalByDir, Map.of(), tail);
    }

    public static WorkspaceSpec of(WorkspaceTarget target) {
        return new WorkspaceSpec(target, Set.of(), Map.of(), null);
    }

    public static WorkspaceSpec nativeImage(
            Set<Path> selected, Map<Path, Path> graalByDir, @Nullable String main, List<String> extraArgs) {
        return new WorkspaceSpec(WorkspaceTarget.NATIVE, selected, graalByDir, new TailArgs.Native(main, extraArgs));
    }

    public static WorkspaceSpec image(
            Set<Path> selected,
            @Nullable String main,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String tarball,
            @Nullable String docker) {
        return new WorkspaceSpec(
                WorkspaceTarget.IMAGE, selected, Map.of(), new TailArgs.Image(main, registry, tag, tarball, docker));
    }

    /** {@code jk compile}: a compile-only plan for every module in the cone; nothing packages. */
    public static WorkspaceSpec compile(Set<Path> selected) {
        return new WorkspaceSpec(WorkspaceTarget.COMPILE, selected, Map.of(), null);
    }

    /**
     * {@code jk install}: {@code m2Dir} is the client-resolved local-repo root (null for {@code
     * ~/.m2}); {@code destination} is the {@code --destination} override, null when none was given.
     */
    public static WorkspaceSpec install(
            Set<Path> selected,
            Map<Path, Path> graalByDir,
            @Nullable Path m2Dir,
            @Nullable InstallDestination destination) {
        return new WorkspaceSpec(
                WorkspaceTarget.INSTALL, selected, graalByDir, new TailArgs.Install(m2Dir, destination));
    }

    /** Install's second pass after the engine jar changes: {@code cache-install} against jars on disk. */
    public static WorkspaceSpec reshelve(
            Set<Path> selected, @Nullable Path m2Dir, @Nullable InstallDestination destination) {
        return new WorkspaceSpec(
                WorkspaceTarget.RESHELVE, selected, Map.of(), new TailArgs.Install(m2Dir, destination));
    }

    public static WorkspaceSpec train(
            Set<Path> selected, Map<Path, Path> graalByDir, @Nullable String profile, boolean force) {
        return new WorkspaceSpec(WorkspaceTarget.TRAIN, selected, graalByDir, new TailArgs.Train(profile, force));
    }

    /** {@code jk sync}: the build's lock and sync phase, with sources jars when {@code sources}. */
    public static WorkspaceSpec sync(boolean sources, @Nullable URI repoUrl) {
        return new WorkspaceSpec(WorkspaceTarget.SYNC, Set.of(), Map.of(), new TailArgs.Sync(sources, repoUrl));
    }

    /** The sync tail's arguments: what {@code jk sync} asked for, or none for every other goal. */
    public TailArgs.@Nullable Sync syncArgs() {
        return tail instanceof TailArgs.Sync sync ? sync : null;
    }

    public static WorkspaceSpec publish(Set<Path> selected, TailArgs.Publish args) {
        return new WorkspaceSpec(WorkspaceTarget.PUBLISH, selected, Map.of(), args);
    }

    /** This spec with the client-resolved GraalVM home per module that links a native image. */
    public WorkspaceSpec withGraalByDir(Map<Path, Path> homes) {
        return new WorkspaceSpec(target, selectedModules, homes, graalInstalls, tail);
    }

    /** This spec with the GraalVM the engine installs, as part of the run, per module that has none. */
    public WorkspaceSpec withGraalInstalls(Map<Path, String> installs) {
        return new WorkspaceSpec(target, selectedModules, graalByDir, installs, tail);
    }

    /** This spec narrowed to {@code selected}. */
    public WorkspaceSpec withSelection(Set<Path> selected) {
        return new WorkspaceSpec(target, selected, graalByDir, graalInstalls, tail);
    }

    public boolean hasSelection() {
        return !selectedModules.isEmpty();
    }

    /** Install's local Maven repo root, or null for {@code ~/.m2} and every other goal. */
    public @Nullable Path m2Dir() {
        return tail instanceof TailArgs.Install install ? install.m2Dir() : null;
    }

    /** Install's {@code --destination} override, or null when none was given and for every other goal. */
    public @Nullable InstallDestination installDestination() {
        return tail instanceof TailArgs.Install install ? install.destination() : null;
    }

    public WorkspaceTarget effectiveTarget(boolean testOnly) {
        if (target != WorkspaceTarget.PACKAGE) return target;
        return testOnly ? WorkspaceTarget.TEST : WorkspaceTarget.PACKAGE;
    }
}
