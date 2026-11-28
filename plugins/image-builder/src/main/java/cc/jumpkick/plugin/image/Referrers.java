// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.MiniJson;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpContent;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.http.Authorization;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.http.Request;
import com.google.cloud.tools.jib.http.Response;
import com.google.cloud.tools.jib.http.ResponseException;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Documents attached to a pushed image as OCI 1.1 referrers: one artifact manifest each, whose
 * {@code subject} is the image's manifest, so {@code oras discover}, {@code cosign tree} and a
 * registry's UI list them beside the image.
 *
 * <p>A registry that serves the referrers API indexes the subject itself and says so with an
 * {@code OCI-Subject} header on the manifest PUT. For one that does not, the distribution spec's
 * referrers tag schema: an image index at the tag {@code sha256-<hex>} of the subject, read,
 * extended and written back.
 *
 * <p>The requests go through Jib's HTTP client, so they take the proxy, TLS and loopback plain-HTTP
 * path the push itself took, with the credential it pushed with ({@link RegistryAuth#pushCredential})
 * traded for a pull-and-push token when the registry answers with a bearer challenge.
 */
final class Referrers {

    /** An artifact to attach: its {@code artifactType} and its layers, in order. */
    record Document(String artifactType, List<Layer> layers) {
        Document {
            layers = List.copyOf(layers);
        }

        /** One document whose single layer is {@code content}, of the artifact's own type. */
        static Document of(String artifactType, byte[] content) {
            return new Document(artifactType, List.of(new Layer(artifactType, content)));
        }
    }

    /** One blob of an artifact and its media type. */
    record Layer(String mediaType, byte[] content) {}

    static final String SBOM_TYPE = "application/vnd.cyclonedx+json";
    static final String PROVENANCE_TYPE = "application/vnd.in-toto+json";
    static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
    static final String INDEX_TYPE = "application/vnd.oci.image.index.v1+json";
    private static final String EMPTY_TYPE = "application/vnd.oci.empty.v1+json";

    /** The empty JSON object an artifact manifest names as its config. */
    private static final byte[] EMPTY = "{}".getBytes(StandardCharsets.UTF_8);

    /** What a pushed image's manifest may be: an index for a multi-platform push, else one manifest. */
    private static final List<String> SUBJECT_TYPES = List.of(
            INDEX_TYPE,
            "application/vnd.docker.distribution.manifest.list.v2+json",
            MANIFEST_TYPE,
            "application/vnd.docker.distribution.manifest.v2+json");

    /** {@code key="value"} pairs of an HTTP challenge, quoted values only (all a registry sends). */
    private static final Pattern CHALLENGE_PARAM = Pattern.compile("([a-zA-Z_]+)=\"([^\"]*)\"");

    private Referrers() {}

    /**
     * Attach {@code documents} to the image {@code reference} names, whose manifest is {@code
     * subjectDigest}, and return each artifact manifest's digest in order.
     */
    static List<String> attach(String reference, String subjectDigest, List<Document> documents, RegistryAuth auth)
            throws IOException {
        ImageReference target;
        Credential credential;
        try {
            target = ImageReference.parse(reference);
            credential = auth.pushCredential(reference);
        } catch (InvalidImageReferenceException | CredentialRetrievalException e) {
            throw new IOException("no credential for " + reference + ": " + e.getMessage(), e);
        }
        try (Session registry = new Session(
                target.getRegistry(), target.getRepository(), credential, RegistryAuth.loopback(reference))) {
            Map<String, Object> subject = registry.subject(subjectDigest);
            registry.pushBlob(EMPTY);
            List<String> digests = new ArrayList<>();
            List<Map<String, Object>> unindexed = new ArrayList<>();
            for (Document document : documents) {
                for (Layer layer : document.layers()) registry.pushBlob(layer.content());
                byte[] manifest = manifest(document, subject);
                String digest = digest(manifest);
                String indexedUnder = registry.putManifest(digest, MANIFEST_TYPE, manifest);
                if (!subjectDigest.equals(indexedUnder)) {
                    unindexed.add(descriptor(MANIFEST_TYPE, digest, manifest.length, document.artifactType()));
                }
                digests.add(digest);
            }
            if (!unindexed.isEmpty()) registry.addToIndex(tagSchema(subjectDigest), unindexed);
            return digests;
        }
    }

    /**
     * Put an OCI image index at {@code reference}'s tag over the manifests already pushed to its
     * repository, {@code os/arch} platform to digest, and return the index's digest.
     */
    static String putIndex(String reference, Map<String, String> manifests, RegistryAuth auth) throws IOException {
        ImageReference target;
        Credential credential;
        try {
            target = ImageReference.parse(reference);
            credential = auth.pushCredential(reference);
        } catch (InvalidImageReferenceException | CredentialRetrievalException e) {
            throw new IOException("no credential for " + reference + ": " + e.getMessage(), e);
        }
        try (Session registry = new Session(
                target.getRegistry(), target.getRepository(), credential, RegistryAuth.loopback(reference))) {
            List<Map<String, Object>> descriptors = new ArrayList<>();
            for (Map.Entry<String, String> m : manifests.entrySet()) {
                Fetched fetched = registry.manifest(m.getValue(), SUBJECT_TYPES);
                if (fetched == null) throw new IOException("the registry has no manifest " + m.getValue());
                descriptors.add(platformDescriptor(fetched.body(), m.getValue(), m.getKey()));
            }
            byte[] index = index(descriptors);
            registry.putManifest(target.getQualifier(), INDEX_TYPE, index);
            return digest(index);
        }
    }

    /** The OCI image index listing {@code descriptors}. */
    static byte[] index(List<Map<String, Object>> descriptors) {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("schemaVersion", 2);
        index.put("mediaType", INDEX_TYPE);
        index.put("manifests", descriptors);
        return MiniJson.write(index).getBytes(StandardCharsets.UTF_8);
    }

    /** The index entry of {@code manifest}, stored under {@code digest}, for the {@code os/arch} {@code platform}. */
    static Map<String, Object> platformDescriptor(byte[] manifest, String digest, String platform) throws IOException {
        String mediaType =
                MiniJson.str(MiniJson.parseRelaxed(new String(manifest, StandardCharsets.UTF_8)), "mediaType");
        if (mediaType == null) throw new IOException("manifest " + digest + " names no media type");
        int slash = platform.indexOf('/');
        if (slash <= 0) throw new IOException("invalid platform `" + platform + "` (expect os/arch)");
        Map<String, Object> descriptor = descriptor(mediaType, digest, manifest.length, null);
        Map<String, Object> on = new LinkedHashMap<>();
        on.put("architecture", platform.substring(slash + 1));
        on.put("os", platform.substring(0, slash));
        descriptor.put("platform", on);
        return descriptor;
    }

    /** The artifact manifest for {@code document}: the empty config, then the document's layers. */
    static byte[] manifest(Document document, Map<String, Object> subject) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 2);
        manifest.put("mediaType", MANIFEST_TYPE);
        manifest.put("artifactType", document.artifactType());
        manifest.put("config", descriptor(EMPTY_TYPE, digest(EMPTY), EMPTY.length, null));
        manifest.put(
                "layers",
                document.layers().stream()
                        .map(l -> descriptor(l.mediaType(), digest(l.content()), l.content().length, null))
                        .toList());
        manifest.put("subject", subject);
        return MiniJson.write(manifest).getBytes(StandardCharsets.UTF_8);
    }

    /** The tag the referrers tag schema keeps the index of {@code digest}'s referrers under. */
    static String tagSchema(String digest) {
        return digest.replace(':', '-');
    }

    private static Map<String, Object> descriptor(
            String mediaType, String digest, long size, @Nullable String artifactType) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("mediaType", mediaType);
        descriptor.put("digest", digest);
        descriptor.put("size", size);
        if (artifactType != null) descriptor.put("artifactType", artifactType);
        return descriptor;
    }

    private static String digest(byte[] content) {
        return "sha256:" + Hashing.sha256Hex(content);
    }

    private static @Nullable String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static URL toUrl(String url) throws IOException {
        try {
            return new URI(url).toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("not a registry URL: " + url, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** One repository on one registry, authenticated once and reused for every request. */
    private static final class Session implements AutoCloseable {
        private final FailoverHttpClient http;
        private final String registry;
        private final String repository;
        private final @Nullable Credential credential;
        private @Nullable Authorization authorization;

        Session(String registry, String repository, @Nullable Credential credential, boolean loopback) {
            // Plain HTTP and a credential over it only on loopback, as RegistryAuth allows the push.
            this.http = new FailoverHttpClient(loopback, loopback, RegistryAuth::log);
            this.registry = registry;
            this.repository = repository;
            this.credential = credential;
        }

        /** The descriptor of the manifest stored under {@code digest}, read back from the registry. */
        Map<String, Object> subject(String digest) throws IOException {
            Fetched fetched = manifest(digest, SUBJECT_TYPES);
            if (fetched == null) throw new IOException("the registry has no manifest " + digest);
            if (!digest.equals(digest(fetched.body()))) {
                throw new IOException("the registry served other bytes for manifest " + digest);
            }
            String mediaType = fetched.contentType();
            if (mediaType == null || mediaType.isBlank() || mediaType.startsWith("application/json")) {
                mediaType = MiniJson.str(
                        MiniJson.parseRelaxed(new String(fetched.body(), StandardCharsets.UTF_8)), "mediaType");
            }
            if (mediaType == null) throw new IOException("manifest " + digest + " names no media type");
            return Referrers.descriptor(mediaType.split(";", 2)[0].strip(), digest, fetched.body().length, null);
        }

        /** Upload {@code content} as a blob: open an upload session, then commit it in one PUT. */
        void pushBlob(byte[] content) throws IOException {
            URL start = url("/blobs/uploads/");
            String location;
            try (Response opened = call("POST", start, List.of(), null)) {
                location = first(opened.getHeader("Location"));
            }
            if (location == null) throw new IOException("the registry opened a blob upload with no Location");
            String session;
            try {
                session = start.toURI().resolve(location).toString();
            } catch (URISyntaxException | IllegalArgumentException e) {
                throw new IOException("the registry opened a blob upload at " + location, e);
            }
            URL commit = toUrl(session + (session.indexOf('?') < 0 ? '?' : '&') + "digest=" + encode(digest(content)));
            call("PUT", commit, List.of(), new ByteArrayContent("application/octet-stream", content))
                    .close();
        }

        /** PUT a manifest; the {@code OCI-Subject} the registry answers with, or null when it names none. */
        @Nullable
        String putManifest(String reference, String mediaType, byte[] body) throws IOException {
            try (Response put =
                    call("PUT", url("/manifests/" + reference), List.of(), new ByteArrayContent(mediaType, body))) {
                return first(put.getHeader("OCI-Subject"));
            }
        }

        /** Add {@code descriptors} to the index at {@code tag}, creating it, unless each is already listed. */
        void addToIndex(String tag, List<Map<String, Object>> descriptors) throws IOException {
            Fetched held = manifest(tag, List.of(INDEX_TYPE));
            Map<String, Object> index = new LinkedHashMap<>();
            List<Object> manifests = new ArrayList<>();
            if (held != null
                    && MiniJson.parseRelaxed(new String(held.body(), StandardCharsets.UTF_8))
                            instanceof Map<?, ?> read) {
                for (Map.Entry<?, ?> e : read.entrySet()) index.put(String.valueOf(e.getKey()), e.getValue());
                manifests.addAll(MiniJson.list(read, "manifests"));
            } else {
                index.put("schemaVersion", 2);
                index.put("mediaType", INDEX_TYPE);
            }
            boolean changed = false;
            for (Map<String, Object> descriptor : descriptors) {
                Object digest = descriptor.get("digest");
                boolean listed = manifests.stream().anyMatch(m -> Objects.equals(digest, MiniJson.str(m, "digest")));
                if (!listed) {
                    manifests.add(descriptor);
                    changed = true;
                }
            }
            if (!changed) return;
            index.put("manifests", manifests);
            putManifest(tag, INDEX_TYPE, MiniJson.write(index).getBytes(StandardCharsets.UTF_8));
        }

        /** The manifest at {@code reference}, or null when the registry has none. */
        private @Nullable Fetched manifest(String reference, List<String> accept) throws IOException {
            try (Response got = call("GET", url("/manifests/" + reference), accept, null)) {
                return new Fetched(got.getBody().readAllBytes(), first(got.getHeader("Content-Type")));
            } catch (ResponseException e) {
                if (e.getStatusCode() == 404) return null;
                throw e;
            }
        }

        private URL url(String path) throws IOException {
            return toUrl("https://" + registry + "/v2/" + repository + path);
        }

        /**
         * One request, answered once more after a {@code 401} with the authorization its challenge
         * asks for. The authorization rides only to the registry itself, never to an upload
         * location on another host.
         */
        private Response call(String method, URL url, List<String> accept, @Nullable HttpContent body)
                throws IOException {
            for (int attempt = 0; ; attempt++) {
                Request.Builder request = Request.builder();
                if (!accept.isEmpty()) request.setAccept(accept);
                if (authorization != null && registry.equalsIgnoreCase(url.getAuthority())) {
                    request.setAuthorization(authorization);
                }
                if (body != null) request.setBody(body);
                try {
                    return http.call(method, url, request.build());
                } catch (ResponseException e) {
                    if (e.getStatusCode() != 401 || attempt > 0) throw e;
                    Authorization answered = authorize(e.getHeaders().getAuthenticate());
                    if (answered == null) throw e;
                    authorization = answered;
                }
            }
        }

        /** The authorization a {@code WWW-Authenticate} challenge asks for, or null when there is none to give. */
        private @Nullable Authorization authorize(@Nullable String challenge) throws IOException {
            if (challenge != null && challenge.regionMatches(true, 0, "Bearer ", 0, 7)) return bearer(challenge);
            return credential == null
                    ? null
                    : Authorization.fromBasicCredentials(credential.getUsername(), credential.getPassword());
        }

        /**
         * A pull-and-push token from the challenge's realm, asked with the credential when there is
         * one: the one scope covers every request this session makes.
         */
        private @Nullable Authorization bearer(String challenge) throws IOException {
            Map<String, String> params = new HashMap<>();
            Matcher m = CHALLENGE_PARAM.matcher(challenge);
            while (m.find()) params.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
            String realm = params.get("realm");
            if (realm == null || realm.isBlank()) return null;
            StringBuilder url = new StringBuilder(realm).append(realm.indexOf('?') < 0 ? '?' : '&');
            url.append("scope=").append(encode("repository:" + repository + ":pull,push"));
            if (params.containsKey("service")) url.append("&service=").append(encode(params.get("service")));
            Request.Builder request = Request.builder().setAccept(List.of("application/json"));
            if (credential != null) {
                request.setAuthorization(
                        Authorization.fromBasicCredentials(credential.getUsername(), credential.getPassword()));
            }
            try (Response response = http.get(toUrl(url.toString()), request.build())) {
                Object body =
                        MiniJson.parseRelaxed(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                String token = MiniJson.str(body, "token");
                if (token == null || token.isBlank()) token = MiniJson.str(body, "access_token");
                return token == null || token.isBlank() ? null : Authorization.fromBearerToken(token);
            } catch (ResponseException e) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            http.shutDown();
        }
    }

    private record Fetched(byte[] body, @Nullable String contentType) {}
}
