// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.RepositoryToml.VarPolicy.STRICT;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side line scanner for {@code [repositories]}. It exists because credentials must resolve
 * on the client — secrets never ride the wire — and {@code checkCliNoParseTypes} keeps the full
 * parser off the native image, so the CLI cannot reach
 * {@link RepositoryToml#repositories}. Exotic TOML → absent entry, never a wrong value.
 *
 * <p>It is a second <em>substrate</em>, not a second reader: the meaning of a
 * {@code token}/{@code username}/{@code password} triple comes from
 * {@link RepositoryToml#credentialOf} and the {@code ${ENV}} rule from
 * {@link RepositoryToml.VarPolicy#STRICT}, both shared with the tomlj reader.
 */
public final class RepositoriesScan {

    /** One {@code [repositories]} entry: name, url, and the inline credential when present. */
    public record Repo(String name, String url, Optional<RepoCredential> credential) {}

    private static final Pattern PAIR = Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private RepositoriesScan() {}

    public static List<Repo> scan(Path jkToml) {
        if (!Files.isRegularFile(jkToml)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(jkToml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }

        List<Repo> out = new ArrayList<>();
        String section = "";
        String entryName = null; // the [repositories.<name>] being collected
        String url = null;
        String token = null;
        String username = null;
        String password = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                commit(out, entryName, url, token, username, password);
                entryName = null;
                url = token = username = password = null;
                int close = line.indexOf(']');
                if (close <= 1) continue;
                section = line.substring(line.startsWith("[[") ? 2 : 1, close)
                        .replace("]", "")
                        .strip();
                if (section.startsWith("repositories.")) {
                    entryName = section.substring("repositories.".length());
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String rest = line.substring(eq + 1).strip();
            if (entryName != null) {
                String value = TomlScan.scalar(rest);
                switch (key) {
                    case "url" -> url = value;
                    case "token" -> token = value;
                    case "username" -> username = value;
                    case "password" -> password = value;
                    default -> {
                        // object-store keys etc. — not needed for credential resolution
                    }
                }
            } else if (section.equals("repositories")) {
                if (rest.startsWith("{")) {
                    // single-line inline table: name = { url = "…", username = "…", … }
                    String u = null;
                    String t = null;
                    String us = null;
                    String pw = null;
                    Matcher m = PAIR.matcher(rest);
                    while (m.find()) {
                        String v = MinimalToml.unquote('"' + m.group(2) + '"');
                        switch (m.group(1)) {
                            case "url" -> u = v;
                            case "token" -> t = v;
                            case "username" -> us = v;
                            case "password" -> pw = v;
                            default -> {
                                // ignore
                            }
                        }
                    }
                    commit(out, key, u, t, us, pw);
                } else {
                    commit(out, key, TomlScan.scalar(rest), null, null, null);
                }
            }
        }
        commit(out, entryName, url, token, username, password);
        return out;
    }

    private static void commit(
            List<Repo> out, String name, String url, String token, String username, String password) {
        if (name == null || url == null || url.isBlank()) return;
        String where = "repositories." + name;
        out.add(new Repo(
                name,
                url,
                RepositoryToml.credentialOf(
                        RepositoryToml.interpolate(token, STRICT, where),
                        RepositoryToml.interpolate(username, STRICT, where),
                        RepositoryToml.interpolate(password, STRICT, where))));
    }
}
