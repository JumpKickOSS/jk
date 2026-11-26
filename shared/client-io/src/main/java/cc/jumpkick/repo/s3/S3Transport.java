// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo.s3;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.DomXml;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.FileBody;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.pom.PomXml;
import cc.jumpkick.repo.ObjectStoreTransport;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Path-style S3 and Google Cloud Storage XML requests signed with {@link SigV4Signer}.
 */
public final class S3Transport implements ObjectStoreTransport {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final byte[] EMPTY = new byte[0];
    private static final String EMPTY_HASH = SigV4Signer.sha256Hex(EMPTY);
    private static final long MULTIPART_THRESHOLD = 64L * 1024 * 1024;
    private static final long PART_SIZE = 16L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;
    private static final int PARALLEL_PARTS = 4;

    private final Http http;
    private final URI endpoint; // e.g. https://s3.us-east-1.amazonaws.com  (no trailing slash)
    private final String region;
    private final Optional<AwsCredentials> credentials;
    private final Supplier<Instant> clock;
    private final boolean gcs;

    public S3Transport(
            Http http, URI endpoint, String region, Optional<AwsCredentials> credentials, Supplier<Instant> clock) {
        this(http, endpoint, region, credentials, clock, false);
    }

    private S3Transport(
            Http http,
            URI endpoint,
            String region,
            Optional<AwsCredentials> credentials,
            Supplier<Instant> clock,
            boolean gcs) {
        this.gcs = gcs;
        this.http = http;
        this.endpoint = stripTrailingSlash(endpoint);
        this.region = region;
        this.credentials = credentials;
        this.clock = clock;
    }

    /** Resolve endpoint/region/credentials from the AWS environment for an {@code s3://} URL. */
    public static S3Transport fromEnv(
            Http http, URI s3Url, AwsCredentialChain chain, Function<String, @Nullable String> env) {
        return forS3(http, s3Url, ObjectStoreConfig.EMPTY, chain, env);
    }

    /**
     * Transport for an {@code s3://} URL, merging explicit per-repo {@link ObjectStoreConfig} (from
     * {@code jk.toml}) over the AWS environment / default chain. Region: config → chain (env/profile)
     * → {@code us-east-1}. Endpoint: config → {@code AWS_ENDPOINT_URL} → {@code
     * s3.<region>.amazonaws.com}. Credentials: explicit config keys → chain → none (unsigned/public).
     */
    public static S3Transport forS3(
            Http http,
            URI s3Url,
            ObjectStoreConfig cfg,
            AwsCredentialChain chain,
            Function<String, @Nullable String> env) {
        String region = Objects.requireNonNullElse(
                firstNonBlank(
                        cfg.region(),
                        chain.resolve(cfg.region()).map(AwsCredentials::region).orElse(null)),
                "us-east-1");
        Optional<AwsCredentials> creds = resolveCreds(cfg, chain, region);
        String endpointOverride = firstNonBlank(cfg.endpoint(), env.apply("AWS_ENDPOINT_URL"));
        URI endpoint = endpointOverride != null
                ? URI.create(endpointOverride.strip())
                : URI.create("https://s3." + region + ".amazonaws.com");
        return new S3Transport(http, endpoint, region, creds, Clock.SYSTEM::instant);
    }

    /**
     * {@code gs://} via GCS's S3-compatible XML API ({@code storage.googleapis.com}, region {@code
     * auto}).
     */
    public static S3Transport forGcs(
            Http http, URI gsUrl, AwsCredentialChain chain, Function<String, @Nullable String> env) {
        return forGcs(http, gsUrl, ObjectStoreConfig.EMPTY, chain, env);
    }

    /** {@link #forGcs(Http, URI, AwsCredentialChain, Function)} with explicit per-repo config. */
    public static S3Transport forGcs(
            Http http,
            URI gsUrl,
            ObjectStoreConfig cfg,
            AwsCredentialChain chain,
            Function<String, @Nullable String> env) {
        Optional<AwsCredentials> creds = resolveCreds(cfg, chain, "auto");
        String endpointOverride = firstNonBlank(cfg.endpoint(), env.apply("AWS_ENDPOINT_URL"));
        URI endpoint = endpointOverride != null
                ? URI.create(endpointOverride.strip())
                : URI.create("https://storage.googleapis.com");
        return new S3Transport(http, endpoint, "auto", creds, Clock.SYSTEM::instant, true);
    }

