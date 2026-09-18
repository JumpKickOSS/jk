// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.DomXml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * The tools' XML reports as {@link Finding}s. Checkstyle and detekt write the Checkstyle format
 * ({@code <file name><error line column severity message source/>}), PMD its own
 * ({@code <file name><violation beginline begincolumn rule priority>text</violation>}), SpotBugs a
 * {@code BugCollection} whose {@code BugInstance}s locate themselves through a primary
 * {@code SourceLine} relative to the source roots.
 */
final class Reports {

    private Reports() {}

    static List<Finding> parse(LintTool tool, Path report, List<Path> sourceRoots) throws IOException {
        if (Files.size(report) == 0) return List.of();
        Element root = DomXml.parse(report).getDocumentElement();
        return switch (tool) {
            case CHECKSTYLE, DETEKT -> checkstyle(root);
            case PMD -> pmd(root);
            case SPOTBUGS -> spotbugs(root, sourceRoots);
        };
    }

    /** Checkstyle's own format, which detekt's XML report shares. */
    static List<Finding> checkstyle(Element root) {
        List<Finding> findings = new ArrayList<>();
        for (Element file : DomXml.childElements(root, "file")) {
            String name = file.getAttribute("name");
            for (Element error : DomXml.childElements(file, "error")) {
                String severity = error.getAttribute("severity").toLowerCase(Locale.ROOT);
                if (severity.equals("ignore")) continue;
                findings.add(new Finding(
                        severity.equals("error") ? Finding.ERROR : Finding.WARNING,
                        name,
                        intAttr(error, "line"),
                        intAttr(error, "column"),
                        ruleOf(error.getAttribute("source")),
                        error.getAttribute("message")));
            }
        }
        return findings;
    }

    /** PMD's XML: priorities 1 and 2 are errors, 3 to 5 warnings; a processing error is an error at the file. */
    static List<Finding> pmd(Element root) {
        List<Finding> findings = new ArrayList<>();
        for (Element file : DomXml.childElements(root, "file")) {
            String name = file.getAttribute("name");
            for (Element violation : DomXml.childElements(file, "violation")) {
                int priority = intAttr(violation, "priority");
                findings.add(new Finding(
                        priority > 0 && priority <= 2 ? Finding.ERROR : Finding.WARNING,
                        name,
                        intAttr(violation, "beginline"),
                        intAttr(violation, "begincolumn"),
                        violation.getAttribute("rule"),
                        violation.getTextContent().strip()));
            }
        }
        for (Element error : DomXml.childElements(root, "error")) {
            findings.add(
                    new Finding(Finding.ERROR, error.getAttribute("filename"), 0, 0, "", error.getAttribute("msg")));
        }
        return findings;
    }

    /** SpotBugs's BugCollection: priority 1 (high) is an error, the rest warnings; the primary source line locates it. */
    static List<Finding> spotbugs(Element root, List<Path> sourceRoots) {
        List<Finding> findings = new ArrayList<>();
        for (Element bug : DomXml.childElements(root, "BugInstance")) {
            Element line = primarySourceLine(bug);
            @Nullable String file = null;
            int at = 0;
            if (line != null) {
                file = locate(line.getAttribute("sourcepath"), sourceRoots);
                at = intAttr(line, "start");
            }
            Element message = DomXml.childElement(bug, "LongMessage");
            if (message == null) message = DomXml.childElement(bug, "ShortMessage");
            findings.add(new Finding(
                    intAttr(bug, "priority") == 1 ? Finding.ERROR : Finding.WARNING,
                    file,
                    at,
                    0,
                    bug.getAttribute("type"),
                    message == null
                            ? bug.getAttribute("type")
                            : message.getTextContent().strip()));
        }
        return findings;
    }

    /** The bug's own {@code SourceLine}: the one marked primary, else the last direct child. */
    private static @Nullable Element primarySourceLine(Element bug) {
        Element last = null;
        for (Element line : DomXml.childElements(bug, "SourceLine")) {
            if (EnvValues.parseBool(line.getAttribute("primary")).orElse(false)) return line;
            last = line;
        }
        return last;
    }

    /** The root-relative {@code sourcepath} under the first source root that holds it, else as written. */
    private static String locate(String sourcePath, List<Path> sourceRoots) {
        for (Path root : sourceRoots) {
            Path candidate = root.resolve(sourcePath);
            if (Files.isRegularFile(candidate)) return candidate.toString();
        }
        return sourcePath;
    }

    /** {@code com.puppycrawl.tools.checkstyle.checks.coding.MagicNumberCheck} → {@code MagicNumber}; {@code detekt.MagicNumber} → {@code MagicNumber}. */
    static String ruleOf(String source) {
        String rule = source.substring(source.lastIndexOf('.') + 1);
        return rule.endsWith("Check") && rule.length() > "Check".length()
                ? rule.substring(0, rule.length() - "Check".length())
                : rule;
    }

    private static int intAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
