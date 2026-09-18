// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.host.DomXml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * A lint tool's XML report as one finding count: Checkstyle's (and detekt's) {@code <file><error>}
 * rows other than severity {@code ignore}, PMD's {@code <file><violation>} rows and its
 * {@code <error>}s, SpotBugs's {@code <BugInstance>}s. An empty report — a step with no sources or
 * no configuration — counts zero; a root element none of the tools writes is null.
 */
final class LintReport {

    private LintReport() {}

    static @Nullable Integer findings(Path report) throws IOException {
        if (Files.size(report) == 0) return 0;
        String xml = Files.readString(report).replaceFirst("<!DOCTYPE[^>]*>", "");
        Element root = DomXml.parse(xml).getDocumentElement();
        return switch (root.getLocalName() == null ? root.getTagName() : root.getLocalName()) {
            case "checkstyle" -> {
                int count = 0;
                for (Element file : DomXml.childElements(root, "file")) {
                    for (Element error : DomXml.childElements(file, "error")) {
                        if (!error.getAttribute("severity")
                                .toLowerCase(Locale.ROOT)
                                .equals("ignore")) count++;
                    }
                }
                yield count;
            }
            case "pmd" -> {
                int count = DomXml.childElements(root, "error").size();
                for (Element file : DomXml.childElements(root, "file")) {
                    count += DomXml.childElements(file, "violation").size();
                }
                yield count;
            }
            case "BugCollection" -> DomXml.childElements(root, "BugInstance").size();
            default -> null;
        };
    }
}
