// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

/**
 * Shell for a fake {@code java} that plays a pull worker: connect to the command channel named by
 * {@code --commands=<host>:<port>}, prove itself with {@code --token}, and leave the channel open
 * on fd 3, so the script reads its {@code RUN}/{@code DONE} lines with {@code read -r line <&3}.
 * Bash, for {@code /dev/tcp}.
 */
final class FakePullWorker {

    private FakePullWorker() {}

    /** The interpreter line the script must start with. */
    static final String SHEBANG = "#!/bin/bash";

    /** Connects fd 3 to the parent's command channel; replaces {@code @CONNECT@}, before the first {@code ready}. */
    static final String CONNECT = """
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
