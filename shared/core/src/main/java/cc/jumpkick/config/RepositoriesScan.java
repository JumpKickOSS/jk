// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.credential.RepoCredential;
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
 * Client-side line scanner for {@code [repositories]} (credentials must resolve client-side;
 * secrets never ride the wire). Exotic TOML → absent entry; {@code ${ENV}} is strict via
 * {@link RepositoryToml#interpolate}.
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
                        String v = unescape(m.group(2));
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
        Optional<RepoCredential> credential = Optional.empty();
        String t = interp(token);
        String u = interp(username);
        if (t != null && !t.isBlank()) {
            credential = Optional.of(new RepoCredential.Bearer(t));
        } else if (u != null && !u.isBlank()) {
            String p = interp(password);
            credential = Optional.of(new RepoCredential.Basic(u, p == null ? "" : p));
        }
        out.add(new Repo(name, url, credential));
    }

    /** Strict {@code ${ENV}} interpolation, matching the full parser's publish semantics. */
    private static String interp(String raw) {
        return RepositoryToml.interpolate(raw, var -> {
            String val = System.getenv(var);
            if (val == null) {
                throw new JkBuildParseException(
                        "repository credential references unset environment variable ${" + var + "}");
            }
            return val;
        });
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
