// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

/**
 * Shell for a fake {@code java} that plays a worker: connect to the {@link WorkerCommands} channel
 * named by {@code --commands=<host>:<port>}, prove itself with {@code --token}, and leave the
 * channel open on fd 3, so the script reads its commands with {@code read -r line <&3}. Bash, for
 * {@code /dev/tcp}.
 */
public final class FakeWorkerShell {

    private FakeWorkerShell() {}

    /** The interpreter line the script must start with. */
    public static final String SHEBANG = "#!/bin/bash";

    /** Connects fd 3 to the parent's command channel; replaces {@code @CONNECT@}, before the worker says it is ready. */
    public static final String CONNECT = """
            for a in "$@"; do
              case "$a" in
                --commands=*) hp="${a#--commands=}" ;;
                --token=*) tok="${a#--token=}" ;;
              esac
            done
            exec 3<>"/dev/tcp/${hp%:*}/${hp##*:}"
            printf '%s\\n' "$tok" >&3
            """;
}
