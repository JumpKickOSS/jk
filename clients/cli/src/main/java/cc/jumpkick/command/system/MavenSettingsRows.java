// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maven's {@code settings.xml} as the engine's {@code m2-settings} inventory reports it: the files
 * it looked for, the mirrors and which built-in remotes each answers for, the active proxies and
 * the active profiles' repositories. {@code jk doctor} prints these beside the repository stores,
 * so a build that reaches Nexus instead of Central says where that came from.
 */
public final class MavenSettingsRows {

    private MavenSettingsRows() {}

    /** One settings file: where it was looked for, and whether it was there. */
    public record File(String path, boolean read) {}

    /**
     * One {@code <mirror>}. {@code captures} names the built-in remotes it stands in for (empty when
     * it matches none of them); {@code refusal} is why it is not used, or null when it is.
     */
    public record Mirror(
            String id,
            String mirrorOf,
            String url,
            List<String> captures,
            @Nullable String refusal) {}

    /** One active {@code <proxy>}: never its credential. */
    public record Proxy(String id, String protocol, String hostPort, List<String> nonProxyHosts) {}

    /** One repository of an active profile. */
    public record Repository(String id, String url) {}

    /** The rows, or the reason there are none. */
    public record Rows(
            List<File> files,
            List<Mirror> mirrors,
            List<Proxy> proxies,
            List<Repository> repositories,
            @Nullable String error) {

        public static Rows none(String error) {
            return new Rows(List.of(), List.of(), List.of(), List.of(), error);
        }
    }

    /**
     * The rows the engine answers {@code probe} with, or the reason there are none: an engine that
     * is not running when none was to be started, or a failed query.
     */
    static Rows query(DoctorCommand.WorkerProbe probe) {
        try {
            return decode(probe.workers());
        } catch (EngineClient.EngineNotRunningException e) {
            return Rows.none(DoctorCommand.ENGINE_NOT_RUNNING);
        } catch (IOException | RuntimeException e) {
            return Rows.none("engine query failed: " + e.getMessage());
        }
    }

    /** Decode the engine's rows — the grammar of {@link CacheInventoryAck#m2Settings}. */
    public static Rows decode(CacheInventoryAck ack) {
        if (ack.error() != null) return Rows.none(ack.error());
        List<File> files = new ArrayList<>();
        List<Mirror> mirrors = new ArrayList<>();
        List<Proxy> proxies = new ArrayList<>();
        List<Repository> repositories = new ArrayList<>();
        for (String line : ack.lines()) {
            String[] f = line.split("\\|", -1);
            switch (f[0]) {
                case "file" -> {
                    if (f.length >= 3) files.add(new File(f[1], "read".equals(f[2])));
                }
                case "mirror" -> {
                    if (f.length >= 6) {
                        mirrors.add(new Mirror(f[1], f[2], f[3], csv(f[4]), f[5].isBlank() ? null : f[5]));
                    }
                }
                case "proxy" -> {
                    if (f.length >= 5) proxies.add(new Proxy(f[1], f[2], f[3], csv(f[4])));
                }
                case "repository" -> {
                    if (f.length >= 3) repositories.add(new Repository(f[1], f[2]));
                }
                default -> {}
            }
        }
        return new Rows(
                List.copyOf(files), List.copyOf(mirrors), List.copyOf(proxies), List.copyOf(repositories), null);
    }

    private static List<String> csv(String field) {
        if (field.isBlank()) return List.of();
        return List.of(field.split(","));
    }

