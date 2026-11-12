// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk template lint}: {@link JkTemplateToml#load}'s lint over one template directory, or
 * over every {@code .jk-template.toml} under a tree. A template must declare the language,
 * framework and name its position ({@link TemplateLayout}) gives it, or the catalogue leaves it out.
 */
public final class TemplateLint {

    private TemplateLint() {}

    /**
     * @param checked how many templates were linted
     * @param failures one line per failing template or path, naming the file and the property
     */
    public record Result(int checked, List<String> failures) {

        public Result {
            failures = List.copyOf(failures);
        }
    }

    /**
     * Lints every template under {@code paths}, each resolved against {@code base}; messages name
     * files relative to {@code base} when they lie under it.
     */
    public static Result run(Path base, List<String> paths) {
        Path root = base.toAbsolutePath().normalize();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String raw : paths.isEmpty() ? List.of(".") : paths) {
            Path path = root.resolve(raw).normalize();
            if (!Files.isDirectory(path)) {
                failures.add(raw + ": not a directory");
                continue;
            }
            List<Path> templates;
            try {
                templates = templates(path);
            } catch (IOException e) {
                failures.add(raw + ": " + e.getMessage());
                continue;
            }
            if (templates.isEmpty()) {
                failures.add(raw + ": no " + JkTemplateToml.FILE_NAME + " under it");
                continue;
            }
            for (Path template : templates) {
                checked++;
                String failure = lint(template);
                if (failure != null) failures.add(relative(root, failure));
            }
        }
        return new Result(checked, failures);
    }

    /** The template roots at or under {@code path}, sorted. */
    static List<Path> templates(Path path) throws IOException {
        if (Files.isRegularFile(JkTemplateToml.file(path))) return List.of(path);
        TreeSet<Path> out = new TreeSet<>();
        PathUtil.forEachEntry(
                path,
                dir -> {
                    String name = String.valueOf(dir.getFileName());
                    if (".git".equals(name) || OutputDirs.TARGET.equals(name)) return true;
                    if (!Files.isRegularFile(JkTemplateToml.file(dir))) return false;
                    out.add(dir);
                    return true;
                },
                (entry, attrs) -> true);
        return List.copyOf(out);
    }

    private static @Nullable String lint(Path template) {
        JkTemplateToml.Meta meta;
        try {
            meta = JkTemplateToml.load(template).orElseThrow();
        } catch (IOException | IllegalArgumentException e) {
            return String.valueOf(e.getMessage());
        }
        String dirName = template.getFileName().toString();
        String name = JkTemplateToml.nameFromDir(dirName);
        String[] at = TemplateLayout.position(template);
        if (name == null || !dirName.endsWith(".g8") || at == null) return null;
        String lang = at[0];
        String framework = at[1];
        if (!TemplateSpec.FRAMEWORK_NONE.equals(framework) && !TemplateLayout.isFrameworkName(framework)) {
            return JkTemplateToml.file(template) + ": sits under " + lang + "/" + framework
                    + "/, but a framework cannot be named after a language; the catalogue skips it";
        }
        Path parent = template.getParent();
        if (parent != null && TemplateSpec.FRAMEWORK_NONE.equals(TemplateLayout.name(parent))) {
            return JkTemplateToml.file(template) + ": a template with no framework sits at "
                    + TemplateLayout.relativeDir(lang, framework, name) + ", not under "
                    + lang + "/none/";
        }
        if (JkTemplateToml.matching(meta, lang, framework, name).isPresent()) return null;
        return JkTemplateToml.file(template) + ": declares "
                + TemplateSpec.idOf(meta.language(), meta.framework(), meta.name())
                + " but sits at " + TemplateSpec.idOf(lang, framework, name)
                + "; the catalogue skips it until they match";
    }

    private static String relative(Path base, String message) {
        String prefix = base + File.separator;
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }
}
