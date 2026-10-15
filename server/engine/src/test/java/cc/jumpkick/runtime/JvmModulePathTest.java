// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which runtime-classpath jars a {@code [contribute.jvm-modules]} puts on the module path. */
class JvmModulePathTest {

    private static final PluginContributions.JvmModuleUse FX =
            new PluginContributions.JvmModuleUse("javafx.", List.of("javafx.controls", "javafx.fxml"));

    @TempDir
    Path tmp;

    @Test
    void explicit_modules_under_the_prefix_ride_the_module_path_with_the_added_modules() throws Exception {
        Path base = moduleJar("javafx.base");
        Path graphics = moduleJar("javafx.graphics");
        Path other = moduleJar("org.example.lib");
        Path plain = plainJar("plain.jar", null);

        List<String> args = JvmModulePath.args(List.of(FX), List.of(plain, base, other, graphics));

        assertThat(args)
                .containsExactly(
                        "--module-path",
                        Classpaths.join(List.of(base, graphics)),
                        "--add-modules",
                        "javafx.controls,javafx.fxml");
    }

    @Test
    void an_automatic_module_never_joins_even_under_the_prefix() throws Exception {
        Path automatic = plainJar("javafx-base-empty.jar", "javafx.baseEmpty");
        assertThat(JvmModulePath.explicitModule(automatic)).isNull();
        assertThat(JvmModulePath.args(List.of(FX), List.of(automatic))).isEmpty();
    }

    @Test
    void nothing_without_a_contribution_or_without_a_matching_jar() throws Exception {
        Path base = moduleJar("javafx.base");
        assertThat(JvmModulePath.args(List.of(), List.of(base))).isEmpty();
        assertThat(JvmModulePath.args(List.of(FX), List.of(plainJar("plain.jar", null), tmp.resolve("classes"))))
                .isEmpty();
    }

    /** A jar whose {@code module-info.class} declares {@code name}. */
    private Path moduleJar(String name) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src-" + name));
        Path out = Files.createDirectories(tmp.resolve("out-" + name));
        Files.writeString(src.resolve("module-info.java"), "module " + name + " {}\n");
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "-d",
                        out.toString(),
                        src.resolve("module-info.java").toString());
        assertThat(rc).isZero();
        Path jar = tmp.resolve(name + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(Files.readAllBytes(out.resolve("module-info.class")));
            jos.closeEntry();
        }
        return jar;
    }

    /** A jar with no module-info, naming an automatic module when {@code automaticName} is set. */
    private Path plainJar(String file, @Nullable String automaticName) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticName != null) manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticName);
        Path jar = tmp.resolve(file);
        try (OutputStream os = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(os, manifest)) {
            jos.putNextEntry(new JarEntry("p/Readme.txt"));
            jos.write("x".getBytes());
            jos.closeEntry();
        }
        return jar;
    }
}
