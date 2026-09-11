// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code .bsp/jk.json}: how BSP clients (Metals, the JetBrains BSP plugin) spawn {@code jk bsp
 * serve} on stdio. Idempotent; written by {@code jk bsp install}, {@code jk ide} and {@code jk_ide}.
 */
public final class BspConnectionFile {

    private BspConnectionFile() {}

    /**
     * Write the connection file under {@code projectDir}.
     *
     * @param argv0 the {@code jk} executable the IDE should spawn — a bare {@code jk} for PATH lookup,
     *     or an explicit path
     * @return the file written
     */
    public static Path write(Path projectDir, String argv0) throws IOException {
        Path bspDir = projectDir.resolve(".bsp");
        Files.createDirectories(bspDir);
        String json = """
                {
                  "name": "jk",
                  "version": %s,
                  "bspVersion": "2.1.0",
                  "languages": ["java", "kotlin", "groovy"],
                  "argv": [%s, "bsp", "serve"]
                }
                """.formatted(Jsonl.quote(JkVersion.VERSION), Jsonl.quote(argv0));
        Path out = bspDir.resolve("jk.json");
        AtomicWrites.replace(out, json);
        return out;
    }
}
