// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import java.util.Objects;

/**
 * A package manager at a version: {@code package.json}'s {@code packageManager} ({@code
 * pnpm@10.18.1}, a {@code +sha512…} suffix ignored) or a version jk chose. {@code latest} means the
 * registry's newest. Yarn 1 is refused: jk runs Yarn Berry only.
 */
public record PackageManagerSpec(PackageManager manager, String version) {

    public static final String LATEST = "latest";

    public PackageManagerSpec {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(version, "version");
        if (manager == PackageManager.YARN && NodeRelease.majorOf(version) == 1) {
            throw new IllegalArgumentException("Yarn " + version + " is Yarn 1, which jk does not run — run"
                    + " `yarn set version stable && yarn install`, then commit package.json and yarn.lock");
        }
    }

    /** Parse a {@code packageManager} value. */
    public static PackageManagerSpec parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        int at = s.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("packageManager \"" + raw + "\" is not <name>@<version>");
        PackageManager pm = PackageManager.byId(s.substring(0, at))
                .orElseThrow(() -> new IllegalArgumentException(
                        "packageManager \"" + raw + "\" names a manager jk does not run (npm, pnpm, yarn, bun)"));
        String version = s.substring(at + 1);
        int plus = version.indexOf('+');
        if (plus >= 0) version = version.substring(0, plus);
        if (version.isBlank()) throw new IllegalArgumentException("packageManager \"" + raw + "\" names no version");
        return new PackageManagerSpec(pm, version);
    }

    @Override
    public String toString() {
        return manager.id() + "@" + version;
    }
}
