// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The launchers a package manager is called by from scripts ({@code yarn}, {@code pnpx}, {@code
 * bunx}), written into {@code <home>/}{@value #DIR}{@code /} of its install. A JS entry point runs
 * under the {@code node} first on {@code PATH}, so one install serves every Node version; a native
 * one (bun, pnpm from 12) runs directly. Both a POSIX script and a {@code .cmd} are written.
 */
public final class PackageManagerShims {

    public static final String DIR = "jk-bin";

    private PackageManagerShims() {}

    /** Write the shims of {@code tool} installed at {@code home}; no-op for any other tool. */
    public static void write(BuildTool tool, Path home) throws IOException {
        Map<String, String> commands = commands(tool, home);
        if (commands.isEmpty()) return;
        Path dir = Files.createDirectories(home.resolve(DIR));
        for (Map.Entry<String, String> c : commands.entrySet()) {
            Path sh = dir.resolve(c.getKey());
            Files.writeString(
                    sh,
                    "#!/bin/sh\nexec "
                            + c.getValue()
                                    .replace("%HERE%", "\"$(dirname \"$0\")/..\"")
                                    .replace("%ARGS%", "\"$@\"") + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(sh, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException windows) {
                // the .cmd below is what Windows runs
            }
            Files.writeString(
                    dir.resolve(c.getKey() + ".cmd"),
                    "@"
                            + c.getValue()
                                    .replace("%HERE%", "\"%~dp0..\"")
                                    .replace("/", "\\")
                                    .replace("%ARGS%", "%*") + "\r\n",
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * The file that runs {@code tool} installed at {@code home}: its {@code package.json} {@code
     * bin} entry, else the native binary at the package root (pnpm from 12) or under {@code bin/}.
     */
    public static Path entry(BuildTool tool, Path home) throws IOException {
        String rel = bins(home).get(tool.slug());
        if (rel != null) return home.resolve(rel);
        String exe = Os.isWindows() ? tool.slug() + ".exe" : tool.slug();
        Path atRoot = home.resolve(exe);
        return Files.exists(atRoot) ? atRoot : home.resolve("bin").resolve(exe);
    }

    /** A JS entry point, which {@code node} runs; anything else is executed directly. */
    public static boolean isScript(Path entry) {
        String n = String.valueOf(entry.getFileName());
        return n.endsWith(".js") || n.endsWith(".cjs") || n.endsWith(".mjs");
    }

    /** Shim name → command line, {@code %HERE%} for the install root and {@code %ARGS%} for the arguments. */
    static Map<String, String> commands(BuildTool tool, Path home) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        switch (tool) {
            case PNPM, YARN -> {
                Map<String, String> bins = bins(home);
                for (Map.Entry<String, String> bin : bins.entrySet()) {
                    String run = isScript(Path.of(bin.getValue())) ? "node %HERE%/" : "%HERE%/";
                    out.put(bin.getKey(), run + bin.getValue() + " %ARGS%");
                }
                if (bins.isEmpty() && tool == BuildTool.PNPM) {
                    out.put("pnpm", "%HERE%/pnpm %ARGS%");
                    out.put("pn", "%HERE%/pnpm %ARGS%");
                    out.put("pnpx", "%HERE%/pnpm dlx %ARGS%");
                    out.put("pnx", "%HERE%/pnpm dlx %ARGS%");
                }
            }
            case BUN -> {
                out.put("bun", "%HERE%/bin/" + BuildTool.BUN.binaryName() + " %ARGS%");
                out.put("bunx", "%HERE%/bin/" + BuildTool.BUN.binaryName() + " x %ARGS%");
            }
            default -> {}
        }
        return out;
    }

    /** {@code package.json}'s {@code bin}: name → entry point relative to the package root. */
    private static Map<String, String> bins(Path home) throws IOException {
        Path pkg = home.resolve("package.json");
        if (!Files.isRegularFile(pkg)) return Map.of();
        Object bin = MiniJson.get(MiniJson.parse(Files.readString(pkg, StandardCharsets.UTF_8)), "bin");
        Map<String, String> out = new LinkedHashMap<>();
        if (bin instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String name && e.getValue() instanceof String rel) {
                    out.put(name, rel.startsWith("./") ? rel.substring(2) : rel);
                }
            }
        }
        return out;
    }
}
