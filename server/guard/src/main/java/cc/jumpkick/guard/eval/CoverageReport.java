// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.host.DomXml;
import java.io.IOException;
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
        Element root = DomXml.parse(report).getDocumentElement();
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
