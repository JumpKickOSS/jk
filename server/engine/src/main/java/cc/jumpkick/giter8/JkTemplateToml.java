// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

/**
 * {@code .jk-template.toml} at a Giter8 template root: picker metadata. Path
 * {@code <lang>/<framework>/<name>.g8} is source of truth; the file must match.
 */
public final class JkTemplateToml {

    public static final String FILE_NAME = ".jk-template.toml";

    public record Meta(
            String language, String framework, String name, String description, List<String> layouts) {

        public Meta {
            language = language.strip().toLowerCase(Locale.ROOT);
            framework = framework.strip().toLowerCase(Locale.ROOT);
            name = name.strip().toLowerCase(Locale.ROOT);
            if (description == null) description = "";
            layouts = layouts == null || layouts.isEmpty()
                    ? List.of(Giter8ShortNames.LAYOUT_TRADITIONAL, Giter8ShortNames.LAYOUT_SIMPLE)
                    : List.copyOf(layouts);
        }
    }

    private JkTemplateToml() {}

    public static Path file(Path templateRoot) {
        return templateRoot.resolve(FILE_NAME);
    }

    public static Optional<Meta> tryRead(Path templateRoot) {
        Path f = file(templateRoot);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(parse(Files.readString(f, StandardCharsets.UTF_8), f.toString()));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Meta parse(String toml, String displayPath) {
        TomlParseResult r = Toml.parse(toml);
        if (r.hasErrors()) {
            throw new IllegalArgumentException(
                    displayPath + " has invalid TOML: " + r.errors().getFirst().getMessage());
        }
        String language = requireIdent(r.getString("language"), "language", displayPath);
        if (!isLang(language)) {
            throw new IllegalArgumentException(displayPath + ": language must be java, kotlin, or groovy");
        }
        String framework = requireIdent(r.getString("framework"), "framework", displayPath);
        String name = requireIdent(r.getString("name"), "name", displayPath);
        String description = r.getString("description");
        if (description == null) description = "";
        List<String> layouts = layoutsOf(r.getArray("layouts"), displayPath);
        return new Meta(language, framework, name, description.strip(), layouts);
    }

    /**
     * @return empty when language/framework/name disagree with the directory path
     */
    public static Optional<Meta> matching(Meta meta, String language, String framework, String name) {
        if (meta.language().equals(language)
                && meta.framework().equals(framework)
                && meta.name().equals(name)) {
            return Optional.of(meta);
        }
        return Optional.empty();
    }

    static boolean isLang(String s) {
        return "java".equals(s) || "kotlin".equals(s) || "groovy".equals(s);
    }

    static boolean isKebab(String s) {
        return s != null && s.matches("[a-z][a-z0-9-]*");
    }

    static @Nullable String nameFromDir(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String n = fileName;
        if (n.endsWith(".g8")) n = n.substring(0, n.length() - 3);
        n = n.toLowerCase(Locale.ROOT);
        return isKebab(n) ? n : null;
    }

    private static String requireIdent(@Nullable String raw, String key, String displayPath) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(displayPath + ": missing " + key);
        }
        String v = raw.strip().toLowerCase(Locale.ROOT);
        if (!isKebab(v)) {
            throw new IllegalArgumentException(displayPath + ": " + key + " must be kebab-case");
        }
        return v;
    }

    private static List<String> layoutsOf(@Nullable TomlArray arr, String displayPath) {
        if (arr == null) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String raw = arr.getString(i);
            if (raw == null || raw.isBlank()) continue;
            if (!Giter8ShortNames.isKnownLayoutToken(raw)) {
                throw new IllegalArgumentException(displayPath + ": unknown layout " + raw);
            }
            String n = Giter8ShortNames.normalizeLayout(raw);
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }
}
