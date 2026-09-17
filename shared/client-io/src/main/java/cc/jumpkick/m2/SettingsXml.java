// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.MinimalXml;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads one {@code settings.xml} into a {@link MavenSettings}. {@link MinimalXml} is DOCTYPE-free
 * by construction, so an external entity in the file cannot read anything; a file that does not
 * parse, or is not there, is empty.
 */
final class SettingsXml {

    private SettingsXml() {}

    static MavenSettings read(Path file) {
        if (!Files.isRegularFile(file)) return MavenSettings.empty();
        try {
            MinimalXml.Element doc = MinimalXml.parse(Files.readString(file));
            return new MavenSettings(
                    servers(doc),
                    mirrors(doc, file),
                    proxies(doc, file),
                    profiles(doc),
                    activeProfiles(doc),
                    List.of(file));
        } catch (Exception e) {
            // Foreign/experimental file — degrade gracefully, like the other parsers.
            return MavenSettings.empty();
        }
    }

    private static Map<String, MavenSettings.Server> servers(MinimalXml.Element doc) {
        Map<String, MavenSettings.Server> byId = new LinkedHashMap<>();
        for (MinimalXml.Element server : listed(doc, "servers", "server")) {
            String id = text(server, "id");
            if (id == null) continue;
            String username = text(server, "username");
            String password = text(server, "password");
            if (username == null && password == null) continue; // no usable creds
            byId.putIfAbsent(id, new MavenSettings.Server(id, username, password));
        }
        return byId;
    }

    private static List<MavenSettings.Mirror> mirrors(MinimalXml.Element doc, Path file) {
        List<MavenSettings.Mirror> out = new ArrayList<>();
        for (MinimalXml.Element mirror : listed(doc, "mirrors", "mirror")) {
            String id = text(mirror, "id");
            String mirrorOf = text(mirror, "mirrorOf");
            URI url = uri(text(mirror, "url"));
            if (id == null || mirrorOf == null || url == null) continue;
            out.add(new MavenSettings.Mirror(id, mirrorOf, url, file));
        }
        return out;
    }

    /** Active proxies only; {@code <active>} defaults to true and {@code <protocol>} to http, as in Maven. */
    private static List<MavenSettings.Proxy> proxies(MinimalXml.Element doc, Path file) {
        List<MavenSettings.Proxy> out = new ArrayList<>();
        for (MinimalXml.Element proxy : listed(doc, "proxies", "proxy")) {
            String active = text(proxy, "active");
            if (active != null && !Boolean.parseBoolean(active)) continue;
            String host = text(proxy, "host");
            if (host == null) continue;
            String id = text(proxy, "id");
            String protocol = text(proxy, "protocol");
            int port = port(text(proxy, "port"));
            if (port < 0) continue;
            List<String> nonProxyHosts = new ArrayList<>();
            String raw = text(proxy, "nonProxyHosts");
            if (raw != null) {
                for (String entry : raw.split("\\|")) {
                    if (!entry.isBlank()) nonProxyHosts.add(entry.strip());
                }
            }
            out.add(new MavenSettings.Proxy(
                    id == null ? "default" : id,
                    protocol == null ? "http" : protocol,
                    host,
                    port,
                    text(proxy, "username"),
                    text(proxy, "password"),
                    nonProxyHosts,
                    file));
        }
        return out;
    }

    private static int port(@Nullable String text) {
        if (text == null) return 8080;
        try {
            int port = Integer.parseInt(text);
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException notAPort) {
            return -1;
        }
    }

    private static List<MavenSettings.Profile> profiles(MinimalXml.Element doc) {
        List<MavenSettings.Profile> out = new ArrayList<>();
        for (MinimalXml.Element profile : listed(doc, "profiles", "profile")) {
            String id = text(profile, "id");
            if (id == null) continue;
            boolean byDefault = profile.element("activation")
                    .flatMap(a -> a.element("activeByDefault"))
                    .map(e -> Boolean.parseBoolean(e.text().strip()))
                    .orElse(false);
            out.add(new MavenSettings.Profile(id, byDefault, repositories(profile)));
        }
        return out;
    }

    /**
     * A profile's {@code <repositories>}, one {@link RepositorySpec} each with the {@code
     * <releases>} / {@code <snapshots>} policy the entry wrote; an entry with no id, no URL, or
     * neither policy enabled is skipped.
     */
    private static List<RepositorySpec> repositories(MinimalXml.Element profile) {
        List<RepositorySpec> out = new ArrayList<>();
        for (MinimalXml.Element repo : listed(profile, "repositories", "repository")) {
            String id = text(repo, "id");
            URI url = uri(text(repo, "url"));
            if (id == null || url == null) continue;
            boolean releases = enabled(repo, "releases");
            boolean snapshots = enabled(repo, "snapshots");
            if (!releases && !snapshots) continue;
            out.add(new RepositorySpec(id, url).withPolicy(releases, snapshots));
        }
        return out;
    }

    /** {@code <releases><enabled>} / {@code <snapshots><enabled>}: on unless written false. */
    private static boolean enabled(MinimalXml.Element repo, String policy) {
        return repo.element(policy)
                .flatMap(p -> p.element("enabled"))
                .map(e -> Boolean.parseBoolean(e.text().strip()))
                .orElse(true);
    }

    private static Set<String> activeProfiles(MinimalXml.Element doc) {
        Set<String> ids = new LinkedHashSet<>();
        for (MinimalXml.Element id : listed(doc, "activeProfiles", "activeProfile")) {
            String text = id.text().strip();
            if (!text.isEmpty()) ids.add(text);
        }
        return ids;
    }

    /** The {@code <item>} children of {@code parent}'s direct {@code <list>} child, or none. */
    private static List<MinimalXml.Element> listed(MinimalXml.Element parent, String list, String item) {
        return parent.element(list).map(l -> l.elements(item)).orElse(List.of());
    }

    /** Stripped text of the first direct child element named {@code tag}; null when absent or blank. */
    private static @Nullable String text(MinimalXml.Element parent, String tag) {
        return parent.element(tag)
                .map(MinimalXml.Element::text)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .orElse(null);
    }

    private static @Nullable URI uri(@Nullable String text) {
        if (text == null) return null;
        try {
            URI uri = new URI(text);
            return uri.getScheme() == null ? null : uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
