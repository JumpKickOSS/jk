// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;

/**
 * The one subprocess start a jk process uses once a fork policy is installed. Until {@link
 * #install}, this is {@link ProcessBuilder#start()}.
 */
public final class Forks {

    /** Opens one child. The engine installs the policy that contains and tracks workers. */
    @FunctionalInterface
    public interface Opener {
        Process open(ProcessBuilder command) throws IOException;
    }

    private static volatile Opener opener = ProcessBuilder::start;

    private Forks() {}

    /** Replace {@link ProcessBuilder#start()} for every later {@link #start}. */
    public static void install(Opener installed) {
        opener = installed;
    }

    /** Start {@code command} through the installed opener. */
    public static Process start(ProcessBuilder command) throws IOException {
        return opener.open(command);
    }
}
