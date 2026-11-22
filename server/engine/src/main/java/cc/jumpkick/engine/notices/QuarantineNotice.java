// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import cc.jumpkick.builds.ProjectIdCache;
import cc.jumpkick.runtime.TestFlakes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The bell's "JumpKick quarantined flaky test X in Y" when a run puts a test class in quarantine,
 * which every dashboard tab in use shows a moment as a toast. It links to the project's Flaky tests
 * tab for the checkout the run tested.
 */
public final class QuarantineNotice {

    /** The notice kind. */
    public static final String KIND = "quarantine";

    private QuarantineNotice() {}

    /** What adds the notice to {@code notices} for each quarantine, as {@link TestFlakes#listen} takes it. */
    public static Consumer<TestFlakes.Quarantined> on(Notices notices) {
        return q -> notices.add(
                KIND, text(q), href(q.root(), ProjectIdCache.idOf(q.root().toString())), null);
    }

    /** "JumpKick quarantined flaky test FooTest in shop", or how many when several. */
    static String text(TestFlakes.Quarantined q) {
        Path name = q.root().getFileName();
        String where = name == null ? "" : " in " + name;
        if (q.classes().size() == 1)
            return "JumpKick quarantined flaky test " + simple(q.classes().getFirst()) + where;
        return "JumpKick quarantined " + q.classes().size() + " flaky tests" + where;
    }

    /** {@code #project/<id>/flaky?dir=<checkout>}; the projects list when the checkout has no project id. */
    static String href(Path root, @Nullable String projectId) {
        if (projectId == null || projectId.isBlank()) return "#projects";
        return "#project/" + encode(projectId) + "/flaky?dir=" + encode(root.toString());
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
