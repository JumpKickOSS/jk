// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.BuildTool;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The package managers jk runs: npm, bundled with Node, and pnpm, Yarn Berry and bun, each
 * provisioned from its npm registry package.
 */
public enum PackageManager {
    NPM("npm", null),
    PNPM("pnpm", BuildTool.PNPM),
    YARN("yarn", BuildTool.YARN),
    BUN("bun", BuildTool.BUN);

    private final String id;
    private final @Nullable BuildTool tool;

    PackageManager(String id, @Nullable BuildTool tool) {
        this.id = id;
        this.tool = tool;
    }

    /** The name {@code packageManager} and the {@code [node]} table spell it with. */
    public String id() {
        return id;
    }

    /** The provisioned distribution, empty for npm, which comes with Node. */
    public Optional<BuildTool> tool() {
        return Optional.ofNullable(tool);
    }

    public static Optional<PackageManager> byId(@Nullable String id) {
        if (id == null) return Optional.empty();
        String s = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.id.equals(s)).findFirst();
    }

    /** The npm package that publishes this manager's versions. */
    String versionsPackage(NodePlatform platform) {
        return this == PNPM ? "pnpm" : registryPackage(platform, PackageManagerSpec.LATEST);
    }

    /**
     * The npm package {@code version} installs from on {@code platform}: pnpm from 12 on, like bun,
     * is a native binary in a per-platform package.
     */
    String registryPackage(NodePlatform platform, String version) {
        return switch (this) {
            case NPM -> "npm";
            case PNPM -> {
                if (NodeRelease.majorOf(version) < 12) yield "pnpm";
                String os =
                        switch (platform.os()) {
                            case "win" -> "win32";
                            case "darwin" -> "darwin";
                            default -> "linux";
                        };
                yield "@pnpm/exe." + os + "-" + platform.arch() + (platform.musl() ? "-musl" : "");
            }
            case YARN -> "@yarnpkg/cli-dist";
            case BUN -> {
                String os =
                        switch (platform.os()) {
                            case "win" -> "windows";
                            case "darwin" -> "darwin";
                            default -> "linux";
                        };
                String arch = platform.arch().equals("arm64") ? "aarch64" : "x64";
                yield "@oven/bun-" + os + "-" + arch + (platform.musl() ? "-musl" : "");
            }
        };
    }
}
