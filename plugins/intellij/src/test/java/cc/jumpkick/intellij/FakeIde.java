// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A distribution laid out as an IntelliJ one is, small enough to read: {@code product-info.json}
 * with launch entries and a layout, and each plugin's or module's jar holding its descriptor.
 */
final class FakeIde {

    private final Path home;
    private final List<String> layout = new ArrayList<>();

    FakeIde(Path home) {
        this.home = home;
    }

    /** A bundled plugin under {@code plugins/<dir>/lib/<dir>.jar} with {@code descriptor} as its plugin.xml. */
    FakeIde plugin(String id, String dir, String descriptor) throws IOException {
        String jar = "plugins/" + dir + "/lib/" + dir + ".jar";
        jar(jar, Map.of("META-INF/plugin.xml", descriptor));
        layout.add("{\"name\":\"" + id + "\",\"kind\":\"plugin\",\"classPath\":[\"" + jar + "\"]}");
        return this;
    }

    /** A content module in {@code jar} (relative to the home) with {@code descriptor} as {@code <name>.xml}. */
    FakeIde module(String name, String kind, String jar, String descriptor) throws IOException {
        jar(jar, Map.of(name + ".xml", descriptor));
        layout.add("{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"classPath\":[\"" + jar + "\"]}");
        return this;
    }

    /** Write {@code product-info.json} and answer the home. */
    Path write() throws IOException {
        String launch = """
                {"os":"%s","arch":"%s","bootClassPathJarNames":["util.jar"],
                 "additionalJvmArguments":["-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader",
                   "-Djna.boot.library.path=%%IDE_HOME%%/lib/jna","-Dpty4j.preferred.native.folder=$IDE_HOME/lib/pty4j",
                   "-Xbootclasspath/a:$APP_PACKAGE/lib/nio-fs.jar","-Dsplash=true","--add-opens=java.base/java.lang=ALL-UNNAMED"]}
                """.formatted(ProductInfo.hostOs(), ProductInfo.hostArch());
        String info = "{\"name\":\"IntelliJ IDEA\",\"version\":\"2026.2.3\",\"buildNumber\":\"262.10968.63\","
                + "\"launch\":[" + launch + "],\"layout\":[" + String.join(",", layout) + "]}";
        Files.createDirectories(home);
        Files.writeString(home.resolve("product-info.json"), info, StandardCharsets.UTF_8);
        return home;
    }

    private void jar(String rel, Map<String, String> entries) throws IOException {
        Path file = home.resolve(rel);
        Files.createDirectories(file.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> e : new LinkedHashMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
}
