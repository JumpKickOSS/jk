// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code org.openjfx.javafxplugin} Gradle plugin is the {@code [javafx]} table. The plugin adds
 * an OpenJFX dependency per module of its {@code javafx { version; modules }} block; jk has no such
 * implicit dependency, so the import writes them, and {@code modules} only when it differs from the
 * table's default.
 */
final class JavafxGradle {

    static final String PLUGIN = "org.openjfx.javafxplugin";

    static final List<String> DEFAULT_MODULES = List.of("javafx.controls", "javafx.fxml");

    private static final Pattern VERSION =
            Pattern.compile("\\bversion\\b\\s*(?:=\\s*|\\.set\\s*\\(\\s*|\\s+)[\"']([^\"']+)[\"']");
    private static final Pattern MODULES = Pattern.compile("\\bmodules\\b[^\\n]*");
    private static final Pattern MODULE_NAME = Pattern.compile("[\"'](javafx\\.[A-Za-z.]+)[\"']");

    private JavafxGradle() {}

    static @Nullable PluginConfig map(
            Set<String> applied, String script, Map<Scope, List<Dependency>> deps, ImportReport.Builder report) {
        if (!applied.contains(PLUGIN)) return null;
        String block = GradleScriptText.extractBlock(script, "javafx").orElse("");
        Optional<String> version = version(block);
        List<String> modules = modules(block);
        Map<String, Object> values = new LinkedHashMap<>();
        if (!modules.isEmpty() && !modules.equals(DEFAULT_MODULES)) values.put("modules", modules);
        if (version.isEmpty()) {
            report.warning("`" + PLUGIN + "` has no `javafx { version }`, so no OpenJFX dependencies were written;"
                    + " add `org.openjfx:javafx-<module>` for each module to `[dependencies]`.");
        } else {
            List<Dependency> main = new ArrayList<>(deps.getOrDefault(Scope.MAIN, List.of()));
            for (String module : modules.isEmpty() ? DEFAULT_MODULES : modules) {
                String coordinate = "org.openjfx:" + module.replace('.', '-');
                if (main.stream().anyMatch(d -> d.module().equals(coordinate))) continue;
                main.add(new Dependency(coordinate, VersionSelector.parse(version.get())));
            }
            deps.put(Scope.MAIN, main);
        }
        return new PluginConfig("javafx", values);
    }

    static Optional<String> version(String block) {
        Matcher m = VERSION.matcher(block);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** {@code modules = ['javafx.controls']}, {@code modules("javafx.controls")}, {@code modules = listOf(…)}. */
    static List<String> modules(String block) {
        List<String> out = new ArrayList<>();
        Matcher line = MODULES.matcher(block);
        while (line.find()) {
            Matcher name = MODULE_NAME.matcher(line.group());
            while (name.find()) {
                if (!out.contains(name.group(1))) out.add(name.group(1));
            }
        }
        return out;
    }
}
