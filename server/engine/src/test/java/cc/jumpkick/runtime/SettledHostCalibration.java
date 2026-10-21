// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Points the host state dir at a private directory holding a settled {@code host-metrics.toml}, so
 * an ETA's {@link Calibration#ensure} returns at once instead of running the multi-second hardware
 * probe. Register with {@code @ExtendWith(SettledHostCalibration.class)}.
 */
public final class SettledHostCalibration implements BeforeEachCallback, AfterEachCallback {

    private static final String STATE_DIR = "jk.env.JK_STATE_DIR";

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(SettledHostCalibration.class);

    private record Saved(Path stateDir, @Nullable String prevStateDir) {}

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        Path stateDir = Files.createTempDirectory("jk-host-state");
        context.getStore(NS).put(Saved.class, new Saved(stateDir, System.getProperty(STATE_DIR)));
        System.setProperty(STATE_DIR, stateDir.toString());
        Path file = JkDirs.builds().resolve("host-metrics.toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                [calibration]
                schema        = 1
                ms-per-weight = 120.0
                measured      = true
                jk-version    = "%s"
                updated       = %d
                """.formatted(JkVersion.VERSION, System.currentTimeMillis()));
        Calibration.invalidateMemo();
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        Saved saved = context.getStore(NS).remove(Saved.class, Saved.class);
        if (saved == null) return;
        if (saved.prevStateDir() == null) System.clearProperty(STATE_DIR);
        else System.setProperty(STATE_DIR, saved.prevStateDir());
        Calibration.invalidateMemo();
        try (Stream<Path> walk = Files.walk(saved.stateDir())) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
