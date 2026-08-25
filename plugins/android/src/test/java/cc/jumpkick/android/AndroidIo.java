// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Path;

/**
 * This plugin's defaults on top of the shared {@link FakeBuildIo}.
 *
 * <p>The engine fake itself is shared with every other plugin's tests; what is Android's — the
 * three config keys the schema requires of every module ({@code namespace}, {@code compile-sdk},
 * {@code min-sdk}) and the {@code target/lib/} artifact name — stays here, because it is fixture
 * content and not a fact about the SPI.
 */
final class AndroidIo {

    private AndroidIo() {}

    /** A packager's inputs: the schema-required config, and the artifact this run must write. */
    static FakeBuildIo packager(Path root, String artifactName) throws IOException {
        return defaults(root).artifact(artifactName);
    }

    /** A step's inputs. Steps write under {@code scratch()}, so there is no artifact to name. */
    static FakeBuildIo step(Path root) throws IOException {
        return defaults(root);
    }

    private static FakeBuildIo defaults(Path root) throws IOException {
        return new FakeBuildIo(root, "android")
                .config("namespace", "com.example.app")
                .config("compile-sdk", 36L)
                .config("min-sdk", 24L);
    }
}
