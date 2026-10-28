// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.FormatStyles;
import cc.jumpkick.model.JkBuild;
import java.util.Locale;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * A formatter the POM enforces is {@code [format] enforce = true} in the style it formats with, so
 * {@code jk guard} keeps the tree as the Maven build did. A POM with no formatter gets no {@code
 * enforce}: jk does not impose a style on a tree nobody formatted.
 */
final class FormatPlugins {

    static final String SPOTLESS = "spotless-maven-plugin";
    static final String FMT = "fmt-maven-plugin";

    private FormatPlugins() {}

    static Optional<JkBuild.FormatConfig> map(Model model, ImportReport.Builder report) {
        Optional<Plugin> spotless = PluginFacts.plugin(model, SPOTLESS);
        if (spotless.isPresent()) return spotless(spotless.get(), report);
        Optional<Plugin> fmt = PluginFacts.plugin(model, FMT);
        if (fmt.isPresent()) {
            String style = null;
            for (Xpp3Dom config : PluginFacts.configurations(fmt.get())) {
                String s = text(config.getChild("style"));
                if (s != null) style = s;
            }
            return Optional.of(enforced(
                    FormatStyles.AOSP.equalsIgnoreCase(style) ? FormatStyles.AOSP : FormatStyles.GOOGLE, null));
        }
        return Optional.empty();
    }

    /** Spotless's {@code <java>} formatter step and its {@code <kotlin><ktfmt>} style, when jk has them. */
    private static Optional<JkBuild.FormatConfig> spotless(Plugin plugin, ImportReport.Builder report) {
        String java = null;
        String kotlin = null;
        boolean javaSteps = false;
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            Xpp3Dom j = config.getChild("java");
            if (j != null) {
                javaSteps = true;
                if (j.getChild("palantirJavaFormat") != null) java = FormatStyles.PALANTIR;
                Xpp3Dom google = j.getChild("googleJavaFormat");
                if (google != null) {
                    boolean aosp = FormatStyles.AOSP.equalsIgnoreCase(text(google.getChild("style")));
                    java = aosp ? FormatStyles.AOSP : FormatStyles.GOOGLE;
                }
            }
            Xpp3Dom k = config.getChild("kotlin");
            Xpp3Dom ktfmt = k == null ? null : k.getChild("ktfmt");
            if (ktfmt != null) {
                String style = text(ktfmt.getChild("style"));
                kotlin = ktfmtStyle(style);
            }
        }
        if (java == null && javaSteps) {
            report.warning("`" + SPOTLESS + "` formats Java with a step jk does not run (palantirJavaFormat and"
                    + " googleJavaFormat are the ones it does); `[format] enforce` is left off, so `jk guard`"
                    + " formats nothing — choose `[format] java` and set `enforce = true` to have it.");
            return Optional.empty();
        }
        if (java == null && kotlin == null) return Optional.empty();
        return Optional.of(enforced(java, kotlin));
    }

    /** ktfmt's style names as jk's: its default is META, and DROPBOX and FACEBOOK are older names. */
    static String ktfmtStyle(@Nullable String style) {
        if (style == null) return FormatStyles.META;
        return switch (style.toLowerCase(Locale.ROOT)) {
            case FormatStyles.GOOGLE -> FormatStyles.GOOGLE;
            case FormatStyles.KOTLINLANG, "dropbox" -> FormatStyles.KOTLINLANG;
            default -> FormatStyles.META;
        };
    }

    private static JkBuild.FormatConfig enforced(@Nullable String java, @Nullable String kotlin) {
        return new JkBuild.FormatConfig(null, java, kotlin, null, null, null, true, null);
    }

    private static @Nullable String text(@Nullable Xpp3Dom dom) {
        if (dom == null || dom.getValue() == null) return null;
        String v = dom.getValue().strip();
        return v.isEmpty() ? null : v;
    }
}
