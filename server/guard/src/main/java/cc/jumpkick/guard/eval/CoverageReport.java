// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.host.DomXml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * A JaCoCo XML report's whole-report counters: {@code <counter type="LINE" missed covered/>} as a
 * direct child of {@code <report>}. Percent covered, or {@code null} when the counter is absent.
 */
public final class CoverageReport {

    private CoverageReport() {}

    static @Nullable Double percent(Path report, String kind) throws IOException {
        String counter = counters(report).get(kind.toUpperCase(Locale.ROOT));
        if (counter == null) return null;
        int slash = counter.indexOf('/');
        double missed = Double.parseDouble(counter.substring(0, slash));
        double covered = Double.parseDouble(counter.substring(slash + 1));
        double total = missed + covered;
        return total == 0 ? 100.0 : covered * 100.0 / total;
    }

    /**
     * Every whole-report counter by type, sorted, as {@code missed/covered}: all {@link #percent}
     * reads of a report, the first counter of a type winning. Nothing else of the report, so its
     * session ids and times are not in it.
     */
    public static Map<String, String> counters(Path report) throws IOException {
        // JaCoCo writes a DOCTYPE naming its report DTD; the hardened parser refuses any DOCTYPE
        // and nothing here needs the DTD, so the declaration is dropped before parsing.
        String xml = Files.readString(report).replaceFirst("<!DOCTYPE[^>]*>", "");
        Element root = DomXml.parse(xml).getDocumentElement();
        Map<String, String> counters = new TreeMap<>();
        for (Element c : DomXml.childElements(root, "counter")) {
            counters.putIfAbsent(c.getAttribute("type"), c.getAttribute("missed") + "/" + c.getAttribute("covered"));
        }
        return counters;
    }
}
