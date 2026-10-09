// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.Assume;

/** Links and resolves a jk project through the real external-system path, as a project open does. */
final class JkImport {

    /** The resolved graph, or the failure the resolve reported; exactly one is non-null. */
    record Result(
            @Nullable DataNode<ProjectData> graph, @Nullable String failure) {}

    private JkImport() {}

    static Result importProject(Project project, File base) {
        JkSync.link(project, base);
        if (!JkSync.isLinked(project, base)) throw new AssertionError("not linked: " + base);
        List<DataNode<ProjectData>> graph = new ArrayList<>();
        List<String> failure = new ArrayList<>();
        ImportSpecBuilder spec = new ImportSpecBuilder(project, JkSystem.ID)
                .use(ProgressExecutionMode.MODAL_SYNC)
                .dontReportRefreshErrors()
                .callback(new ExternalProjectRefreshCallback() {
                    @Override
                    public void onSuccess(@Nullable DataNode<ProjectData> node) {
                        if (node == null) return;
                        graph.add(node);
                        ProjectDataManager.getInstance().importData(node, project);
                    }

                    @Override
                    public void onFailure(String message, @Nullable String details) {
                        failure.add(message + (details == null ? "" : "\n" + details));
                    }
                });
        ExternalSystemUtil.refreshProject(JkSync.projectPath(base), spec);
        return new Result(graph.isEmpty() ? null : graph.get(0), failure.isEmpty() ? null : failure.get(0));
    }

    /** Skips the calling test, saying why on stderr, when {@code jk --version} does not answer. */
    static void assumeJkOnPath(Class<?> test) {
        boolean present;
        try {
            Process p = new ProcessBuilder(JkBin.path(), "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            present = p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            present = false;
        }
        if (!present) System.err.println("SKIPPED " + test.getSimpleName() + ": no jk on PATH (JK_BIN / -Djk.bin)");
        Assume.assumeTrue("jk on PATH", present);
    }
}
