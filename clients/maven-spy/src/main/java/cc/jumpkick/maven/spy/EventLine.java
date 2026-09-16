// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.maven.spy;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.maven.execution.BuildSummary;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jspecify.annotations.Nullable;

/**
 * One reactor event as one line of eight tab-separated, percent-encoded fields: the {@link
 * ExecutionEvent.Type} name; the module's wall millis from Maven's own build summary (project
 * events only, else 0); {@code groupId:artifactId}; the module's basedir; {@code
 * plugin-artifactId:goal}; the execution id; the exception's class name; its message, long form
 * and cause chain newline-joined. Absent fields are empty. Encoding is the JDK's, so the line
 * carries no separator or newline of its own.
 */
public final class EventLine {

    /** Field order, shared with the engine's reader. */
    public static final int FIELDS = 8;

    private EventLine() {}

    public static String of(ExecutionEvent e) {
        MavenProject p = e.getProject();
        File basedir = p == null ? null : p.getBasedir();
        MojoExecution mojo = e.getMojoExecution();
        Throwable t = e.getException();
        String[] fields = {
            e.getType().name(),
            Long.toString(millis(e.getSession(), p)),
            p == null ? "" : p.getGroupId() + ":" + p.getArtifactId(),
            basedir == null ? "" : basedir.getAbsolutePath(),
            mojo == null ? "" : mojo.getArtifactId() + ":" + mojo.getGoal(),
            mojo == null ? "" : mojo.getExecutionId(),
            t == null ? "" : t.getClass().getName(),
            t == null ? "" : messageOf(t),
        };
        StringBuilder sb = new StringBuilder(160);
        for (String f : fields) {
            if (sb.length() > 0) sb.append('\t');
            sb.append(URLEncoder.encode(f, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** The module's build time Maven recorded, or 0 when the session has no summary for it yet. */
    static long millis(@Nullable MavenSession session, @Nullable MavenProject project) {
        if (session == null || project == null || session.getResult() == null) return 0;
        BuildSummary summary = session.getResult().getBuildSummary(project);
        return summary == null ? 0 : summary.getTime();
    }

    /** The message, the long message a Mojo exception carries, then each cause's message. */
    static String messageOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        append(sb, t.getMessage());
        if (t instanceof AbstractMojoExecutionException m) append(sb, m.getLongMessage());
        for (Throwable c = t.getCause(); c != null && c != c.getCause(); c = c.getCause()) {
            append(sb, c.getMessage());
        }
        return sb.toString();
    }

    /** Adds {@code part} unless one side already says the other (a long message repeats the short). */
    private static void append(StringBuilder sb, @Nullable String part) {
        if (part == null || part.isEmpty() || sb.indexOf(part) >= 0) return;
        if (sb.length() > 0 && part.contains(sb)) {
            sb.setLength(0);
        } else if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(part);
    }
}
