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
 * A JaCoCo XML report's whole-report counters: {@code <counter type="LINE" missed covered/>} as a
 * direct child of {@code <report>}. Percent covered, or {@code null} when the counter is absent.
 */
final class CoverageReport {

    private CoverageReport() {}

    static @Nullable Double percent(Path report, String kind) throws IOException {
        // JaCoCo writes a DOCTYPE naming its report DTD; the hardened parser refuses any DOCTYPE
        // and nothing here needs the DTD, so the declaration is dropped before parsing.
        String xml = Files.readString(report).replaceFirst("<!DOCTYPE[^>]*>", "");
        Element root = DomXml.parse(xml).getDocumentElement();
        String type = kind.toUpperCase(Locale.ROOT);
        for (Element c : DomXml.childElements(root, "counter")) {
            if (!type.equals(c.getAttribute("type"))) continue;
            double missed = Double.parseDouble(c.getAttribute("missed"));
            double covered = Double.parseDouble(c.getAttribute("covered"));
            double total = missed + covered;
            return total == 0 ? 100.0 : covered * 100.0 / total;
        }
        return null;
    }
}
