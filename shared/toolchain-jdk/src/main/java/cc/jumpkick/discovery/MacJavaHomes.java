// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.PathUtil;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The JDK homes macOS's {@code /usr/libexec/java_home -V} lists, including ones outside the system root. */
final class MacJavaHomes {

    private static final Path JAVA_HOME_TOOL = Path.of("/usr/libexec/java_home");

    private static final long TIMEOUT_SECONDS = 5;

    private MacJavaHomes() {}

    /** Run {@code java_home -V}; empty when the tool is absent, fails, or times out. */
    static List<Path> list() {
        if (!PathUtil.isRunnable(JAVA_HOME_TOOL)) return List.of();
        String out = ToolOutput.run(List.of(JAVA_HOME_TOOL.toString(), "-V"), Duration.ofSeconds(TIMEOUT_SECONDS));
        return out == null ? List.of() : parse(out);
    }

    /**
     * The homes in {@code java_home -V} output. Each JVM line ends with its home after the last
     * quoted field, e.g. {@code 21.0.1 (arm64) "Eclipse Adoptium" - "OpenJDK 21.0.1" /Library/…/Home}.
     */
    static List<Path> parse(String output) {
        List<Path> homes = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int quote = line.lastIndexOf('"');
            String rest = (quote < 0 ? line : line.substring(quote + 1)).strip();
            if (!rest.startsWith("/")) continue;
            Path home = Path.of(rest);
            if (!homes.contains(home)) homes.add(home);
        }
        return homes;
    }
}