    /**
     * Explicit config credentials win; otherwise the AWS default chain; both stamped with {@code
     * region}.
     */
    private static Optional<AwsCredentials> resolveCreds(
            ObjectStoreConfig cfg, AwsCredentialChain chain, String region) {
        if (cfg.hasExplicitCredentials()) {
            String sessionToken = cfg.sessionToken();
            String token = (sessionToken != null && !sessionToken.isBlank()) ? sessionToken : null;
            return Optional.of(new AwsCredentials(
                    Objects.requireNonNull(cfg.accessKey(), "accessKey"),
                    Objects.requireNonNull(cfg.secretKey(), "secretKey"),
                    token,
                    region));
        }
        return chain.resolve(region)
                .map(c -> new AwsCredentials(c.accessKeyId(), c.secretAccessKey(), c.sessionToken(), region));
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }

    @Override
    public Optional<byte[]> fetch(URI uri, RepoCredential ignored) throws IOException, InterruptedException {
        URI object = objectUri(uri);
        HttpResponse<byte[]> resp = http.get(object, signedHeaders("GET", object, EMPTY_HASH, Map.of()));
        int status = resp.statusCode();
        if (status == 404) return Optional.empty();
        if (status >= 400) throw new IOException("S3 HTTP " + status + " fetching " + object);
        return Optional.of(resp.body());
    }

    /** The object streamed, so an artifact in flight costs its connection and not its payload. */
    @Override
    public Optional<InputStream> fetchStream(URI uri, RepoCredential ignored) throws IOException, InterruptedException {
        return fetchBody(uri, ignored).map(Body::in);
    }

    @Override
    public Optional<Body> fetchBody(URI uri, RepoCredential ignored) throws IOException, InterruptedException {
        URI object = objectUri(uri);
        HttpResponse<InputStream> resp = http.getStream(object, signedHeaders("GET", object, EMPTY_HASH, Map.of()));
        int status = resp.statusCode();
        if (status == 404) {
            drain(resp.body());
            return Optional.empty();
        }
        if (status >= 400) {
            drain(resp.body());
            throw new IOException("S3 HTTP " + status + " fetching " + object);
        }
        return Optional.of(new Body(resp.body(), Http.declaredLength(resp)));
    }

    /** Consume and discard a non-success body so the connection can be reused. */
    private static void drain(InputStream body) throws IOException {
        try (body) {
            body.transferTo(OutputStream.nullOutputStream());
        }
    }

    @Override
    public Optional<Revision> readRevision(URI uri, RepoCredential ignored) throws IOException, InterruptedException {
        URI object = objectUri(uri);
        HttpResponse<byte[]> response = http.get(object, signedHeaders("GET", object, EMPTY_HASH, Map.of()));
        if (response.statusCode() == 404) return Optional.empty();
        requireSuccess("GET", object, response.statusCode());
        String etag = requiredHeader(response, "ETag");
        String validator = gcs ? requiredHeader(response, "x-goog-generation") : etag;
        return Optional.of(new Revision(response.body(), validator, etag));
    }

    @Override
    public int put(URI uri, byte[] body, String contentType, RepoCredential credential)
            throws IOException, InterruptedException {
        return putRevision(uri, body, contentType, credential, Optional.empty());
    }

    @Override
    public int putRevision(
            URI uri, byte[] body, String contentType, RepoCredential ignored, Optional<Revision> previous)
            throws IOException, InterruptedException {
        URI object = objectUri(uri);
        Map<String, String> headers = conditions(previous);
        headers.put("Content-Type", contentType);
        return http.put(object, body, signedHeaders("PUT", object, SigV4Signer.sha256Hex(body), headers))
                .statusCode();
    }

    @Override
    public int putFile(URI uri, Path file, String contentType, RepoCredential ignored)
            throws IOException, InterruptedException {
        URI object = objectUri(uri);
        long size = Files.size(file);
        Map<String, String> conditions = conditions(Optional.empty());
        // GCS XML multipart uploads reject write preconditions.
        if (!gcs && size >= MULTIPART_THRESHOLD) {
            return multipart(object, file, size, contentType, conditions);
        }
        conditions.put("Content-Type", contentType);
        return http.putFile(object, file, signedHeaders("PUT", object, Hashing.sha256Hex(file), conditions))
                .statusCode();
    }

