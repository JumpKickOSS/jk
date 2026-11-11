// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.schema;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.LockSchemas;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.repo.AuthHeaders;
import cc.jumpkick.repo.RepoCredentialResolver;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A Confluent-compatible schema registry's read API: {@code GET
 * /subjects/<subject>/versions/<version>}, which Confluent Schema Registry, Confluent Cloud,
 * Redpanda, Karapace and Apicurio's {@code ccompat} endpoint all serve. Requests carry the
 * credential the repository chain resolves for the table's {@code repository} id (the registry's
 * host when it names none); Confluent Cloud's API key and secret are a basic credential.
 */
final class RegistryClient {

    /** One schema as the registry serves a subject version. {@code type} is lower case. */
    record Version(String subject, int version, long id, String type, String schema, List<Reference> references) {}

    /** A schema's reference to another subject version, under the name the schema uses for it. */
    record Reference(String name, String subject, int version) {

        String key() {
            return subject + "@" + version;
        }
    }

    private static final String ACCEPT = "application/vnd.schemaregistry.v1+json, application/json";

    private final URI base;
    private final Map<String, String> headers;
    private final Http http;

    private RegistryClient(URI base, Map<String, String> headers, Http http) {
        this.base = base;
        this.headers = headers;
        this.http = http;
    }

    /** A client for {@code declared}'s registry, with the credential the chain resolves for it. */
    static RegistryClient of(LockSchemas.Declared declared) {
        URI base = URI.create(declared.registry() + "/");
        String id = declared.repository() != null ? declared.repository() : base.getHost();
        RepoCredential credential = RepoCredentialResolver.withEnv(BuildEnv.ambient()::apply, MavenSettings.current())
                .resolve(id, base, Optional.empty());
        Map<String, String> headers = new LinkedHashMap<>(AuthHeaders.of(credential));
        headers.put("Accept", ACCEPT);
        return new RegistryClient(base, Map.copyOf(headers), new Http());
    }

    /** {@code subject} at {@code version}: a number, or {@code latest}. */
    Version version(String subject, String version) throws IOException, InterruptedException {
        URI uri = base.resolve(
                "subjects/" + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20") + "/versions/"
                        + URLEncoder.encode(version, StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = http.documents().get(uri, headers);
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IOException(subject + ": registry refused the credential (HTTP " + status + ")");
        }
        if (status == 404) {
            throw new IOException(subject + ": no version " + version + " at " + SafeUri.forMessage(base));
        }
        if (status != 200) {
            throw new IOException(subject + ": registry answered HTTP " + status);
        }
        return parse(subject, new String(response.body(), StandardCharsets.UTF_8));
    }

    static Version parse(String subject, String json) throws IOException {
        Object root = MiniJson.parse(json);
        String schema = MiniJson.str(root, "schema");
        if (schema == null) throw new IOException(subject + ": registry answer has no schema");
        String type = MiniJson.str(root, "schemaType");
        List<Reference> refs = new ArrayList<>();
        for (Object ref : MiniJson.list(root, "references")) {
            refs.add(new Reference(
                    require(subject, MiniJson.str(ref, "name"), "reference name"),
                    require(subject, MiniJson.str(ref, "subject"), "reference subject"),
                    (int) number(subject, MiniJson.get(ref, "version"))));
        }
        return new Version(
                subject,
                (int) number(subject, MiniJson.get(root, "version")),
                number(subject, MiniJson.get(root, "id")),
                type == null ? "avro" : type.toLowerCase(Locale.ROOT),
                schema,
                refs);
    }

    private static long number(String subject, @Nullable Object value) throws IOException {
        if (value instanceof Number n) return n.longValue();
        throw new IOException(subject + ": registry answer lacks a version or id");
    }

    private static String require(String subject, @Nullable String value, String what) throws IOException {
        if (value == null) throw new IOException(subject + ": registry answer lacks a " + what);
        return value;
    }
}
