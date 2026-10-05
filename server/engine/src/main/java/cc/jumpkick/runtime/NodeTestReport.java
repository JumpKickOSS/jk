// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.jsonl.MiniJson;
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
 * A node test script's report: which runner the script runs, the arguments or environment that
 * make it write a report beside its own output, and the report read back into a {@link
 * TestSummary}. vitest and {@code node --test} write JUnit XML; jest writes its own JSON
 * ({@code --json}), which needs no reporter package in the project. A runner jk does not recognise
 * writes none, and the script's exit code alone decides.
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

    /** The runner {@code script} (a {@code package.json} script's text) runs. */
    static Runner runner(String script) {
        if (script.matches("(?s).*(^|[\\s;&|/])vitest(\\s|$).*")) return Runner.VITEST;
        if (script.matches("(?s).*(^|[\\s;&|/])jest(\\s|$).*")) return Runner.JEST;
        if (NODE_TEST.matcher(script).find()) return Runner.NODE_TEST;
        return Runner.OTHER;
    }

    /**
     * How {@code runner} writes its report for {@code xml}: vitest takes reporter arguments, jest
     * writes its JSON to {@link #jestJson}, {@code node --test} takes its reporters through
     * {@code NODE_OPTIONS} (added to {@code nodeOptions}, the step's own).
     */
    static Wiring wiring(Runner runner, Path xml, String nodeOptions) {
        String path = xml.toAbsolutePath().toString();
        return switch (runner) {
            case VITEST ->
                new Wiring(List.of("--reporter=default", "--reporter=junit", "--outputFile.junit=" + path), Map.of());
            case JEST ->
                new Wiring(List.of("--json", "--outputFile=" + jestJson(xml).toAbsolutePath()), Map.of());
            case NODE_TEST -> {
                String reporters = "--test-reporter=spec --test-reporter-destination=stdout"
                        + " --test-reporter=junit --test-reporter-destination=" + path;
                String options = nodeOptions.isBlank() ? reporters : nodeOptions + " " + reporters;
                yield new Wiring(List.of(), Map.of("NODE_OPTIONS", options));
            }
            case OTHER -> new Wiring(List.of(), Map.of());
        };
    }

    /** Where jest writes its JSON report for the run whose JUnit report would be {@code xml}. */
    static Path jestJson(Path xml) {
        String name = String.valueOf(xml.getFileName());
        String stem = name.endsWith(".xml") ? name.substring(0, name.length() - 4) : name;
        return xml.resolveSibling(stem + ".jest.json");
    }

    /**
     * The run's report as a summary for {@code module}: jest's JSON when it wrote one, else
     * {@code xml}, every {@code <testcase>} at any depth counted ({@code node --test} nests suites);
     * {@code null} when the runner wrote no readable report.
     */
    static @Nullable TestSummary read(Path xml, String module) {
        Path json = jestJson(xml);
        if (Files.isRegularFile(json)) return readJest(json, module);
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

    /**
     * jest's {@code --json} report: per file, each assertion's status ({@code passed},
     * {@code failed}, {@code pending}/{@code skipped}/{@code todo}) and failure messages.
     */
    static @Nullable TestSummary readJest(Path json, String module) {
        Object root;
        try {
            root = MiniJson.parse(Files.readString(json));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (root == null) return null;
        long total = 0;
        long skipped = 0;
        List<TestFailureInfo> failures = new ArrayList<>();
        for (Object file : MiniJson.list(root, "testResults")) {
            String fileName = String.valueOf(MiniJson.str(file, "name"));
            for (Object a : MiniJson.list(file, "assertionResults")) {
                total++;
                String status = String.valueOf(MiniJson.str(a, "status"));
                if (status.equals("failed")) {
                    StringBuilder text = new StringBuilder();
                    for (Object m : MiniJson.list(a, "failureMessages"))
                        text.append(m).append('\n');
                    String detail = text.toString().strip();
                    String first = detail.lines().findFirst().orElse("failed");
                    failures.add(new TestFailureInfo(
                            module,
                            "node",
                            relativeFile(fileName),
                            String.valueOf(MiniJson.str(a, "fullName")),
                            "jest",
                            first,
                            detail));
                } else if (!status.equals("passed")) {
                    skipped++;
                }
            }
        }
        long failed = failures.size();
        return new TestSummary(total, total - failed - skipped, failed, skipped, failures);
    }

    /** A test file's name as jest reports it (absolute), shortened to its last two segments. */
    private static String relativeFile(String name) {
        Path p = Path.of(name);
        int n = p.getNameCount();
        return n >= 2 ? p.subpath(n - 2, n).toString().replace('\\', '/') : name;
    }
}
