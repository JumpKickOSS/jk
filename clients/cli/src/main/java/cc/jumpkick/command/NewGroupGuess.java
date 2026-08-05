// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;

/**
 * CLI façade for {@link cc.jumpkick.scaffold.NewGroupGuess} (lives in {@code :core} so the engine
 * dashboard can share the same group-id guess as {@code jk new}).
 */
public final class NewGroupGuess {

    private NewGroupGuess() {}

    public static String guess() {
        return cc.jumpkick.scaffold.NewGroupGuess.guess();
    }

    public static String guess(Path cwd, Path home) {
        return cc.jumpkick.scaffold.NewGroupGuess.guess(cwd, home);
    }

    public static String groupForEmail(String email) {
        return cc.jumpkick.scaffold.NewGroupGuess.groupForEmail(email);
    }
}
