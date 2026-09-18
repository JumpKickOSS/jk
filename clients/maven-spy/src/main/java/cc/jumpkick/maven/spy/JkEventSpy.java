// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.maven.spy;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.jspecify.annotations.Nullable;

/**
 * Appends every {@link ExecutionEvent} as one {@link EventLine} to the file {@code -Djk.mvn.events}
 * names, each mojo's line carrying the time the {@link MojoTimer} measured for it. Silent when the
 * property is absent, and never fails the build: a spy that cannot write drops the line.
 */
@Named
@Singleton
public final class JkEventSpy extends AbstractEventSpy {

    /** The property {@code jk mvn} sets to the events file; absent means record nothing. */
    public static final String EVENTS_PROPERTY = "jk.mvn.events";

    private final MojoTimer timer = MojoTimer.system();
    private @Nullable Writer out;

    @Override
    public void init(Context context) {
        String file = System.getProperty(EVENTS_PROPERTY);
        if (file == null) file = fromContext(context);
        if (file == null || file.isEmpty()) return;
        try {
            Path path = Path.of(file);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            out = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            out = null;
        }
    }

    /** Maven hands its user properties to a spy through the context when they are not system ones. */
    private static @Nullable String fromContext(Context context) {
        Object props = context.getData().get("userProperties");
        if (props instanceof Map<?, ?> m) {
            Object v = m.get(EVENTS_PROPERTY);
            if (v != null) return v.toString();
        }
        return null;
    }

    @Override
    public void onEvent(Object event) {
        Writer w = out;
        if (w == null || !(event instanceof ExecutionEvent e)) return;
        try {
            w.write(EventLine.of(e, timer.observe(e)));
            w.write('\n');
            w.flush();
        } catch (IOException | RuntimeException ignored) {
            // A line the spy cannot record is a line the report goes without.
        }
    }

    @Override
    public void close() {
        Writer w = out;
        out = null;
        if (w == null) return;
        try {
            w.close();
        } catch (IOException ignored) {
            // Nothing left to flush that a failed close could save.
        }
    }
}