    /** Every key under {@code dir}, through ListObjectsV2 one page at a time. */
    @Override
    public List<String> list(URI dir, RepoCredential ignored) throws IOException, InterruptedException {
        String path = dir.getPath() == null ? "" : dir.getPath();
        String prefix = path.startsWith("/") ? path.substring(1) : path;
        if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix += "/";
        List<String> names = new ArrayList<>();
        String token = null;
        do {
            URI page = URI.create(endpoint + "/" + dir.getHost() + "?list-type=2&prefix=" + SigV4Signer.rfc3986(prefix)
                    + (token == null ? "" : "&continuation-token=" + SigV4Signer.rfc3986(token)));
            HttpResponse<byte[]> response = http.get(page, signedHeaders("GET", page, EMPTY_HASH, Map.of()));
            requireSuccess("list", page, response.statusCode());
            var root = DomXml.parse(response.body()).getDocumentElement();
            for (var contents : DomXml.childElements(root, "Contents")) {
                String key = DomXml.childText(contents, "Key");
                if (key != null && key.startsWith(prefix) && key.length() > prefix.length()) {
                    names.add(key.substring(prefix.length()));
                }
            }
            token = EnvValues.parseBool(DomXml.childText(root, "IsTruncated")).orElse(false)
                    ? DomXml.childText(root, "NextContinuationToken")
                    : null;
        } while (token != null && !token.isEmpty());
        return names;
    }

    @Override
    public int delete(URI uri, RepoCredential ignored) throws IOException, InterruptedException {
        URI object = objectUri(uri);
        return http.delete(object, signedHeaders("DELETE", object, EMPTY_HASH, Map.of()))
                .statusCode();
    }

