// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.desktop;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Windows PowerShell scripts built from Java values. Every value reaches the script base64-encoded
 * through {@link #literal}, so no text can end a literal and run as script.
 */
public final class PowerShell {

    /** Stops at the first error and defines {@code D}, the base64 decoder {@link #literal} calls. */
    public static final String PRELUDE = "$ErrorActionPreference = 'Stop'\n"
            + "function D($s) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s)) }\n";

    private PowerShell() {}

    /** {@code value} as a parenthesized script expression; the script must start with {@link #PRELUDE}. */
    public static String literal(String value) {
        return "(D '" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "')";
    }

    /** The argv that runs {@code script} in Windows PowerShell, with no profile and no prompts. */
    public static List<String> command(String script) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
    }
}
