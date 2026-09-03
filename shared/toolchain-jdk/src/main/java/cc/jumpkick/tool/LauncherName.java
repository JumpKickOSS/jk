// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.jsonl.Jsonl;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Portable single-segment name for a launcher and its installed-tool state directory. */
public final class LauncherName {

    private static final Pattern PORTABLE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern WINDOWS_NUMBERED_DEVICE = Pattern.compile("(?:COM|LPT)[1-9]");
    private static final Set<String> WINDOWS_DEVICES = Set.of("CON", "PRN", "AUX", "NUL");

    /**
     * Stems of the files jk itself keeps in {@code <home>/bin}: the {@code jk} and {@code jkx}
     * clients in every platform spelling ({@code .exe}, {@code .bat}, {@code .cmd}) and their parked
     * {@code .old} copies, and the wrapper's {@code VERSION} floor file. A tool launcher under one
     * of these names would truncate or delete the product.
     */
    private static final Set<String> JK_OWN_STEMS = Set.of("JK", "JKX", "VERSION");

    private LauncherName() {}

    /** Return why {@code name} is invalid, or empty when it is a portable leaf name. */
    public static Optional<String> validationError(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of("launcher name must not be blank");
        }
        if (!PORTABLE.matcher(name).matches()) {
            return Optional.of("launcher name "
                    + Jsonl.quote(name)
                    + " must start with a letter or digit and contain only letters, digits, '.', '_', or '-'");
        }
        String stem = name.substring(0, name.indexOf('.') < 0 ? name.length() : name.indexOf('.'))
                .toUpperCase(Locale.ROOT);
        if (WINDOWS_DEVICES.contains(stem)
                || WINDOWS_NUMBERED_DEVICE.matcher(stem).matches()) {
            return Optional.of("launcher name " + Jsonl.quote(name) + " is reserved on Windows");
        }
        if (JK_OWN_STEMS.contains(stem)) {
            return Optional.of("launcher name " + Jsonl.quote(name) + " is jk's own file under bin/");
        }
        return Optional.empty();
    }

    /** Return {@code name}, or throw before a persistent launcher path is constructed. */
    public static String requireValid(String name) {
        validationError(name).ifPresent(message -> {
            throw new IllegalArgumentException(message);
        });
        return name;
    }

    /** Resolve a validated direct child without allowing normalization to escape {@code root}. */
    public static Path resolveChild(Path root, String name) {
        requireValid(name);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path child = normalizedRoot.resolve(name).normalize();
        if (!normalizedRoot.equals(child.getParent())) {
            throw new IllegalArgumentException("launcher name does not resolve to a direct child");
        }
        return root.resolve(name).normalize();
    }
}
