// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.LibDir;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * The {@code [application]} keys of the lib-dir distribution: {@code lib-dir} declares it ({@code
 * true} for {@code libs/}, or the directory's name), and {@code lib-versions} and {@code
 * lib-exclude} shape it.
 */
final class ManifestLibDir {

    static final String LIB_DIR = "lib-dir";
    static final String LIB_VERSIONS = "lib-versions";
    static final String LIB_EXCLUDE = "lib-exclude";

    private ManifestLibDir() {}

    /** The declared layout, or {@code null} when {@code application} declares none. */
    static @Nullable LibDir parse(TomlTable application) {
        boolean declared = application.contains(LIB_DIR) && !Boolean.FALSE.equals(boolOrNull(application));
        if (!declared) {
            if (application.contains(LIB_VERSIONS) || application.contains(LIB_EXCLUDE)) {
                throw new JkBuildParseException("[application] `lib-versions` and `lib-exclude` shape the lib-dir"
                        + " layout; add `lib-dir = true` (or the directory's name)");
            }
            return null;
        }
        String dir = application.isBoolean(LIB_DIR)
                ? LibDir.DEFAULT_DIR
                : checkDir(application.isString(LIB_DIR) ? application.getString(LIB_DIR) : null);
        boolean versions = true;
        if (application.contains(LIB_VERSIONS)) {
            if (!application.isBoolean(LIB_VERSIONS)) {
                throw new JkBuildParseException("[application].lib-versions must be true or false");
            }
            versions = Boolean.TRUE.equals(application.getBoolean(LIB_VERSIONS));
        }
        List<String> exclude = JkBuildParser.optionalStringList(application, LIB_EXCLUDE, "application." + LIB_EXCLUDE);
        for (String rule : exclude) checkRule(rule);
        return new LibDir(dir, versions, exclude);
    }

    private static @Nullable Boolean boolOrNull(TomlTable application) {
        return application.isBoolean(LIB_DIR) ? application.getBoolean(LIB_DIR) : null;
    }

    /**
     * A directory under {@code target/}, written with forward slashes: never absolute, never the
     * target directory itself, never above it. The step prunes jars it did not write from it, so it
     * must be a directory of its own.
     */
    private static String checkDir(@Nullable String raw) {
        String dir = raw == null ? "" : raw.trim();
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        boolean bad = dir.isEmpty()
                || dir.startsWith("/")
                || dir.contains("\\")
                || dir.contains(":")
                || List.of(dir.split("/")).stream().anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."));
        if (bad) {
            throw new JkBuildParseException("[application].lib-dir must be a relative directory under target/, such as"
                    + " \"libs\"; got " + (raw == null ? "a non-string" : "\"" + raw + "\""));
        }
        return dir;
    }

    private static void checkRule(String rule) {
        String[] parts = rule.split(":", -1);
        boolean ok = parts.length <= 2 && List.of(parts).stream().noneMatch(String::isBlank);
        if (!ok) {
            throw new JkBuildParseException("[application].lib-exclude entries are `artifact`, `group:artifact` or"
                    + " `group:*`; got \"" + rule + "\"");
        }
    }
}
