// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.maven.spy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class EventLineTest {

    @Test
    @SuppressWarnings("deprecation") // the only MavenSession constructor a test can reach without a container
    void project_event_names_the_module_its_dir_and_mavens_build_time() {
        MavenProject app = project("com.example", "app", "/ws/app");
        DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
        result.addBuildSummary(new BuildSuccess(app, 400));
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(), result);

        List<String> f = fields(EventLine.of(event(ExecutionEvent.Type.ProjectSucceeded, session, app, null, null)));
        assertThat(f).hasSize(EventLine.FIELDS);
        assertThat(f.get(0)).isEqualTo("ProjectSucceeded");
        assertThat(f.get(1)).isEqualTo("400");
        assertThat(f.get(2)).isEqualTo("com.example:app");
        assertThat(f.get(3)).isEqualTo(new File("/ws/app").getAbsolutePath());
        assertThat(f.subList(4, 8)).containsOnly("");
    }

    @Test
    void mojo_failure_carries_goal_exception_and_long_message() {
        MavenProject app = project("com.example", "app", "/ws/app");
        Plugin compiler = new Plugin();
        compiler.setGroupId("org.apache.maven.plugins");
        compiler.setArtifactId("maven-compiler-plugin");
        MojoExecution mojo = new MojoExecution(compiler, "compile", "default-compile");
        MojoFailureException boom = new MojoFailureException(
                this, "Compilation failure", "Compilation failure\n/ws/app/src/A.java:[3,5] cannot find \"symbol\"\t!");
        String line = EventLine.of(event(ExecutionEvent.Type.MojoFailed, null, app, mojo, boom));
        assertThat(line).doesNotContain("\n");
        List<String> f = fields(line);
        assertThat(f.get(0)).isEqualTo("MojoFailed");
        assertThat(f.get(1)).isEqualTo("0");
        assertThat(f.get(4)).isEqualTo("maven-compiler-plugin:compile");
        assertThat(f.get(5)).isEqualTo("default-compile");
        assertThat(f.get(6)).isEqualTo("org.apache.maven.plugin.MojoFailureException");
        assertThat(f.get(7)).isEqualTo("Compilation failure\n/ws/app/src/A.java:[3,5] cannot find \"symbol\"\t!");
    }

    @Test
    void session_event_has_empty_project_fields_and_causes_join_the_message() {
        RuntimeException cause = new RuntimeException("root cause");
        List<String> f = fields(EventLine.of(
                event(ExecutionEvent.Type.SessionEnded, null, null, null, new IllegalStateException("outer", cause))));
        assertThat(f.get(0)).isEqualTo("SessionEnded");
        assertThat(f.subList(2, 6)).containsOnly("");
        assertThat(f.get(7)).isEqualTo("outer\nroot cause");
    }

    /** The engine's read of a line: split on tabs, decode each field. */
    private static List<String> fields(String line) {
        return Arrays.stream(line.split("\t", -1))
                .map(s -> URLDecoder.decode(s, StandardCharsets.UTF_8))
                .toList();
    }

    private static MavenProject project(String group, String artifact, String dir) {
        MavenProject p = new MavenProject();
        p.setGroupId(group);
        p.setArtifactId(artifact);
        p.setFile(new File(dir, "pom.xml"));
        return p;
    }

    private static ExecutionEvent event(
            ExecutionEvent.Type type,
            @Nullable MavenSession session,
            @Nullable MavenProject project,
            @Nullable MojoExecution mojo,
            @Nullable Exception exception) {
        return new ExecutionEvent() {
            @Override
            public Type getType() {
                return type;
            }

            @Override
            public @Nullable MavenSession getSession() {
                return session;
            }

            @Override
            public @Nullable MavenProject getProject() {
                return project;
            }

            @Override
            public @Nullable MojoExecution getMojoExecution() {
                return mojo;
            }

            @Override
            public @Nullable Exception getException() {
                return exception;
            }
        };
    }
}
