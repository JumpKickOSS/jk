// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Target basket + optional module cone for {@link WorkspaceRequest}. Empty {@link #selectedModules()}
 * means the whole graph. Native/image extras are ignored for other targets.
 */
public record WorkspaceSpec(
        WorkspaceTarget target,
        Set<Path> selectedModules,
        Map<Path, Path> graalByDir,
        String nativeMain,
        List<String> nativeExtraArgs,
        String imageMain,
        String imageRegistry,
        String imageTag,
        String imageTarball,
        String imageDocker,
        /** Install only: client-resolved m2 repo root ({@code --m2-dir}); null → {@code ~/.m2}. */
        Path m2Dir) {

    public static final WorkspaceSpec DEFAULT = new WorkspaceSpec(
            WorkspaceTarget.PACKAGE, Set.of(), Map.of(), null, List.of(), null, null, null, null, null, null);

    public WorkspaceSpec {
        target = target == null ? WorkspaceTarget.PACKAGE : target;
        selectedModules = selectedModules == null ? Set.of() : Set.copyOf(selectedModules);
        graalByDir = graalByDir == null ? Map.of() : Map.copyOf(graalByDir);
        nativeExtraArgs = nativeExtraArgs == null ? List.of() : List.copyOf(nativeExtraArgs);
        if (nativeMain != null && nativeMain.isBlank()) nativeMain = null;
    }

    public static WorkspaceSpec of(WorkspaceTarget target) {
        return new WorkspaceSpec(target, Set.of(), Map.of(), null, List.of(), null, null, null, null, null, null);
    }

    public static WorkspaceSpec nativeImage(
            Set<Path> selected, Map<Path, Path> graalByDir, String main, List<String> extraArgs) {
        return new WorkspaceSpec(
                WorkspaceTarget.NATIVE, selected, graalByDir, main, extraArgs, null, null, null, null, null, null);
    }

    public static WorkspaceSpec image(
            Set<Path> selected, String main, String registry, String tag, String tarball, String docker) {
        return new WorkspaceSpec(
                WorkspaceTarget.IMAGE, selected, Map.of(), null, List.of(), main, registry, tag, tarball, docker, null);
    }

    /** {@code jk compile}: compile-only terminal on the selection; prereqs package (JK-2103). */
    public static WorkspaceSpec compile(Set<Path> selected) {
        return new WorkspaceSpec(
                WorkspaceTarget.COMPILE, selected, Map.of(), null, List.of(), null, null, null, null, null, null);
    }

    /**
     * {@code jk install}: package + cache-install on the cone; {@code graalByDir} for ALWAYS
     * native, {@code m2Dir} the client-resolved local-repo root so a workspace install honors
     * {@code --m2-dir} the same way a lone module does.
     */
    public static WorkspaceSpec install(Set<Path> selected, Map<Path, Path> graalByDir, Path m2Dir) {
        return new WorkspaceSpec(
                WorkspaceTarget.INSTALL, selected, graalByDir, null, List.of(), null, null, null, null, null, m2Dir);
    }

    public boolean hasSelection() {
        return !selectedModules.isEmpty();
    }

    public WorkspaceTarget effectiveTarget(boolean testOnly) {
        if (target != WorkspaceTarget.PACKAGE) return target;
        return testOnly ? WorkspaceTarget.TEST : WorkspaceTarget.PACKAGE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkspaceSpec s)) return false;
        return target == s.target
                && selectedModules.equals(s.selectedModules)
                && graalByDir.equals(s.graalByDir)
                && Objects.equals(nativeMain, s.nativeMain)
                && nativeExtraArgs.equals(s.nativeExtraArgs)
                && Objects.equals(imageMain, s.imageMain)
                && Objects.equals(imageRegistry, s.imageRegistry)
                && Objects.equals(imageTag, s.imageTag)
                && Objects.equals(imageTarball, s.imageTarball)
                && Objects.equals(imageDocker, s.imageDocker)
                && Objects.equals(m2Dir, s.m2Dir);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                target,
                selectedModules,
                graalByDir,
                nativeMain,
                nativeExtraArgs,
                imageMain,
                imageRegistry,
                imageTag,
                imageTarball,
                imageDocker,
                m2Dir);
    }
}
