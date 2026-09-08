// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

/**
 * Run jk again from inside a verb, with a different argv. The shell hands its entry point to the verbs
 * that need one, so a verb never names the shell.
 */
@FunctionalInterface
public interface Reentry {
    int execute(String... args);
}
