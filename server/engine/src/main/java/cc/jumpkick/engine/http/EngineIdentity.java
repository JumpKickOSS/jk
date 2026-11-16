// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.Guest;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.model.BuildIdentity;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which engine this is, as every surface names it and as a front door discovers it: the OS, whether
 * it is a WSL or container guest, the hostname and {@code JK_HOME}. The {@code id} tells apart every
 * engine one machine can run (the host, each WSL distro, each container, a second {@code JK_HOME})
 * and is stable across restarts and reboots, since it hashes only what names the engine's place.
 *
 * @param id 12 hex digits of the SHA-256 of OS, guest kind and {@link Guest#instance}, hostname and
 *     {@code JK_HOME}
 * @param os {@code windows}, {@code linux} or {@code macos}
 */
public record EngineIdentity(String id, String os, Guest guest, String hostname, String jkHome, String version) {

    /** This process's identity. */
    public static EngineIdentity current(String version) {
        return current(version, Guest.detect());
    }

    /** This process's identity as guest {@code guest}. */
    public static EngineIdentity current(String version, Guest guest) {
        String os = Os.isWindows() ? "windows" : Os.isDarwin() ? "macos" : "linux";
        Path home = JkDirs.home();
        String jkHome;
        try {
            jkHome = home.toRealPath().toString();
        } catch (IOException | RuntimeException missing) {
            jkHome = home.toAbsolutePath().normalize().toString();
        }
        String hostname = localHostname();
        return new EngineIdentity(idOf(os, guest, hostname, jkHome), os, guest, hostname, jkHome, version);
    }

    /**
     * The id of an engine with {@code JK_HOME} {@code jkHome} on {@code hostname}. A WSL distro's
     * hostname is the Windows computer's and its {@code JK_HOME} the same path in every distro, so
     * the guest's instance is what tells two distros apart. The guest's name is left out: it may come
     * from the environment, which a daemon lacks.
     */
    static String idOf(String os, Guest guest, String hostname, String jkHome) {
        return Hashing.sha256Hex(
                        os + "\n" + guest.kind().wire() + "\n" + guest.instance() + "\n" + hostname + "\n" + jkHome)
                .substring(0, 12);
    }

    /** {@code host}, or {@code guest} for a WSL distro or a container. */
    public String role() {
        return guest.guest() ? "guest" : "host";
    }

    /** One line naming this engine: {@code Windows on BocaMiniPC}, {@code WSL Ubuntu on BocaMiniPC}. */
    public String label() {
        String what =
                switch (guest.kind()) {
                    case WSL -> "WSL" + (guest.name().isEmpty() ? "" : " " + guest.name());
                    case CONTAINER -> "container" + (guest.name().isEmpty() ? "" : " (" + guest.name() + ")");
                    case NONE ->
                        switch (os) {
                            case "windows" -> "Windows";
                            case "macos" -> "macOS";
                            default -> "Linux";
                        };
                };
        return hostname.isEmpty() ? what : what + " on " + hostname;
    }

    /** The token-free identity: what a front door lists and a tab compares; {@code port} is the bound one. */
    public Map<String, Object> toMap(int port) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label());
        m.put("role", role());
        m.put("os", os);
        m.put("guest", guest.kind().wire());
        m.put("guestName", guest.name());
        m.put("hostname", hostname);
        m.put("jkHome", jkHome);
        m.put("version", version);
        m.put("commit", Objects.requireNonNullElse(BuildIdentity.builtFrom(), ""));
        m.put("port", port);
        m.put(FrontDoor.PROTOCOL_KEY, FrontDoor.PROTOCOL);
        return m;
    }

    /** The hostname without a DNS lookup where the OS names it: {@code COMPUTERNAME}, {@code /etc/hostname}. */
    private static String localHostname() {
        String name = Os.isWindows() ? ProcessEnv.get("COMPUTERNAME") : ProcessEnv.get("HOSTNAME");
        if (name != null && !name.isBlank()) return name.strip();
        try {
            Path etc = Path.of("/etc/hostname");
            if (!Os.isWindows() && Files.isRegularFile(etc)) {
                String read = Files.readString(etc).strip();
                if (!read.isEmpty()) return read;
            }
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException unknown) {
            return "";
        }
    }
}
