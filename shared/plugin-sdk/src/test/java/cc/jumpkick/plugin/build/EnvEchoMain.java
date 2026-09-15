// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import org.jspecify.annotations.Nullable;

/**
 * A child process for {@link ToolRunForkTest}: echoes its args, then one environment variable, then
 * optionally whether {@code PATH} survived. Exits 7 when handed {@code --fail}, so a drain that
 * loses the child's status is visible. Lines are {@code \n}-terminated so a file redirect is the
 * same bytes on every OS ({@code println} would write {@code \r\n} on Windows).
 */
public final class EnvEchoMain {

    /** The one spelling of the probe variable; {@link ToolRunForkTest} reads it from here. */
    public static final String VAR = "JK_TOOLRUN_PROBE";

    private EnvEchoMain() {}

    public static void main(String[] args) {
        boolean fail = false;
        boolean path = false;
        for (String arg : args) {
            if ("--fail".equals(arg)) {
                fail = true;
            } else if ("--path".equals(arg)) {
                path = true;
            } else {
                line("arg=" + arg);
            }
        }
        @Nullable String probe = System.getenv(VAR);
        line("env=" + (probe == null ? "<unset>" : probe));
        if (path) {
            @Nullable String p = System.getenv("PATH");
            line("path=" + (p == null || p.isEmpty() ? "<unset>" : "present"));
        }
        if (fail) System.exit(7);
    }

    /** One LF-terminated line; not {@code println}, which follows {@link System#lineSeparator()}. */
    private static void line(String text) {
        System.out.print(text);
        System.out.print('\n');
    }
}
