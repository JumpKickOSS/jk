// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.tools.jib.api.buildplan.FileEntry;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LayeredImagePermissionsTest {

    /** An entrypoint script keeps its exec bit in the image; a plain file stays 0644. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anExecutableFileKeepsItsExecBitInTheLayer(@TempDir Path src) throws Exception {
        Path script =
                Files.writeString(Files.createDirectories(src.resolve("bin")).resolve("start.sh"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(src.resolve("app.js"), "console.log(1)\n");

        Map<String, String> modes = new TreeMap<>();
        for (FileEntry e :
                LayeredImage.layer(new LayeredImage.Layer("app", src, "/app")).getEntries()) {
            modes.put(e.getExtractionPath().toString(), e.getPermissions().toOctalString());
        }

        assertThat(modes)
                .containsEntry("/app/bin/start.sh", "755")
                .containsEntry("/app/app.js", FilePermissions.DEFAULT_FILE_PERMISSIONS.toOctalString());
    }
}
