// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The edits a packaged {@code META-INF/plugin.xml} gets: {@code <version>} is the module's version
 * and {@code <idea-version>} carries the build range. The text is edited in place, so comments,
 * CDATA and layout reach the zip as written.
 */
final class PluginXml {

    private static final Pattern VERSION = Pattern.compile("<version>[^<]*</version>");
    private static final Pattern IDEA_VERSION =
            Pattern.compile("<idea-version\\b[^>]*/>|<idea-version\\b[^>]*>\\s*</idea-version>");

    /**
     * Where a missing element goes: after the line holding {@code <version>}, else {@code <name>},
     * else {@code <id>}, else the root tag.
     */
    private static final List<Pattern> ANCHORS = List.of(
            Pattern.compile("<version>[^<]*</version>[^\\n]*\\n"),
            Pattern.compile("<name>[^<]*</name>[^\\n]*\\n"),
            Pattern.compile("<id>[^<]*</id>[^\\n]*\\n"),
            Pattern.compile("<idea-plugin\\b[^>]*>[^\\n]*\\n"));

    private PluginXml() {}

    /** {@code xml} with {@code version} and the {@code since}/{@code until} build range set. */
    static String patch(String xml, String version, String since, @Nullable String until) {
        String ideaVersion = "<idea-version since-build=\"" + since + "\""
                + (until == null ? "" : " until-build=\"" + until + "\"") + "/>";
        String out = replaceOrInsert(xml, VERSION, "<version>" + version + "</version>");
        return replaceOrInsert(out, IDEA_VERSION, ideaVersion);
    }

    private static String replaceOrInsert(String xml, Pattern pattern, String element) {
        Matcher existing = pattern.matcher(xml);
        if (existing.find()) {
            return xml.substring(0, existing.start()) + element + xml.substring(existing.end());
        }
        for (Pattern anchor : ANCHORS) {
            Matcher m = anchor.matcher(xml);
            if (m.find()) return xml.substring(0, m.end()) + "  " + element + "\n" + xml.substring(m.end());
        }
        throw new IllegalArgumentException("plugin.xml has no <idea-plugin> root to add " + element + " to");
    }
}
