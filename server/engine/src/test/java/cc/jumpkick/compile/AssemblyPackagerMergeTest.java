// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Files every jar ships at one path and a runtime reads as one: each jar's part survives the fat jar. */
class AssemblyPackagerMergeTest {

    @Test
    void every_jar_s_reference_conf_is_kept(@TempDir Path tmp) throws IOException {
        Path actor = jar(tmp, "akka-actor.jar", Map.of("reference.conf", "akka.actor { timeout = 5s }"), false);
        Path http = jar(tmp, "akka-http.jar", Map.of("reference.conf", "akka.http { port = 80 }"), false);

        String merged = text(assemble(tmp, actor, http), "reference.conf");

        assertThat(merged).contains("akka.actor { timeout = 5s }").contains("akka.http { port = 80 }");
    }

    @Test
    void every_spring_imports_file_and_aot_factories_are_merged(@TempDir Path tmp) throws IOException {
        String imports =
                "META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports";
        Path a = jar(tmp, "a.jar", Map.of(imports, "a.Config", "META-INF/spring/aot.factories", "x.Y=a.One"), false);
        Path b = jar(tmp, "b.jar", Map.of(imports, "b.Config", "META-INF/spring/aot.factories", "x.Y=b.Two"), false);

        Path out = assemble(tmp, a, b);

        assertThat(text(out, imports)).contains("a.Config").contains("b.Config");
        assertThat(text(out, "META-INF/spring/aot.factories")).contains("x.Y=a.One,b.Two");
    }

    @Test
    void log4j_plugin_caches_are_unioned(@TempDir Path tmp) throws IOException {
        Path core = binaryJar(tmp, "log4j-core.jar", Log4j2PluginCache.PATH, cache("core", "console", "a.Console"));
        Path extra = binaryJar(tmp, "extra.jar", Log4j2PluginCache.PATH, cache("core", "kafka", "b.Kafka"));

        Path out = assemble(tmp, core, extra);

        try (JarFile jf = new JarFile(out.toFile());
                DataInputStream in = new DataInputStream(jf.getInputStream(jf.getJarEntry(Log4j2PluginCache.PATH)))) {
            assertThat(in.readInt()).isEqualTo(1);
            assertThat(in.readUTF()).isEqualTo("core");
            int n = in.readInt();
            List<String> classes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                in.readUTF();
                classes.add(in.readUTF());
                in.readUTF();
                in.readBoolean();
                in.readBoolean();
            }
            assertThat(classes).containsExactly("a.Console", "b.Kafka");
        }
    }

    @Test
    void a_multi_release_input_makes_the_fat_jar_multi_release(@TempDir Path tmp) throws IOException {
        Path mr = jar(tmp, "mr.jar", Map.of("META-INF/versions/17/x/Y.class", "v17"), true);

        try (JarFile jf = new JarFile(assemble(tmp, mr).toFile())) {
            assertThat(jf.getManifest().getMainAttributes().getValue("Multi-Release"))
                    .isEqualTo("true");
        }
    }

    @Test
    void no_multi_release_input_leaves_the_attribute_off(@TempDir Path tmp) throws IOException {
        Path plain = jar(tmp, "plain.jar", Map.of("x/Y.class", "v"), false);

        try (JarFile jf = new JarFile(assemble(tmp, plain).toFile())) {
            assertThat(jf.getManifest().getMainAttributes().getValue("Multi-Release"))
                    .isNull();
        }
    }

    private static Path assemble(Path tmp, Path... deps) throws IOException {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path out = tmp.resolve("app-all.jar");
        return new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(deps), out, "app.Main", Map.of(), 0L));
    }

    private static String text(Path jar, String name) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry(name);
            assertThat(entry).as(name).isNotNull();
            return new String(jf.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] cache(String category, String key, String className) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(1);
            out.writeUTF(category);
            out.writeInt(1);
            out.writeUTF(key);
            out.writeUTF(className);
            out.writeUTF(key);
            out.writeBoolean(true);
            out.writeBoolean(false);
        }
        return buf.toByteArray();
    }

    private static Path binaryJar(Path tmp, String fileName, String entry, byte[] content) throws IOException {
        Path jar = tmp.resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry(entry));
            jos.write(content);
            jos.closeEntry();
        }
        return jar;
    }

    private static Path jar(Path tmp, String fileName, Map<String, String> entries, boolean multiRelease)
            throws IOException {
        Path jar = tmp.resolve(fileName);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) manifest.getMainAttributes().put(new Attributes.Name("Multi-Release"), "true");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jar;
    }
}
