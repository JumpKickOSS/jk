// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The house guards read a workflow through one comment view, chosen by the file's kind in
 * {@code HouseRules.owner}. The guard source set is compiled by jk alone, so this reads the suite
 * as text: the constants that name a workflow, and what each is handed to.
 */
class WorkflowViewTest {

    /** The view's owner; the suite is the directory around it. */
    private static final String OWNER = "server/guard/src/guard/java/cc/jumpkick/guard/house/HouseRules.java";

    private static final Pattern WORKFLOW_CONSTANT =
            Pattern.compile("static final String (\\w+) =\\s*\"([^\"]*\\.ya?ml)\"");
    private static final Pattern BLANKED = Pattern.compile("\\.blanked\\(\\s*(\\w+)\\s*,");

    private static Map<String, String> houseSources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path dir = Objects.requireNonNull(
                RepoRoot.file(WorkflowViewTest.class, OWNER).getParent(), "suite dir");
        PathUtil.forEachRegularFile(dir, (f, attrs) -> {
            if (f.getFileName().toString().endsWith(".java"))
                out.put(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
        });
        return out;
    }

    @Test
    void no_house_guard_hands_a_workflow_to_the_c_family_blanker() throws IOException {
        List<String> workflows = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (var e : houseSources().entrySet()) {
            Matcher c = WORKFLOW_CONSTANT.matcher(e.getValue());
            List<String> names = new ArrayList<>();
            while (c.find()) {
                names.add(c.group(1));
                workflows.add(e.getKey() + ":" + c.group(1));
            }
            Matcher b = BLANKED.matcher(e.getValue());
            while (b.find()) if (names.contains(b.group(1))) offenders.add(e.getKey() + ": .blanked(" + b.group(1));
        }
        assertThat(workflows).as("the scan sees the workflow constants").isNotEmpty();
        assertThat(offenders)
                .as("a workflow is read through HouseRules.owner, which picks the YAML view by the file's kind")
                .isEmpty();
    }

    @Test
    void the_yaml_comment_view_has_one_owner() throws IOException {
        Map<String, String> sources = houseSources();
        assertThat(sources.get("HouseRules.java"))
                .contains("static String yamlComments(")
                .contains("isYaml(path) ? yamlComments(");
        for (var e : sources.entrySet())
            if (!e.getKey().equals("HouseRules.java"))
                assertThat(e.getValue())
                        .as(e.getKey() + " reads workflows through HouseRules.owner, not a view of its own")
                        .doesNotContain("yamlComments(")
                        .doesNotContain("indexOf(\" #\")");
    }
}