    /**
     * One line per file, mirror, proxy and profile repository: {@code settings: <path>}, then
     * {@code mirror: central, google → <url> (mirror `nexus`, mirrorOf `*`)} — or a warning when
     * the mirror is refused — {@code proxy: https via <host:port> (…)} and {@code repository:
     * <id> → <url> (settings.xml profile)}.
     */
    public static List<String> render(Rows rows, Theme t) {
        List<String> out = new ArrayList<>();
        if (rows.error() != null) {
            out.add(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("settings.xml", t.cyan()) + " — "
                    + rows.error());
            return out;
        }
        for (File file : rows.files()) {
            out.add(Theme.colorize("ok:      ", t.completedStep()) + " " + Theme.colorize("settings.xml", t.cyan())
                    + " — " + Theme.colorize(file.path(), t.path()) + (file.read() ? "" : " (absent)"));
        }
        for (Mirror m : rows.mirrors()) {
            String entry = "mirror `" + m.id() + "`, mirrorOf `" + m.mirrorOf() + "`";
            if (m.refusal() != null) {
                out.add(Theme.colorize("warn:    ", t.warning()) + " " + Theme.colorize(entry, t.cyan())
                        + " — not used: " + m.refusal());
                continue;
            }
            String captures = m.captures().isEmpty() ? "no built-in remote" : String.join(", ", m.captures());
            out.add(Theme.colorize("mirror:  ", t.completedStep()) + " " + Theme.colorize(captures, t.cyan()) + " "
                    + Theme.colorize("→", t.darkGray()) + " " + m.url() + " (" + entry + ")");
        }
        for (Proxy p : rows.proxies()) {
            String bypass = p.nonProxyHosts().isEmpty() ? "" : ", direct: " + String.join(", ", p.nonProxyHosts());
            out.add(Theme.colorize("proxy:   ", t.completedStep()) + " " + Theme.colorize(p.protocol(), t.cyan())
                    + " via " + p.hostPort() + " (<proxy> `" + p.id() + "`" + bypass + ")");
        }
        for (Repository r : rows.repositories()) {
            out.add(Theme.colorize("repo:    ", t.completedStep()) + " " + Theme.colorize(r.id(), t.cyan()) + " "
                    + Theme.colorize("→", t.darkGray()) + " " + r.url() + " (settings.xml profile)");
        }
        return out;
    }

    /** The {@code settings} member of a JSON report: the four lists, or an error string. */
    public static String json(Rows rows) {
        if (rows.error() != null) {
            return JsonFields.object().string("error", rows.error()).finish();
        }
        StringBuilder files = new StringBuilder("[");
        for (int i = 0; i < rows.files().size(); i++) {
            File f = rows.files().get(i);
            if (i > 0) files.append(',');
            files.append(JsonFields.object()
                    .string("path", f.path())
                    .string("state", f.read() ? "read" : "absent")
                    .finish());
        }
        StringBuilder mirrors = new StringBuilder("[");
        for (int i = 0; i < rows.mirrors().size(); i++) {
            Mirror m = rows.mirrors().get(i);
            if (i > 0) mirrors.append(',');
            mirrors.append(JsonFields.object()
                    .string("id", m.id())
                    .string("mirrorOf", m.mirrorOf())
                    .string("url", m.url())
                    .array("captures", m.captures())
                    .string("refusal", m.refusal())
                    .finish());
        }
        StringBuilder proxies = new StringBuilder("[");
        for (int i = 0; i < rows.proxies().size(); i++) {
            Proxy p = rows.proxies().get(i);
            if (i > 0) proxies.append(',');
            proxies.append(JsonFields.object()
                    .string("id", p.id())
                    .string("protocol", p.protocol())
                    .string("via", p.hostPort())
                    .array("nonProxyHosts", p.nonProxyHosts())
                    .finish());
        }
        StringBuilder repositories = new StringBuilder("[");
        for (int i = 0; i < rows.repositories().size(); i++) {
            Repository r = rows.repositories().get(i);
            if (i > 0) repositories.append(',');
            repositories.append(JsonFields.object()
                    .string("id", r.id())
                    .string("url", r.url())
                    .finish());
        }
        return JsonFields.object()
                .token("files", files.append(']').toString())
                .token("mirrors", mirrors.append(']').toString())
                .token("proxies", proxies.append(']').toString())
                .token("repositories", repositories.append(']').toString())
                .finish();
    }
}
