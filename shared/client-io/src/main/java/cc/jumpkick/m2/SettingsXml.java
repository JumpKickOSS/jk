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
 * Reads one {@code settings.xml} into a {@link MavenSettings}, in two steps: {@link #parse} reads
 * the file once, and {@link #read} turns the parse into values for one request, every element's
 * text passed through {@link SettingsValues} so {@code ${env.NAME}} is the request's. {@link
 * MinimalXml} is DOCTYPE-free by construction, so an external entity in the file cannot read
 * anything; a file that does not parse, or is not there, is empty.
 */
final class SettingsXml {

    /** One parsed file: what {@link #read} expands, kept while the file is unchanged. */
    record Document(Path file, MinimalXml.Element root) {}

    private final Path file;
    private final SettingsValues values;

    private SettingsXml(Path file, SettingsValues values) {
        this.file = file;
        this.values = values;
    }

    /** The parse of {@code file}; null when it is not there or does not parse. */
    static @Nullable Document parse(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return new Document(file, MinimalXml.parse(Files.readString(file)));
        } catch (Exception e) {
            // Foreign/experimental file — degrade gracefully, like the other parsers.
            return null;
        }
    }

    /** {@code doc}'s entries, every value expanded through {@code values}. */
    static MavenSettings read(@Nullable Document doc, SettingsValues values) {
        if (doc == null) return MavenSettings.empty();
        SettingsXml reader = new SettingsXml(doc.file(), values);
        MinimalXml.Element root = doc.root();
        return new MavenSettings(
                reader.servers(root),
                reader.mirrors(root),
                reader.proxies(root),
                reader.profiles(root),
                reader.activeProfiles(root),
                List.of(doc.file()),
                values.notices());
    }

    /**
     * The servers with usable credentials. A {@code <password>} that is encrypted and does not
     * decrypt drops its server, after a warning, so the repository is asked anonymously rather
     * than with the ciphertext; an id is decided by its first entry either way.
     */
    private Map<String, MavenSettings.Server> servers(MinimalXml.Element doc) {
        Map<String, MavenSettings.Server> byId = new LinkedHashMap<>();
        Set<String> decided = new LinkedHashSet<>();
        for (MinimalXml.Element server : listed(doc, "servers", "server")) {
            String id = text(server, "id", "<server>");
            if (id == null) continue;
            String where = "<server> `" + id + "`";
            String username = text(server, "username", where);
            String password = text(server, "password", where);
            String passphrase = text(server, "passphrase", where);
            if (username == null && password == null) continue; // no usable creds
            if (!decided.add(id)) continue;
            try {
                password = decrypted(password);
            } catch (MavenPasswords.Undecryptable e) {
                undecryptable(where, "<password>", e, "the repository is accessed anonymously");
                continue;
            }
            try {
                passphrase = decrypted(passphrase);
            } catch (MavenPasswords.Undecryptable e) {
                undecryptable(where, "<passphrase>", e, "it is not used");
                passphrase = null;
            }
            byId.put(id, new MavenSettings.Server(id, username, password, passphrase));
        }
        return byId;
    }

    /**
     * {@code value} decrypted when it is encrypted. A value still carrying a {@code ${...}} has an
     * expression that did not expand; it was already reported, and is sent as written rather than
     * mistaken for ciphertext.
     */
    private @Nullable String decrypted(@Nullable String value) throws MavenPasswords.Undecryptable {
        return value == null || value.contains("${") ? value : values.decrypt(value);
    }

    /** Once per run per entry: an encrypted value that does not decrypt, why, and what jk does instead. */
    private void undecryptable(String where, String element, MavenPasswords.Undecryptable e, String instead) {
        values.report(new SettingsValues.Notice(
                "m2-settings-undecryptable:" + file + " " + where + " " + element,
                "jk: warning: " + where + " in " + file + " has an encrypted " + element
                        + " jk cannot decrypt: " + e.getMessage() + "; " + instead
                        + ". jk reads Maven's encrypted passwords for compatibility only — `jk repo login`,"
                        + " ${file:…} or ${cmd:…} keep a credential out of plain text"));
    }

    private List<MavenSettings.Mirror> mirrors(MinimalXml.Element doc) {
        List<MavenSettings.Mirror> out = new ArrayList<>();
        for (MinimalXml.Element mirror : listed(doc, "mirrors", "mirror")) {
            String id = text(mirror, "id", "<mirror>");
            String where = "<mirror> `" + id + "`";
            String mirrorOf = text(mirror, "mirrorOf", where);
            URI url = uri(text(mirror, "url", where));
            if (id == null || mirrorOf == null || url == null) continue;
            out.add(new MavenSettings.Mirror(id, mirrorOf, url, file));
        }
        return out;
    }

    /** Active proxies only; {@code <active>} defaults to true and {@code <protocol>} to http, as in Maven. */
    private List<MavenSettings.Proxy> proxies(MinimalXml.Element doc) {
        List<MavenSettings.Proxy> out = new ArrayList<>();
        for (MinimalXml.Element proxy : listed(doc, "proxies", "proxy")) {
            String id = text(proxy, "id", "<proxy>");
            String where = "<proxy> `" + (id == null ? "default" : id) + "`";
            String active = text(proxy, "active", where);
            if (active != null && !Boolean.parseBoolean(active)) continue;
            String host = text(proxy, "host", where);
            if (host == null) continue;
            String protocol = text(proxy, "protocol", where);
            int port = port(text(proxy, "port", where));
            if (port < 0) continue;
            List<String> nonProxyHosts = new ArrayList<>();
            String raw = text(proxy, "nonProxyHosts", where);
            if (raw != null) {
                for (String entry : raw.split("\\|")) {
                    if (!entry.isBlank()) nonProxyHosts.add(entry.strip());
                }
            }
            String username = text(proxy, "username", where);
            String password = text(proxy, "password", where);
            try {
                password = decrypted(password);
            } catch (MavenPasswords.Undecryptable e) {
                undecryptable(where, "<password>", e, "the proxy is used without credentials");
                username = null;
                password = null;
            }
            out.add(new MavenSettings.Proxy(
                    id == null ? "default" : id,
                    protocol == null ? "http" : protocol,
                    host,
                    port,
                    username,
                    password,
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

    private List<MavenSettings.Profile> profiles(MinimalXml.Element doc) {
        List<MavenSettings.Profile> out = new ArrayList<>();
        for (MinimalXml.Element profile : listed(doc, "profiles", "profile")) {
            String id = text(profile, "id", "<profile>");
            if (id == null) continue;
            String where = "<profile> `" + id + "`";
            String byDefault = profile.element("activation")
                    .map(a -> text(a, "activeByDefault", where + " <activation>"))
                    .orElse(null);
            out.add(new MavenSettings.Profile(id, Boolean.parseBoolean(byDefault), repositories(profile, where)));
        }
        return out;
    }

    /**
     * A profile's {@code <repositories>}, one {@link RepositorySpec} each with the {@code
     * <releases>} / {@code <snapshots>} policy the entry wrote; an entry with no id, no URL, or
     * neither policy enabled is skipped.
     */
    private List<RepositorySpec> repositories(MinimalXml.Element profile, String profileWhere) {
        List<RepositorySpec> out = new ArrayList<>();
        for (MinimalXml.Element repo : listed(profile, "repositories", "repository")) {
            String id = text(repo, "id", profileWhere + " <repository>");
            String where = profileWhere + " <repository> `" + id + "`";
            URI url = uri(text(repo, "url", where));
            if (id == null || url == null) continue;
            boolean releases = enabled(repo, "releases", where);
            boolean snapshots = enabled(repo, "snapshots", where);
            if (!releases && !snapshots) continue;
            out.add(new RepositorySpec(id, url).withPolicy(releases, snapshots));
        }
        return out;
    }

    /** {@code <releases><enabled>} / {@code <snapshots><enabled>}: on unless written false. */
    private boolean enabled(MinimalXml.Element repo, String policy, String where) {
        return repo.element(policy)
                .map(p -> text(p, "enabled", where + " <" + policy + ">"))
                .map(Boolean::parseBoolean)
                .orElse(true);
    }

    private Set<String> activeProfiles(MinimalXml.Element doc) {
        Set<String> ids = new LinkedHashSet<>();
        for (MinimalXml.Element id : listed(doc, "activeProfiles", "activeProfile")) {
            String text = values.expand(file, "<activeProfile>", id.text().strip());
            if (!text.isEmpty()) ids.add(text);
        }
        return ids;
    }

    /** The {@code <item>} children of {@code parent}'s direct {@code <list>} child, or none. */
    private static List<MinimalXml.Element> listed(MinimalXml.Element parent, String list, String item) {
        return parent.element(list).map(l -> l.elements(item)).orElse(List.of());
    }

    /**
     * Stripped, expanded text of the first direct child element named {@code tag}; null when
     * absent or blank. {@code where} names the entry for a warning ({@code <server> `nexus`}).
     */
    private @Nullable String text(MinimalXml.Element parent, String tag, String where) {
        return parent.element(tag)
                .map(MinimalXml.Element::text)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> values.expand(file, where + " <" + tag + ">", s))
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
