// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;

/**
 * {@code [env]}: what a module's workers — the compiler, each test JVM, a plugin step — get from
 * the environment beyond the allow-list jk applies by default. {@code inherit = true} hands them
 * the engine's whole environment; {@code vars} names or sets variables in the {@code [test] env}
 * shape. Not an action-key input by itself: a value that must retest a suite belongs in {@code
 * [test] env}.
 */
public record EnvConfig(boolean inherit, List<EnvDecl> vars) {

    public static final EnvConfig EMPTY = new EnvConfig(false, List.of());

    public EnvConfig {
        vars = vars == null ? List.of() : List.copyOf(vars);
    }

    public boolean isEmpty() {
        return !inherit && vars.isEmpty();
    }
}
