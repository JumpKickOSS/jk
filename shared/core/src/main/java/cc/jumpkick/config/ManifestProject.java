// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.tomlj.TomlTable;

/**
 * [project] table for {@link JkBuildParser}: name, inheritance, JDK/language versions.
 */
@NullMarked
public final class ManifestProject {

    private ManifestProject() {}

    /**
     * @param workspaceRoot when true, omitted optional fields keep local defaults (no inherit);
     *     group/name/version stay required and concrete. When false (module or standalone), omitted
     *     fields other than {@code name} and {@code description} mark workspace inheritance —
     *     members resolve them from the root; standalones drop optional inherits and still require
     *     concrete group+version (or fail if those were omitted).
     */
    static JkBuild.Project parseProject(TomlTable root, boolean workspaceRoot) {
        TomlTable project = root.getTable("project");
        if (project == null) {
            throw new JkBuildParseException("jk.toml must declare a top-level `[project]` table");
        }
        // name is the module identity — never workspace-inherited (Cargo package.name rule).
        if (project.isTable("name")) {
            throw new JkBuildParseException(
                    "project.name cannot use workspace inheritance — every module must declare its own name");
        }
        java.util.EnumSet<JkBuild.ProjectInherit> inherits = java.util.EnumSet.noneOf(JkBuild.ProjectInherit.class);

        String name = requireString(project, "name", "project.name");

        String group = parseInheritableString(
                project,
                "group",
                JkBuild.ProjectInherit.GROUP,
                inherits,
                workspaceRoot,
                /* requiredWhenRootOrStandalone */ true);

        String version = parseInheritableString(
                project,
                "version",
                JkBuild.ProjectInherit.VERSION,
                inherits,
                workspaceRoot,
                /* requiredWhenRootOrStandalone */ true);

        String jdk;
        if (isWorkspaceInherit(project, "jdk") || (!workspaceRoot && !project.contains("jdk"))) {
            inherits.add(JkBuild.ProjectInherit.JDK);
            jdk = null;
        } else {
            jdk = parseJdkSpec(project);
        }

        int java;
        if (isWorkspaceInherit(project, "java") || (!workspaceRoot && !project.contains("java"))) {
            inherits.add(JkBuild.ProjectInherit.JAVA);
            java = 0;
        } else {
            java = parseJavaRelease(project);
            requireSupportedMajor("project.java", java);
        }

        VersionSelector kotlin;
        if (isWorkspaceInherit(project, "kotlin") || (!workspaceRoot && !project.contains("kotlin"))) {
            inherits.add(JkBuild.ProjectInherit.KOTLIN);
            kotlin = null;
        } else {
            kotlin = parseKotlinVersion(project);
        }

        VersionSelector groovy;
        if (isWorkspaceInherit(project, "groovy") || (!workspaceRoot && !project.contains("groovy"))) {
            inherits.add(JkBuild.ProjectInherit.GROOVY);
            groovy = null;
        } else {
            groovy = parseGroovyVersion(project);
        }

        // sources = true → PUBLISH; sources = "always" → ALWAYS; absent/false → DISABLED
        // description is special: omit stays null (no auto-inherit). Explicit description.workspace = true ok.
        JkBuild.SourcesMode sourcesMode;
        if (isWorkspaceInherit(project, "sources") || (!workspaceRoot && !project.contains("sources"))) {
            inherits.add(JkBuild.ProjectInherit.SOURCES);
            sourcesMode = JkBuild.SourcesMode.DISABLED;
        } else {
            Object sourcesRaw = project.get("sources");
            if ("always".equalsIgnoreCase(sourcesRaw instanceof String s ? s : "")) {
                sourcesMode = JkBuild.SourcesMode.ALWAYS;
            } else if (Boolean.TRUE.equals(sourcesRaw)) {
                sourcesMode = JkBuild.SourcesMode.PUBLISH;
            } else {
                sourcesMode = JkBuild.SourcesMode.DISABLED;
            }
        }

        String description;
        if (isWorkspaceInherit(project, "description")) {
            inherits.add(JkBuild.ProjectInherit.DESCRIPTION);
            description = null;
        } else {
            // Omitted description stays unset — never auto-inherits from the workspace root.
            description = project.getString("description");
        }

        boolean m2install;
        if (isWorkspaceInherit(project, "m2install") || (!workspaceRoot && !project.contains("m2install"))) {
            inherits.add(JkBuild.ProjectInherit.M2INSTALL);
            m2install = false;
        } else {
            // m2install defaults to false: ~/.cache/jk is primary. true mirrors into ~/.m2.
            m2install = Boolean.TRUE.equals(project.getBoolean("m2install"));
        }

        JkBuild.Layout layout;
        if (isWorkspaceInherit(project, "layout") || (!workspaceRoot && !project.contains("layout"))) {
            inherits.add(JkBuild.ProjectInherit.LAYOUT);
            layout = JkBuild.Layout.AUTO;
        } else if (project.contains("layout")) {
            String layoutRaw = project.getString("layout");
            try {
                layout = JkBuild.Layout.parse(layoutRaw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(e.getMessage());
            }
        } else {
            layout = JkBuild.Layout.AUTO;
        }

        return new JkBuild.Project(
                group, name, version, jdk, java, kotlin, groovy, sourcesMode, description, m2install, layout, inherits);
    }

    /**
     * String project field that may be concrete, {@code field.workspace = true}, or omitted (member
     * → inherit; root → error if required).
     */
    static String parseInheritableString(
            TomlTable project,
            String key,
            JkBuild.ProjectInherit inherit,
            java.util.EnumSet<JkBuild.ProjectInherit> inherits,
            boolean workspaceRoot,
            boolean required) {
        String path = "project." + key;
        if (isWorkspaceInherit(project, key)) {
            if (workspaceRoot) {
                throw new JkBuildParseException("workspace root must set a concrete " + path + " (`" + key
                        + ".workspace = true` is only valid" + " on workspace modules)");
            }
            inherits.add(inherit);
            return JkBuild.VERSION_FROM_WORKSPACE;
        }
        if (!project.contains(key)) {
            if (workspaceRoot || required) {
                // Members: omit → inherit. Roots: omit of group/version → error.
                if (!workspaceRoot) {
                    inherits.add(inherit);
                    return JkBuild.VERSION_FROM_WORKSPACE;
                }
            }
            if (required) {
                throw new JkBuildParseException("jk.toml is missing required key `" + path + "`");
            }
            return null;
        }
        String value = project.getString(key);
        if (value == null) {
            throw new JkBuildParseException(path + " must be a string (e.g. \"1.0.0\") or `{ workspace = true }`");
        }
        if (value.isBlank()) {
            throw new JkBuildParseException(path + " must not be blank");
        }
        return value;
    }

    /**
     * Cargo-style {@code field.workspace = true} / {@code field = { workspace = true }} under
     * {@code [project]}. Only the boolean {@code true} is legal; extra keys are rejected.
     */
    static boolean isWorkspaceInherit(TomlTable project, String key) {
        if (!project.contains(key) || !project.isTable(key)) return false;
        TomlTable t = project.getTable(key);
        Boolean ws = t.getBoolean("workspace");
        if (!Boolean.TRUE.equals(ws)) {
            throw new JkBuildParseException("project."
                    + key
                    + ".workspace must be `true` (the only legal value), or set project."
                    + key
                    + " to a concrete value");
        }
        for (String k : t.keySet()) {
            if (!"workspace".equals(k)) {
                throw new JkBuildParseException("project."
                        + key
                        + " with workspace inheritance must only set `workspace = true` (unexpected key `"
                        + k
                        + "`)");
            }
        }
        return true;
    }

    /**
     * {@code project.jdk}: vendor+major, bare major, unquoted int, or keyword
     * ({@code lts}/{@code stable}/{@code latest}/{@code native}). Point releases rejected.
     * Absent/blank → null.
     */
    static String parseJdkSpec(TomlTable project) {
        String spec = parseVersionSpec(project, "jdk", "project.jdk", "\"temurin-25\" or \"25\"");
        if (spec == null || isVersionKeyword(spec)) return spec;
        int major = JkBuild.Project.majorOf(spec);
        if (major == 0) {
            throw new JkBuildParseException(
                    "project.jdk = \"" + spec + "\" must include a major version (e.g. \"temurin-25\" or \"25\")");
        }
        requireSupportedMajor("project.jdk", major);
        return spec;
    }

    /**
     * {@code [native].graal}: same shape as {@code project.jdk}; {@code "native"} ≡ {@code "graalvm"}.
     * Point releases rejected. Absent/blank → null.
     */
    static String parseGraalSpec(TomlTable native_) {
        return parseVersionSpec(native_, "graal", "[native].graal", "\"graalvm-25\", \"25\", or \"native\"");
    }

    /**
     * Shared {@code jdk}/{@code graal} spec parser: int or string; keywords pass through; point
     * releases rejected. Null when absent/blank.
     */
    static String parseVersionSpec(TomlTable table, String key, String pathLabel, String exampleHint) {
        if (!table.contains(key)) return null;
        Object raw = table.get(key);
        String spec;
        if (raw instanceof Long l) {
            spec = Long.toString(l);
        } else if (raw instanceof String s) {
            spec = s.trim();
        } else {
            throw new JkBuildParseException(pathLabel + " must be a string, e.g. " + exampleHint);
        }
        if (spec.isEmpty()) return null;
        if (isVersionKeyword(spec)) return spec;
        if (JkBuild.Project.hasPointRelease(spec)) {
            throw new JkBuildParseException(pathLabel
                    + " = \""
                    + spec
                    + "\" must not pin a point release — use \"<vendor>-<major>\" or "
                    + "\"<major>\" (e.g. "
                    + exampleHint
                    + "); jk keeps the patch current.");
        }
        return spec;
    }

    /** Version keywords (kept local so :core does not depend on :toolchain-jdk). */
    static boolean isVersionKeyword(String spec) {
        String norm = spec.toLowerCase(Locale.ROOT);
        return norm.equals("lts") || norm.equals("stable") || norm.equals("latest") || norm.equals("native");
    }

    /**
     * {@code project.java} accepts either an unquoted TOML integer or a quoted numeric string
     * (coerced). Absent → {@code 0} ({@code javaRelease} falls back to the {@code jdk} major).
     */
    static int parseJavaRelease(TomlTable project) {
        if (!project.contains("java")) return 0;
        Object raw = project.get("java");
        long value;
        if (raw instanceof Long l) {
            value = l;
        } else if (raw instanceof String s) {
            try {
                value = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new JkBuildParseException("project.java must be an integer, got: \"" + s + "\"");
            }
        } else {
            throw new JkBuildParseException("project.java must be an integer");
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new JkBuildParseException("project.java out of range: " + value);
        }
        return (int) value;
    }

    /**
     * {@code project.kotlin} is a Kotlin compiler version selector (string), parsed the same way as a
     * floating dependency version: bare {@code 2.3.21} → caret, {@code =2.3.21} pins. Absent → {@code
     * null} (a Java project).
     */
    static VersionSelector parseKotlinVersion(TomlTable project) {
        if (!project.contains("kotlin")) return null;
        String raw = project.getString("kotlin");
        if (raw == null) {
            throw new JkBuildParseException("project.kotlin must be a version string, e.g. \"2.3.21\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * {@code project.groovy} is a Groovy compiler version selector (string), parsed the same way as
     * a floating dependency version: bare {@code 5.0.4} → caret, {@code =5.0.4} pins. Absent →
     * {@code null} (not a Groovy project).
     */
    static VersionSelector parseGroovyVersion(TomlTable project) {
        if (!project.contains("groovy")) return null;
        String raw = project.getString("groovy");
        if (raw == null) {
            throw new JkBuildParseException("project.groovy must be a version string, e.g. \"5.0.4\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * jk only supports JDK 17 and above (LTS + latest). Reject any older value at parse time so users
     * learn the constraint up front instead of in the middle of a resolve.
     */
    static void requireSupportedMajor(String path, int value) {
        if (value == 0) return;
        if (value < 17) {
            throw new JkBuildParseException(path
                    + " = "
                    + value
                    + " is not supported — jk targets JDK 17 and above "
                    + "(LTS: 17, 21, 25, … plus the latest release).");
        }
    }

    // Dependencies

}
