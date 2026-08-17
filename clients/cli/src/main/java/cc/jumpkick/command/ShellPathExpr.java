// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;

/**
 * Renders filesystem paths as shell expressions for rc / activate snippets. Prefer {@code $HOME/…}
 * when the path is under the user home so snippets stay username-free and copy-pastable; otherwise
 * emit an absolute path. Forms meant for double-quoted contexts expand {@code $HOME} and escape
 * other special characters.
 */
final class ShellPathExpr {

    private ShellPathExpr() {}

    /**
     * Expression safe inside double quotes for bash / zsh / fish: {@code $HOME/.local/bin} or a
     * double-quote-escaped absolute path.
     */
    static String posix(Path path, Path home) {
        String homeRelative = homeRelative(path, home);
        if (homeRelative != null) {
            return homeRelative.isEmpty() ? "$HOME" : "$HOME/" + homeRelative;
        }
        return escapeDoubleQuoted(normalizeAbs(path));
    }

    /**
     * Command word: {@code "$HOME/.local/bin/jk"} or {@code "/opt/jk/bin/jk"} (double-quoted so
     * spaces and {@code $HOME} work).
     */
    static String posixCommand(Path path, Path home) {
        return "\"" + posix(path, home) + "\"";
    }

    /**
     * PowerShell expression safe inside double quotes: {@code $HOME/.local/bin} or an escaped
     * absolute path.
     */
    static String pwsh(Path path, Path home) {
        String homeRelative = homeRelative(path, home);
        if (homeRelative != null) {
            return homeRelative.isEmpty() ? "$HOME" : "$HOME/" + homeRelative;
        }
        return escapePwshDoubleQuoted(normalizeAbs(path));
    }

    /** PowerShell {@code & …} target in double quotes. */
    static String pwshCommand(Path path, Path home) {
        return "\"" + pwsh(path, home) + "\"";
    }

    /**
     * Forward-slash path relative to {@code home}, or {@code null} when {@code path} is not under
     * {@code home}. Empty string means path equals home.
     */
    static String homeRelative(Path path, Path home) {
        if (path == null || home == null) return null;
        Path abs = path.toAbsolutePath().normalize();
        Path homeAbs = home.toAbsolutePath().normalize();
        if (!abs.startsWith(homeAbs)) return null;
        return homeAbs.relativize(abs).toString().replace('\\', '/');
    }

    private static String normalizeAbs(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** Escape for inclusion inside POSIX double quotes (not including the surrounding quotes). */
    static String escapeDoubleQuoted(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\', '"', '$', '`' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String escapePwshDoubleQuoted(String value) {
        // PowerShell double-quote: backtick-escape ", $, `, and `
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '`', '"', '$' -> sb.append('`').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
