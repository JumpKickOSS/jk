// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code .bsp/jk.json}: how BSP clients (Metals, the JetBrains BSP plugin) spawn {@code jk bsp
 * serve} on stdio, and which languages the workspace compiles — {@code scala} among them when a
 * module does, which is what lets Metals import it. Idempotent; written by {@code jk bsp install},
 * {@code jk ide} and {@code jk_ide}.
 */
public final class BspConnectionFile {

    /** Every language jk compiles, in the order the file lists them. */
    public static final List<String> ALL_LANGUAGES = List.of("java", "kotlin", "groovy", "scala");

    private BspConnectionFile() {}

    /**
     * The languages a workspace compiles: the union of its modules' sets in {@link #ALL_LANGUAGES}
     * order, {@code java} alone for a model that names none.
     */
    public static List<String> languages(IdeWireModel model) {
        Set<String> present = new LinkedHashSet<>();
        for (int i = 0; i < model.moduleDirs().size(); i++) present.addAll(model.languagesOf(i));
        List<String> out = new ArrayList<>();
        for (String language : ALL_LANGUAGES) {
            if (present.contains(language)) out.add(language);
        }
        return out.isEmpty() ? List.of("java") : out;
    }

    /**
     * Write the connection file under {@code projectDir}.
     *
     * @param argv0 the {@code jk} executable the IDE should spawn — a bare {@code jk} for PATH lookup,
     *     or an explicit path
     * @param languages the languages the workspace compiles ({@link #languages})
     * @return the file written
     */
    public static Path write(Path projectDir, String argv0, List<String> languages) throws IOException {
        Path bspDir = projectDir.resolve(".bsp");
        Files.createDirectories(bspDir);
        List<String> quoted = new ArrayList<>();
        for (String language : languages) quoted.add(Jsonl.quote(language));
        String json = """
                {
                  "name": "jk",
                  "version": %s,
                  "bspVersion": "2.1.0",
                  "languages": [%s],
                  "argv": [%s, "bsp", "serve"]
                }
                """.formatted(Jsonl.quote(JkVersion.VERSION), String.join(", ", quoted), Jsonl.quote(argv0));
        Path out = bspDir.resolve("jk.json");
        AtomicWrites.replace(out, json);
        return out;
    }
}
