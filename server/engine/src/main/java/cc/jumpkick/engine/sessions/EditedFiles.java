// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** What an episode's edited files name: the modules they sit in, and the type or file each one is. */
final class EditedFiles {

    /** Files an episode remembers; past it, the oldest are forgotten first. */
    static final int KEEP = 200;

    private static final List<String> SOURCE_EXTENSIONS = List.of(".java", ".kt", ".groovy", ".scala");

    private EditedFiles() {}

    /**
     * The module directory a project-relative file sits in: the path before its {@code src/}, {@code
     * ""} for the project's own, or null for a file under no {@code src/}.
     */
    static @Nullable String moduleDir(String file) {
        String f = file.replace('\\', '/');
        if (f.startsWith("src/")) return "";
        int at = f.indexOf("/src/");
        return at > 0 ? f.substring(0, at) : null;
    }

    /** The modules {@code files} sit in, by name, in first-edited order; the project's own is named after {@code project}. */
    static List<String> modules(Collection<String> files, String project) {
        Set<String> out = new LinkedHashSet<>();
        for (String f : files) {
            String dir = moduleDir(f);
            if (dir != null) out.add(EpisodeInference.baseName(dir.isEmpty() ? project : dir));
        }
        return new ArrayList<>(out);
    }

    /** What a file is to someone editing it: the type a JVM source declares, else the file's name. */
    static String symbol(String file) {
        String name = EpisodeInference.baseName(file);
        for (String ext : SOURCE_EXTENSIONS) {
            if (name.endsWith(ext) && name.length() > ext.length())
                return name.substring(0, name.length() - ext.length());
        }
        return name;
    }
}
