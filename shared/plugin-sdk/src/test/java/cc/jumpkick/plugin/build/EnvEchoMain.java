// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * A child process for {@link ToolRunForkTest}: echoes its args, then one environment variable, then
 * optionally whether {@code PATH} survived. Exits 7 when handed {@code --fail}, so a drain that
 * loses the child's status is visible.
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
                System.out.println("arg=" + arg);
            }
        }
        String probe = System.getenv(VAR);
        System.out.println("env=" + (probe == null ? "<unset>" : probe));
        if (path) {
            String p = System.getenv("PATH");
            System.out.println("path=" + (p == null || p.isEmpty() ? "<unset>" : "present"));
        }
        if (fail) System.exit(7);
    }
}
