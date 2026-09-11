// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Best-effort "open this URL in a browser". Prefer {@code $BROWSER} when set; else platform default
 * ({@code open} on macOS, {@code rundll32 url.dll,FileProtocolHandler} on Windows, {@code
 * xdg-open} on Linux). Failures are silent — callers always print the URL so headless sessions
 * still work.
 */
public final class OpenBrowser {

    private OpenBrowser() {}

    /** Open {@code url}; {@code true} if a process was started (not that a window appeared). */
    public static boolean open(String url) {
        return open(url, System::getenv);
    }

    /** Testable variant: {@code env} supplies {@code BROWSER} (and any future vars). */
    static boolean open(String url, Function<String, @Nullable String> env) {
        if (url == null || url.isBlank()) return false;
        List<String> cmd = command(url, env, Os.name());
        if (cmd.isEmpty()) return false;
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Argv used to open {@code url}. Empty when nothing sensible can be built. Package-visible for
     * unit tests.
     */
    static List<String> command(String url, Function<String, @Nullable String> env, String osName) {
        if (url == null || url.isBlank()) return List.of();
        String browser = env != null ? env.apply("BROWSER") : null;
        if (browser != null && !browser.isBlank()) {
            // $BROWSER may carry arguments ("firefox --new-tab", "flatpak run org.mozilla.firefox")
            // — word-split like other tools honoring the convention; append the URL last.
            List<String> cmd = new ArrayList<>(List.of(browser.trim().split("\\s+")));
            cmd.add(url);
            return cmd;
        }
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return List.of("open", url);
        if (os.contains("win")) {
            // NOT `cmd /c start`: ProcessBuilder only quotes args containing whitespace, so a URL
            // with `&` (network-supplied for auth login) would reach cmd.exe unquoted and execute
            // the remainder as a command. rundll32's FileProtocolHandler does no shell parsing.
            return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        }
        return List.of("xdg-open", url);
    }
}
