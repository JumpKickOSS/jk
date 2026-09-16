// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.test.MarkdownTestReport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * Surefire and failsafe XML ({@code target/surefire-reports/TEST-*.xml}, {@code
 * target/failsafe-reports/TEST-*.xml}) read into the entries the journal's Tests section renders.
 */
public final class SurefireReports {

    static final List<String> REPORT_DIRS = List.of("surefire-reports", "failsafe-reports");

    private SurefireReports() {}

    /** Every test case under the module's report directories; a module without reports is empty. */
    public static List<MarkdownTestReport.Entry> read(Path moduleDir) throws IOException {
        List<MarkdownTestReport.Entry> out = new ArrayList<>();
        Path target = moduleDir.resolve(BuildLayout.TARGET);
        for (String reports : REPORT_DIRS) {
            List<Path> files = new ArrayList<>();
            PathUtil.forEachRegularFile(target.resolve(reports), (file, attrs) -> {
                String name = file.getFileName().toString();
                if (name.startsWith("TEST-") && name.endsWith(".xml")) files.add(file);
            });
            files.sort(null);
            for (Path file : files) out.addAll(parse(file));
        }
        return out;
    }

    /** The test cases of one suite file; an unparseable file contributes nothing. */
    static List<MarkdownTestReport.Entry> parse(Path file) {
        List<MarkdownTestReport.Entry> out = new ArrayList<>();
        Element suite;
        try {
            suite = DomXml.parse(file).getDocumentElement();
        } catch (IOException | RuntimeException e) {
            return out;
        }
        for (Element tc : DomXml.childElements(suite, "testcase")) {
            String className = attr(tc, "classname");
            String name = attr(tc, "name");
            long millis = Math.round(seconds(attr(tc, "time")) * 1000);
            Element failure = DomXml.childElement(tc, "failure");
            if (failure == null) failure = DomXml.childElement(tc, "error");
            Element skipped = DomXml.childElement(tc, "skipped");
            String message = null;
            String stack = null;
            String skipReason = null;
            if (failure != null) {
                message = attr(failure, "message");
                if (message.isEmpty()) message = attr(failure, "type");
                stack = failure.getTextContent().strip();
            } else if (skipped != null) {
                skipReason = attr(skipped, "message");
            }
            out.add(new MarkdownTestReport.Entry(className, name, millis, message, stack, skipReason));
        }
        return out;
    }

    private static String attr(@Nullable Element e, String name) {
        return e == null ? "" : e.getAttribute(name);
    }

    private static double seconds(String time) {
        try {
            return Double.parseDouble(time.replace(',', '.').toLowerCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
