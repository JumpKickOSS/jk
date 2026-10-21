// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML, HTML, stylesheets, diagrams and SQL. XML contributes the class names it wires (Spring
 * beans, web.xml, persistence units, logback) and a POM's modules and dependencies; HTML and
 * stylesheets the files they load; a diagram the types it draws; SQL the tables it creates.
 */
final class MarkupExtractors {

    private MarkupExtractors() {}

    private static final Pattern POM_DEPENDENCY = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
    private static final Pattern POM_MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern HTML_REF = Pattern.compile(
            "(?i)<(script|link|a|img|iframe|source)\\b[^>]*?\\b(src|href)\\s*=\\s*[\"']([^\"'#?]+)[\"']");
    private static final Pattern TITLE = Pattern.compile("(?is)<title>(.*?)</title>");
    private static final Pattern CSS_IMPORT =
            Pattern.compile("@(?:import|use|forward)\\s+(?:url\\()?[\"']([^\"']+)[\"']");
    private static final Pattern CAMEL = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\\b");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bcreate\\s+(?:or\\s+replace\\s+)?(table|view)\\s+(?:if\\s+not\\s+exists\\s+)?[`\"\\[]?([\\w.]+)[`\"\\]]?");

    static Fragment xml(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "config");
        Lines lines = new Lines(text);
        String body = blankComments(text);
        if (relPath.equals("pom.xml") || relPath.endsWith("/pom.xml")) {
            Matcher d = POM_DEPENDENCY.matcher(body);
            while (d.find()) {
                Matcher g = GROUP_ID.matcher(d.group(1));
                Matcher a = ARTIFACT_ID.matcher(d.group(1));
                if (g.find() && a.find() && !g.group(1).contains("${")) {
                    Dependencies.declare(b, b.fileId(), g.group(1), a.group(1), lines.at(d.start()));
                }
            }
            Matcher m = POM_MODULE.matcher(body);
            while (m.find()) {
                b.mention(
                        b.fileId(),
                        Mention.Kind.FILE,
                        m.group(1) + "/pom.xml",
                        lines.at(m.start()),
                        Relations.CONTAINS);
            }
        }
        Lines.eachClassName(body, lines, (cls, line) -> b.mention(b.fileId(), Mention.Kind.CLASS, cls, line));
        return b.build();
    }

    static Fragment html(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "page");
        Lines lines = new Lines(text);
        Matcher t = TITLE.matcher(text);
        if (t.find()) b.annotateFile(Map.of("title", t.group(1).strip()));
        Matcher r = HTML_REF.matcher(text);
        while (r.find()) {
            String target = r.group(3);
            if (!target.contains("://") && !target.startsWith("data:") && !target.startsWith("mailto:")) {
                b.mention(b.fileId(), Mention.Kind.FILE, target, lines.at(r.start()));
            }
        }
        return b.build();
    }

    static Fragment css(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", "stylesheet");
        Lines lines = new Lines(text);
        String ext = relPath.substring(relPath.lastIndexOf('.'));
        Matcher m = CSS_IMPORT.matcher(text);
        while (m.find()) {
            String target = m.group(1);
            if (target.contains("://") || target.startsWith("sass:") || target.startsWith("~")) continue;
            for (String candidate : partials(target, ext)) {
                b.mention(b.fileId(), Mention.Kind.FILE, candidate, lines.at(m.start()), Relations.IMPORTS);
            }
        }
        return b.build();
    }

    static Fragment diagram(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "diagram");
        Lines lines = new Lines(text);
        Set<String> seen = new HashSet<>();
        Matcher m = CAMEL.matcher(text);
        while (m.find()) {
            if (seen.add(m.group())) b.mention(b.fileId(), Mention.Kind.SYMBOL, m.group(), lines.at(m.start()));
        }
        return b.build();
    }

    static Fragment sql(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", "sql");
        Lines lines = new Lines(text);
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            String kind = m.group(1).toLowerCase(Locale.ROOT);
            String name = m.group(2);
            int line = lines.at(m.start());
            String id = b.declared(kind + " " + name, name, "code", line, Map.of("node_kind", kind));
            b.edge(b.fileId(), id, Relations.CONTAINS, Confidence.EXTRACTED, line);
        }
        return b.build();
    }

    /** The files a Sass/Less import may name: as written, with the extension, and as a partial. */
    private static List<String> partials(String target, String ext) {
        int slash = target.lastIndexOf('/');
        String dir = slash < 0 ? "" : target.substring(0, slash + 1);
        String name = target.substring(slash + 1);
        if (name.contains(".")) return List.of(target);
        return List.of(
                target + ext,
                dir + "_" + name + ext,
                target + ".css",
                target + "/_index" + ext,
                target + "/index" + ext);
    }

    /** {@code text} with XML comments blanked, offsets kept. */
    private static String blankComments(String text) {
        Matcher m = XML_COMMENT.matcher(text);
        if (!m.find()) return text;
        StringBuilder out = new StringBuilder(text);
        do {
            for (int i = m.start(); i < m.end(); i++) {
                if (out.charAt(i) != '\n') out.setCharAt(i, ' ');
            }
        } while (m.find());
        return out.toString();
    }
}