    private Map<String, String> conditions(Optional<Revision> previous) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (previous.isPresent()) {
            headers.put("If-Match", previous.get().etag());
        } else {
            headers.put("If-None-Match", "*");
        }
        if (gcs)
            headers.put(
                    "x-goog-if-generation-match",
                    previous.map(Revision::validator).orElse("0"));
        return headers;
    }

    private int multipart(URI object, Path file, long size, String contentType, Map<String, String> conditions)
            throws IOException, InterruptedException {
        URI init = URI.create(object + "?uploads");
        var started = http.postOnce(
                init,
                BodyPublishers.noBody(),
                signedHeaders("POST", init, EMPTY_HASH, Map.of("Content-Type", contentType)));
        requireSuccess("start multipart upload", object, started.statusCode());
        String uploadId = DomXml.childText(DomXml.parse(started.body()).getDocumentElement(), "UploadId");
        if (uploadId == null || uploadId.isEmpty()) throw new IOException(object + ": missing multipart upload ID");
        URI upload = URI.create(object + "?uploadId=" + SigV4Signer.rfc3986(uploadId));

        int status;
        try {
            byte[] manifest = uploadParts(upload, file, size);
            conditions.put("Content-Type", "application/xml");
            var completed = http.postOnce(
                    upload,
                    BodyPublishers.ofByteArray(manifest),
                    signedHeaders("POST", upload, SigV4Signer.sha256Hex(manifest), conditions));
            status = completionStatus(object, completed);
            if (status != 412 && status != 409) return status;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try {
                abort(upload);
            } catch (IOException | InterruptedException cleanup) {
                failure.addSuppressed(cleanup);
                if (cleanup instanceof InterruptedException)
                    Thread.currentThread().interrupt();
            }
            throw failure;
        }
        abort(upload);
        return status;
    }

    /** S3 can send a successful HTTP status before it knows the completion result. */
    private static int completionStatus(URI object, HttpResponse<byte[]> response) throws IOException {
        int status = response.statusCode();
        if (status == 412 || status == 409) return status;
        requireSuccess("complete multipart upload", object, status);
        var root = DomXml.parse(response.body()).getDocumentElement();
        if ("CompleteMultipartUploadResult".equals(root.getNodeName())) return status;
        String code = DomXml.childText(root, "Code");
        if ("PreconditionFailed".equals(code)) return 412;
        if ("ConditionalRequestConflict".equals(code)) return 409;
        throw new IOException(object + ": multipart completion failed: " + code);
    }

    private void abort(URI upload) throws IOException, InterruptedException {
        int status = http.delete(upload, signedHeaders("DELETE", upload, EMPTY_HASH, Map.of()))
                .statusCode();
        if (status != 404) requireSuccess("abort multipart upload", upload, status);
    }

    private byte[] uploadParts(URI upload, Path file, long size) throws IOException, InterruptedException {
        long partSize = Math.max(PART_SIZE, Math.ceilDiv(size, MAX_PARTS));
        StringBuilder manifest = new StringBuilder("<CompleteMultipartUpload>");
        try {
            List<Callable<String>> batch = new ArrayList<>(PARALLEL_PARTS);
            int count = (int) Math.ceilDiv(size, partSize);
            for (int number = 1; number <= count; number++) {
                long offset = (number - 1L) * partSize;
                long length = Math.min(partSize, size - offset);
                int part = number;
                batch.add(() -> uploadPart(upload, file, offset, length, part));
                if (batch.size() == PARALLEL_PARTS || number == count) {
                    for (var result : JkThreads.platform().invokeAll(batch)) manifest.append(result.get());
                    batch.clear();
                }
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException interrupted) throw interrupted;
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(file + ": multipart upload failed", e.getCause());
        }
        return manifest.append("</CompleteMultipartUpload>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private String uploadPart(URI upload, Path file, long offset, long length, int number)
            throws IOException, InterruptedException {
        URI part = URI.create(upload + "&partNumber=" + number);
        var digest = Hashing.newSha256();
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(offset);
            byte[] buffer = new byte[64 * 1024];
            for (long remaining = length; remaining > 0; ) {
                int count = (int) Math.min(remaining, buffer.length);
                if (in.readNBytes(buffer, 0, count) != count) throw new IOException(file + " shrank during upload");
                digest.update(buffer, 0, count);
                remaining -= count;
            }
        }
        var response = http.put(
                part,
                FileBody.range(file, offset, length),
                signedHeaders("PUT", part, Hashing.hex(digest.digest()), Map.of()));
        requireSuccess("upload part " + number, upload, response.statusCode());
        return "<Part><PartNumber>" + number + "</PartNumber><ETag>" + PomXml.escape(requiredHeader(response, "ETag"))
                + "</ETag></Part>";
    }

    private static String requiredHeader(HttpResponse<?> response, String name) throws IOException {
        return response.headers()
                .firstValue(name)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IOException(response.uri() + ": missing " + name));
    }

    private static void requireSuccess(String operation, URI object, int status) throws IOException {
        if (status < 200 || status >= 300) throw new IOException(object + ": " + operation + " returned " + status);
    }

    /** {@code s3://bucket/prefix...} → {@code <endpoint>/bucket/prefix...} (path-style). */
    private URI objectUri(URI s3Url) {
        String bucket = s3Url.getHost();
        String key = s3Url.getRawPath() == null ? "" : s3Url.getRawPath(); // already starts with '/'
        return URI.create(endpoint.toString() + "/" + bucket + key);
    }

    private Map<String, String> signedHeaders(
            String method, URI object, String payloadHash, Map<String, String> requestHeaders) {
        var dialect = gcs ? SigV4Signer.Dialect.GOOGLE : SigV4Signer.Dialect.AWS;
        String date = AMZ_DATE.format(clock.get());
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders);
        headers.put(dialect.headerPrefix + "content-sha256", payloadHash);
        headers.put(dialect.headerPrefix + "date", date);
        if (credentials.isEmpty()) {
            return headers; // unsigned — public bucket
        }
        AwsCredentials c = credentials.get();
        if (c.sessionToken() != null && !gcs) headers.put("x-amz-security-token", c.sessionToken());

        Map<String, String> toSign = new LinkedHashMap<>(headers);
        toSign.put("host", hostHeader(object));

        String authorization = SigV4Signer.authorization(
                dialect,
                method,
                object,
                toSign,
                payloadHash,
                c.accessKeyId(),
                c.secretAccessKey(),
                region,
                gcs ? "storage" : "s3",
                date);
        headers.put("Authorization", authorization);
        return headers;
    }

    /** Host header including a non-default port, as the signature requires. */
    private static String hostHeader(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean defaultPort = port == -1 || (https && port == 443) || (!https && port == 80);
        return defaultPort ? host : host + ":" + port;
    }

    private static URI stripTrailingSlash(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? URI.create(s.substring(0, s.length() - 1)) : uri;
    }
}
