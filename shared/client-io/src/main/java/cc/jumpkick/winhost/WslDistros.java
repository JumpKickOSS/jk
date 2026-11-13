// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.winhost.Registry.Hive;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The user's WSL distros, found without starting one. The list, the default and each distro's WSL
 * version come from the registry, which wakes no VM; {@code wsl.exe -l --running} says which run, and
 * only those are asked which JumpKick they have, under a time limit. Opening {@code \\wsl.localhost}
 * or running {@code wsl.exe -d} on a stopped distro would boot it, so nothing here does. The distros
 * Docker Desktop, Rancher Desktop and Podman keep for themselves are left out.
 */
public final class WslDistros {

    /** Where WSL registers each distro, one subkey per distro. */
    static final String LXSS = "Software\\Microsoft\\Windows\\CurrentVersion\\Lxss";

    /** How long a running distro may take to say which JumpKick it has. */
    static final Duration JK_PROBE = Duration.ofSeconds(5);

    /** The JumpKick version probe, run inside a distro; prints nothing when there is none. */
    static final String JK_VERSION = "\"$HOME/.jk/bin/jk\" --version 2>/dev/null | head -n 1";

    /** Runs a Windows command; the seam tests replace. */
    @FunctionalInterface
    public interface Runner {
        /** {@code argv}'s raw output when it exits 0 within {@code limit}; null otherwise. */
        byte @Nullable [] run(List<String> argv, Duration limit);
    }

    /**
     * One distro: its name, WSL version (1 or 2), whether it is the default and is running, and the
     * JumpKick version it reports ({@code jk 0.51.0}), null when stopped, without JumpKick, or silent.
     */
    public record Distro(
            String name,
            int version,
            boolean isDefault,
            boolean running,
            @Nullable String jumpKick) {}

    private WslDistros() {}

    /** The distros {@code registry} lists, with what {@code runner} says about the running ones. */
    public static List<Distro> list(Registry registry, Runner runner) {
        String defaultId = registry.string(Hive.CURRENT_USER, LXSS, "DefaultDistribution");
        List<String> ids = registry.subkeys(Hive.CURRENT_USER, LXSS);
        if (ids.isEmpty()) return List.of();
        Set<String> running = running(runner.run(List.of("wsl.exe", "--list", "--running", "--quiet"), JK_PROBE));
        List<Distro> out = new ArrayList<>();
        for (String id : ids) {
            String key = LXSS + "\\" + id;
            String name = registry.string(Hive.CURRENT_USER, key, "DistributionName");
            if (name == null || name.isBlank() || tooling(name)) continue;
            Integer version = registry.dword(Hive.CURRENT_USER, key, "Version");
            boolean up = running.contains(name.toLowerCase(Locale.ROOT));
            String jk = up ? jumpKick(runner, name) : null;
            out.add(new Distro(name, version == null ? 1 : version, id.equalsIgnoreCase(defaultId), up, jk));
        }
        return List.copyOf(out);
    }

    /** True for a distro a container tool keeps for itself; offering JumpKick there would look broken. */
    static boolean tooling(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("docker-desktop") || n.startsWith("rancher-desktop") || n.startsWith("podman-");
    }

    /** The running distros' names, lower-cased, from {@code wsl.exe --list --running --quiet}. */
    static Set<String> running(byte @Nullable [] output) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : decode(output).split("\\R")) {
            String name = line.strip();
            if (!name.isEmpty()) out.add(name.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * {@code wsl.exe}'s output as text: UTF-16LE, as it writes to a pipe, unless {@code WSL_UTF8} made
     * it UTF-8. A byte-order mark or a NUL in the second byte tells them apart; the mark is dropped.
     */
    static String decode(byte @Nullable [] output) {
        if (output == null || output.length == 0) return "";
        boolean bom = output.length > 1 && output[0] == (byte) 0xFF && output[1] == (byte) 0xFE;
        boolean utf16 = bom || (output.length > 1 && output[1] == 0);
        String text = new String(output, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static @Nullable String jumpKick(Runner runner, String distro) {
        byte[] out = runner.run(List.of("wsl.exe", "-d", distro, "--", "sh", "-c", JK_VERSION), JK_PROBE);
        String version = new String(out == null ? new byte[0] : out, StandardCharsets.UTF_8).strip();
        return version.isEmpty() ? null : version;
    }
}
