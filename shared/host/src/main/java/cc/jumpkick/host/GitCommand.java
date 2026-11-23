// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.ArrayList;
import java.util.List;

/**
 * The one way jk spells a git command line. Every command starts with the options jk needs on every
 * host: on Windows, {@code core.longpaths}, since a clone in jk's caches or a test sandbox nests past
 * 260 characters, which git refuses there unless the user set it. Each method returns a fresh list
 * the caller may extend.
 */
public final class GitCommand {

    /** The git on PATH. */
    private static final List<String> PATH_GIT = List.of("git");

    /**
     * The setting a test harness's own git command carries ({@code git -c HARNESS ...}), which git
     * ignores: the engine reads a test class's recording without counting the command as a program
     * the class started, since the harness runs it around every class whatever the class does.
     */
    public static final String HARNESS = "jk.harness=true";

    private GitCommand() {}

    /** {@code git args}. */
    public static List<String> of(String... args) {
        return of(List.of(args));
    }

    /** {@code git args}. */
    public static List<String> of(List<String> args) {
        return of(PATH_GIT.get(0), args);
    }

    /** {@code exe args}, for a git binary the user or the engine chose. */
    public static List<String> of(String exe, List<String> args) {
        return of(exe, args, Os.isWindows());
    }

    /** {@code exe args} as a Windows host spells it when {@code windows}. */
    static List<String> of(String exe, List<String> args, boolean windows) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        if (windows) cmd.addAll(List.of("-c", "core.longpaths=true"));
        cmd.addAll(args);
        return cmd;
    }
}
