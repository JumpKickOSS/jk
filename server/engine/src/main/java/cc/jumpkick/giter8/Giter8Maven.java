// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.repo.MavenMetadata;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Central {@code maven-metadata.xml} lookup for Giter8 {@code maven()} properties. */
public final class Giter8Maven {

    private static final Pattern EXPR = Pattern.compile(
            "maven\\(\\s*([^,\\s]+)\\s*,\\s*([^,\\s]+)(?:\\s*,\\s*([^)]+))?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final URI CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    private Giter8Maven() {}

    public static MavenVersionLookup central(boolean offline) {
        return (group, artifact, stable) -> {
            if (offline) {
                throw new IOException("maven() properties require the network (offline)");
            }
            String path = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
            HttpRequest req =
                    HttpRequest.newBuilder(CENTRAL.resolve(path)).GET().build();
            HttpResponse<byte[]> res;
            try {
                res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("maven() lookup interrupted", e);
            }
            if (res.statusCode() != 200 || res.body() == null) {
                throw new IOException(
                        "maven() lookup failed for " + group + ":" + artifact + " (HTTP " + res.statusCode() + ")");
            }
            MavenMetadata md = MavenMetadata.parse(res.body());
            String picked = pick(md, stable);
            if (picked == null || picked.isBlank()) {
                throw new IOException("maven() found no version for " + group + ":" + artifact);
            }
            return picked;
        };
    }

    static String pick(MavenMetadata md, boolean stable) {
        if (!stable) {
            if (md.latest() != null && !md.latest().isBlank()) return md.latest();
            List<String> v = md.versions();
            return v.isEmpty() ? null : v.get(v.size() - 1);
        }
        if (md.release() != null && !md.release().isBlank() && !unstable(md.release())) return md.release();
        List<String> v = md.versions();
        for (int i = v.size() - 1; i >= 0; i--) {
            if (!unstable(v.get(i))) return v.get(i);
        }
        return null;
    }

    static boolean isMavenExpr(String value) {
        return value != null && EXPR.matcher(value.strip()).matches();
    }

    static String resolveExpr(String value, MavenVersionLookup lookup) throws IOException {
        Matcher m = EXPR.matcher(value.strip());
        if (!m.matches()) return value;
        if (lookup == null) {
            throw new IOException("maven() property requires a version lookup: " + value.strip());
        }
        String third = m.group(3);
        boolean stable = third != null && third.strip().equalsIgnoreCase("stable");
        return lookup.latest(m.group(1).strip(), m.group(2).strip(), stable);
    }

    private static boolean unstable(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        return v.contains("snapshot")
                || v.contains("-rc")
                || v.contains(".rc")
                || v.contains("-alpha")
                || v.contains("-beta")
                || v.contains("milestone")
                || v.matches(".*(^|[.-])m\\d+.*");
    }
}
