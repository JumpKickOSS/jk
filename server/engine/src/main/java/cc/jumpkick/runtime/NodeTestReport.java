// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A node test script's JUnit XML: which runner the script runs, the arguments or environment that
 * make it write the report beside its own output, and the report read back into a {@link
 * TestSummary}. A runner jk does not recognise writes none, and the script's exit code alone
 * decides.
 */
final class NodeTestReport {

    /** The test runners jk adds a JUnit reporter to. */
    enum Runner {
        VITEST,
        JEST,
        NODE_TEST,
        OTHER
    }

    private static final Pattern NODE_TEST = Pattern.compile("(^|[\\s;&|])node\\s+(?:[^;&|]*\\s)?--test(\\s|$)");

    /** The arguments after the script's own and the environment that write {@code xml}. */
    record Wiring(List<String> extraArgs, Map<String, String> env) {}

    private NodeTestReport() {}

    /** The runner {@code script} (a {@code package.json} script's text) runs; {@code jestJunit}: the reporter is installed. */
    static Runner runner(String script) {
        if (script.matches("(?s).*(^|[\\s;&|/])vitest(\\s|$).*")) return Runner.VITEST;
        if (script.matches("(?s).*(^|[\\s;&|/])jest(\\s|$).*")) return Runner.JEST;
        if (NODE_TEST.matcher(script).find()) return Runner.NODE_TEST;
        return Runner.OTHER;
    }

    /**
     * How {@code runner} writes {@code xml}: vitest takes reporter arguments, jest the
     * {@code jest-junit} reporter when the project has it, {@code node --test} its reporters through
     * {@code NODE_OPTIONS} (added to {@code nodeOptions}, the step's own).
     */
    static Wiring wiring(Runner runner, Path xml, boolean jestJunit, String nodeOptions) {
        String path = xml.toAbsolutePath().toString();
        return switch (runner) {
            case VITEST ->
                new Wiring(List.of("--reporter=default", "--reporter=junit", "--outputFile.junit=" + path), Map.of());
            case JEST ->
                jestJunit
                        ? new Wiring(
                                List.of("--reporters=default", "--reporters=jest-junit"),
                                Map.of("JEST_JUNIT_OUTPUT_FILE", path))
                        : new Wiring(List.of(), Map.of());
            case NODE_TEST -> {
                String reporters = "--test-reporter=spec --test-reporter-destination=stdout"
                        + " --test-reporter=junit --test-reporter-destination=" + path;
                String options = nodeOptions.isBlank() ? reporters : nodeOptions + " " + reporters;
                yield new Wiring(List.of(), Map.of("NODE_OPTIONS", options));
            }
            case OTHER -> new Wiring(List.of(), Map.of());
        };
    }

    /**
     * {@code xml} as a summary for {@code module}, every {@code <testcase>} at any depth counted
     * ({@code node --test} nests suites); {@code null} when the runner wrote no readable report.
     */
    static @Nullable TestSummary read(Path xml, String module) {
        if (!Files.isRegularFile(xml)) return null;
        NodeList cases;
        try {
            cases = DomXml.parse(xml).getElementsByTagName("testcase");
        } catch (IOException | RuntimeException e) {
            return null;
        }
        long total = 0;
        long skipped = 0;
        List<TestFailureInfo> failures = new ArrayList<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element tc = (Element) cases.item(i);
            total++;
            String className = tc.getAttribute("classname");
            String name = tc.getAttribute("name");
            Element failure = DomXml.childElement(tc, "failure");
            if (failure == null) failure = DomXml.childElement(tc, "error");
            if (failure != null) {
                String message = failure.getAttribute("message");
                if (message.isBlank()) message = failure.getAttribute("type");
                failures.add(new TestFailureInfo(
                        module,
                        "node",
                        className,
                        name,
                        failure.getAttribute("type"),
                        message,
                        failure.getTextContent().strip()));
            } else if (DomXml.childElement(tc, "skipped") != null) {
                skipped++;
            }
        }
        long failed = failures.size();
        return new TestSummary(total, total - failed - skipped, failed, skipped, failures);
    }
}
