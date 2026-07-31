// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.ObjectStoreConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Shared {@code [repositories.<name>]} field parser ({@code ${ENV}} interpolation, credentials,
 * object-store, exclusive {@code groups}). Callers supply missing-var policy via {@code
 * resolveVar} (strict project vs lenient global).
 */
public final class RepositoryToml {

    private RepositoryToml() {}

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * Expand {@code ${VAR}} references in {@code raw}, resolving each var name via {@code resolveVar}
     * (which returns the replacement, or may throw for a strict "unset var" policy). {@code null} in →
     * {@code null} out.
     */
    public static String interpolate(String raw, UnaryOperator<String> resolveVar) {
        if (raw == null) return null;
        Matcher m = ENV_REF.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(resolveVar.apply(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Bearer ({@code token}) or basic ({@code username}/{@code password}) credential on the table, or
     * empty. {@code interp} applies the caller's {@code ${ENV}} interpolation to each value.
     */
    public static Optional<RepoCredential> credential(TomlTable t, UnaryOperator<String> interp) {
        String token = interp.apply(t.getString("token"));
        String username = interp.apply(t.getString("username"));
        String password = interp.apply(t.getString("password"));
        if (token != null && !token.isBlank()) {
            return Optional.of(new RepoCredential.Bearer(token));
        }
        if (username != null && !username.isBlank()) {
            return Optional.of(new RepoCredential.Basic(username, password == null ? "" : password));
        }
        return Optional.empty();
    }

    /**
     * Object-store config ({@code region}/{@code endpoint}/{@code access-key}/{@code secret-key}/
     * {@code session-token}) for s3://gs:// backends, or empty when none set. {@code interp} applies
     * the caller's {@code ${ENV}} interpolation.
     */
    public static Optional<ObjectStoreConfig> objectStore(TomlTable t, UnaryOperator<String> interp) {
        ObjectStoreConfig cfg = new ObjectStoreConfig(
                blankToNull(interp.apply(t.getString("region"))),
                blankToNull(interp.apply(t.getString("endpoint"))),
                blankToNull(interp.apply(t.getString("access-key"))),
                blankToNull(interp.apply(t.getString("secret-key"))),
                blankToNull(interp.apply(t.getString("session-token"))));
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Exclusive Maven group bindings ({@code groups = ["com.acme", "com.acme.*"]}) for.
     * Empty when absent. Strict callers pass a path prefix for error messages; invalid types throw
     * {@link IllegalArgumentException}.
     */
    public static List<String> groups(TomlTable t, String pathForErrors) {
        if (t == null || !t.contains("groups")) return List.of();
        Object raw = t.get("groups");
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) {
            throw new IllegalArgumentException(
                    pathForErrors + ".groups must be an array of strings (e.g. [\"com.acme\", \"com.acme.*\"])");
        }
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object el = arr.get(i);
            if (!(el instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(pathForErrors + ".groups[" + i + "] must be a non-empty string");
            }
            out.add(s.trim());
        }
        return List.copyOf(out);
    }
}
