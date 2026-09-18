// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a two-module reactor ({@code lib} ok, {@code app} with one failing test) leaves behind for
 * the engine to read: the spy's event file and app's surefire XML.
 */
final class MavenRunFixture {

    static final String FAILING_TEST = "com.example.AppTest";
    static final String FAILING_METHOD = "adds";
    static final String FAILURE_MESSAGE = "expected: <4> but was: <5>";
    static final String STACK_FRAME = "at com.example.AppTest.adds(AppTest.java:12)";

    private MavenRunFixture() {}

    /** Write the artefacts under {@code project}; returns the events file. */
    static Path write(Path project) throws IOException {
        Path lib = Files.createDirectories(project.resolve("lib"));
        Path app = Files.createDirectories(project.resolve("app"));
        Files.writeString(
                project.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>reactor</artifactId><version>1.0</version></project>\n");
        Path reports = Files.createDirectories(app.resolve("target").resolve("surefire-reports"));
        Files.writeString(reports.resolve("TEST-" + FAILING_TEST + ".xml"), surefireXml());
        Path events = project.resolve("events.tsv");
        Files.writeString(events, events(lib, app));
        return events;
    }

    static String events(Path lib, Path app) {
        String l = lib.toString();
        String a = app.toString();
        return String.join(
                "\n",
                line("SessionStarted", 0, "", "", "", "", "", ""),
                line("ProjectStarted", 0, "com.example:lib", l, "", "", "", ""),
                line(
                        "MojoStarted",
                        0,
                        "com.example:lib",
                        l,
                        "maven-compiler-plugin:compile",
                        "default-compile",
                        "",
                        ""),
                line(
                        "MojoSucceeded",
                        0,
                        "com.example:lib",
                        l,
                        "maven-compiler-plugin:compile",
                        "default-compile",
                        "",
                        ""),
                line("ProjectSucceeded", 400, "com.example:lib", l, "", "", "", ""),
                line("ProjectStarted", 0, "com.example:app", a, "", "", "", ""),
                line("MojoStarted", 0, "com.example:app", a, "maven-surefire-plugin:test", "default-test", "", ""),
                line(
                        "MojoFailed",
                        0,
                        "com.example:app",
                        a,
                        "maven-surefire-plugin:test",
                        "default-test",
                        "org.apache.maven.plugin.MojoFailureException",
                        "There are test failures.\n\nPlease refer to target/surefire-reports"),
                line(
                        "ProjectFailed",
                        1200,
                        "com.example:app",
                        a,
                        "",
                        "",
                        "org.apache.maven.lifecycle.LifecycleExecutionException",
                        "Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test"),
                line("SessionEnded", 0, "", "", "", "", "", ""),
                "");
    }

    /** One event line the way the spy writes it: eight percent-encoded fields joined by tabs. */
    static String line(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            if (sb.length() > 0) sb.append('\t');
            sb.append(URLEncoder.encode(f, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String line(
            String type,
            long millis,
            String project,
            String dir,
            String goal,
            String execution,
            String exception,
            String message) {
        return line(type, Long.toString(millis), project, dir, goal, execution, exception, message);
    }

    static String surefireXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"" + FAILING_TEST
                + "\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"0.05\">\n"
                + "  <testcase name=\"greets\" classname=\"" + FAILING_TEST + "\" time=\"0.01\"/>\n"
                + "  <testcase name=\"" + FAILING_METHOD + "\" classname=\"" + FAILING_TEST + "\" time=\"0.04\">\n"
                + "    <failure message=\""
                + FAILURE_MESSAGE.replace("<", "&lt;").replace(">", "&gt;")
                + "\" type=\"org.opentest4j.AssertionFailedError\">"
                + "org.opentest4j.AssertionFailedError: "
                + FAILURE_MESSAGE.replace("<", "&lt;").replace(">", "&gt;") + "\n"
                + "\t" + STACK_FRAME + "\n"
                + "\tat java.base/java.lang.reflect.Method.invoke(Method.java:565)\n"
                + "</failure>\n"
                + "  </testcase>\n"
                + "</testsuite>\n";
    }
}
