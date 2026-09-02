// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;

/** Identifies the platform-default engine location superseded by the single JumpKick home. */
final class RetiredEngineLayouts {

    record Layout(Path engineHome, Path stateDir) {}

    private RetiredEngineLayouts() {}

    static Layout currentPlatformDefault() {
        return platformDefault(JkDirs::env, Path.of(System.getProperty("user.home")), Os.isWindows());
    }

    static Layout platformDefault(Function<String, String> env, Path userHome, boolean windows) {
        if (windows) {
            Path local = pathOrDefault(
                    env.apply("LOCALAPPDATA"), userHome.resolve("AppData").resolve("Local"));
            Path product = local.resolve("jk");
            return new Layout(product.resolve("data"), product.resolve("state"));
        }
        Path data = pathOrDefault(
                env.apply("XDG_DATA_HOME"), userHome.resolve(".local").resolve("share"));
        Path state = pathOrDefault(
                env.apply("XDG_STATE_HOME"), userHome.resolve(".local").resolve("state"));
        return new Layout(data.resolve("jk"), state.resolve("jk"));
    }

    private static Path pathOrDefault(String value, Path fallback) {
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}
