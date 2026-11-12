// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.scaffold.NewProjectStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a template sits in a catalogue tree. A template with no framework is {@code
 * <lang>/<name>.g8}, one with a framework {@code <lang>/<framework>/<name>.g8}. Inside a language
 * directory a child that ends in {@code .g8} or holds a {@code .jk-template.toml} is a template, any
 * other child a framework, so telling them apart reads no file. A framework may not be named after a
 * language. A {@code <lang>/none/} directory, the layout before frameworks became optional, still
 * reads as templates with no framework; {@link TemplateLint} asks for them to move.
 *
 * <p>Disk catalogues, plugin jars and the shipped tree all scan through {@link #scan}, so the three
 * cannot disagree about what the layout is.
 */
public final class TemplateLayout {

    private TemplateLayout() {}

    /** One template tree found by {@link #scan}: where it sits, not yet whether it is valid. */
    @FunctionalInterface
    public interface Visit {
        void accept(String lang, String framework, Path template) throws IOException;
    }

    /** The template directory for an id's parts, relative to a catalogue root. */
    public static String relativeDir(String lang, String framework, String name) {
        return TemplateSpec.FRAMEWORK_NONE.equals(framework)
                ? lang + "/" + name + ".g8"
                : lang + "/" + framework + "/" + name + ".g8";
    }

    /** Whether a framework directory may carry this name. */
    public static boolean isFrameworkName(String name) {
        return JkTemplateToml.isKebab(name) && !NewProjectStep.LANGS.contains(name);
    }

    /**
     * Visits every template tree under {@code root}'s language directories, on any filesystem (a zip
     * one included).
     *
     * @return whether {@code root} has a language directory at all, i.e. is laid out as a catalogue
     */
    public static boolean scan(Path root, Visit visit) throws IOException {
        boolean catalogue = false;
        for (String lang : NewProjectStep.LANGS) {
            Path langDir = root.resolve(lang);
            if (!Files.isDirectory(langDir)) continue;
            catalogue = true;
            for (Path child : childDirs(langDir)) {
                String n = name(child);
                if (n.startsWith(".")) continue;
                if (isTemplateDir(child)) {
                    visit.accept(lang, TemplateSpec.FRAMEWORK_NONE, child);
                    continue;
                }
                if (!TemplateSpec.FRAMEWORK_NONE.equals(n) && !isFrameworkName(n)) continue;
                for (Path tmpl : childDirs(child)) {
                    if (!name(tmpl).startsWith(".")) visit.accept(lang, n, tmpl);
                }
            }
        }
        return catalogue;
    }

    /**
     * Where {@code template} sits, read from its path: {@code [lang, framework]}, or null when its
     * path is not a catalogue position.
     */
    static String @Nullable [] position(Path template) {
        Path parent = template.getParent();
        if (parent == null) return null;
        String p = name(parent);
        if (JkTemplateToml.isLang(p)) return new String[] {p, TemplateSpec.FRAMEWORK_NONE};
        Path grand = parent.getParent();
        if (grand == null || !JkTemplateToml.isLang(name(grand))) return null;
        return new String[] {name(grand), p};
    }

    static boolean isTemplateDir(Path dir) {
        return name(dir).endsWith(".g8") || Files.isRegularFile(JkTemplateToml.file(dir));
    }

    private static List<Path> childDirs(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        PathUtil.forEachChild(dir, (child, attrs) -> {
            if (attrs.isDirectory()) out.add(child);
            return true;
        });
        return out;
    }

    /** A zip filesystem may spell a directory's name with its trailing separator. */
    static String name(Path dir) {
        String n = String.valueOf(dir.getFileName());
        return n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
    }
}
