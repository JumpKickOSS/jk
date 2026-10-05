// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * An npm registry on loopback: packages published here are served behind a bearer token, and a
 * package it does not hold can be mirrored from the public registry into a cache directory, so a
 * package manager's own tarball comes through it once and offline after.
 */
final class NpmRegistryStub implements AutoCloseable {

    private static final String UPSTREAM = "https://registry.npmjs.org/";
    private static final String MIRROR = "-/upstream/";
    private static final String PUBLISHED = "2020-01-01T00:00:00.000Z";

    /** One request: its path and the {@code Authorization} it carried. */
    record Request(String path, @Nullable String authorization) {}

    private record Version(String version, String manifest, byte[] tarball) {}

    private final HttpServer server;
    private final @Nullable String token;
    private final @Nullable Path mirror;
    private final Map<String, List<Version>> packages = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private NpmRegistryStub(@Nullable String token, @Nullable Path mirror) throws IOException {
        this.token = token;
        this.mirror = mirror;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    /** A registry whose published packages want {@code token}; {@code mirror} caches the public ones, or null for none. */
    static NpmRegistryStub start(@Nullable String token, @Nullable Path mirror) throws IOException {
        return new NpmRegistryStub(token, mirror);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    int port() {
        return server.getAddress().getPort();
    }

    /**
     * Publish {@code name@version} holding {@code files} under {@code package/}, with {@code bin}
     * (command → file) when not empty. The latest dist-tag follows the last version published.
     */
    synchronized NpmRegistryStub publish(
            String name, String version, Map<String, String> files, Map<String, String> bin) throws IOException {
        StringBuilder pkgJson = new StringBuilder("{\"name\":\"" + name + "\",\"version\":\"" + version + "\"");
        if (!bin.isEmpty()) pkgJson.append(",\"bin\":").append(object(bin));
        pkgJson.append('}');
        Map<String, String> all = new LinkedHashMap<>();
        all.put("package.json", pkgJson.toString());
        all.putAll(files);
        byte[] tarball = tarball(all);
        String file = name.substring(name.indexOf('/') + 1) + "-" + version + ".tgz";
        String manifest = pkgJson.substring(0, pkgJson.length() - 1)
                + ",\"dist\":{\"tarball\":\"" + base() + name + "/-/" + file + "\",\"integrity\":\""
                + integrity(tarball) + "\",\"shasum\":\"" + hex("SHA-1", tarball) + "\"}}";
        packages.computeIfAbsent(name, n -> new ArrayList<>()).add(new Version(version, manifest, tarball));
        return this;
    }

    synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    synchronized void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (this) {
                requests.add(new Request(path, auth));
            }
            String rest = path.substring(1);
            if (rest.startsWith(MIRROR)) {
                send(exchange, 200, upstream(rest.substring(MIRROR.length()), false));
                return;
            }
            byte[] body = published(rest);
            if (body != null) {
                boolean allowed = token == null || ("Bearer " + token).equals(auth);
                send(exchange, allowed ? 200 : 401, allowed ? body : new byte[0]);
                return;
            }
            if (mirror != null && !rest.contains("/-/")) {
                String packument = new String(upstream(rest, true), StandardCharsets.UTF_8);
                send(exchange, 200, packument.replace(UPSTREAM, base() + MIRROR).getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 404, new byte[0]);
        } catch (IOException | InterruptedException e) {
            send(exchange, 502, String.valueOf(e).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A published packument or tarball for {@code rest}, or null when this registry holds neither. */
    private synchronized byte @Nullable [] published(String rest) {
        int tar = rest.indexOf("/-/");
        String name = tar < 0 ? rest : rest.substring(0, tar);
        List<Version> versions = packages.get(name);
        if (versions == null) return null;
        if (tar >= 0) {
            String file = rest.substring(tar + 3);
            for (Version v : versions) {
                if (file.equals(name.substring(name.indexOf('/') + 1) + "-" + v.version() + ".tgz")) return v.tarball();
            }
            return null;
        }
        // Publish times long past: a manager with a minimum-age gate (Yarn Berry) quarantines a version without one.
        StringBuilder doc = new StringBuilder("{\"name\":\"" + name + "\",\"dist-tags\":{\"latest\":\""
                + versions.get(versions.size() - 1).version() + "\"},\"time\":{\"created\":\"" + PUBLISHED + "\"");
        for (Version v : versions)
            doc.append(",\"")
                    .append(v.version())
                    .append("\":\"")
                    .append(PUBLISHED)
                    .append('"');
        doc.append("},\"versions\":{");
        for (int i = 0; i < versions.size(); i++) {
            if (i > 0) doc.append(',');
            doc.append('"')
                    .append(versions.get(i).version())
                    .append("\":")
                    .append(versions.get(i).manifest());
        }
        return doc.append("}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code rest} from the public registry, kept under the mirror directory after the first fetch. */
    private byte[] upstream(String rest, boolean packument) throws IOException, InterruptedException {
        Path dir = Objects.requireNonNull(mirror, "mirror");
        Path cached = dir.resolve(packument ? "packuments" : "tarballs")
                .resolve(rest.replace("/", "__").replace("@", "at_") + (packument ? ".json" : ""));
        if (Files.isRegularFile(cached)) return Files.readAllBytes(cached);
        String encoded = packument ? rest.replace("/", "%2f") : rest;
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(UPSTREAM + encoded))
                        .timeout(Duration.ofMinutes(5))
                        .header("Accept", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException(rest + ": upstream HTTP " + response.statusCode());
        Files.createDirectories(cached.getParent());
        Path part = cached.resolveSibling(cached.getFileName() + ".part");
        Files.write(part, response.body());
        Files.move(part, cached, StandardCopyOption.REPLACE_EXISTING);
        return response.body();
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }

    private static String object(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(k).append("\":\"").append(v).append('"');
        });
        return sb.append('}').toString();
    }

    static String integrity(byte[] bytes) {
        return "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", bytes));
    }

    private static String hex(String algorithm, byte[] bytes) {
        return HexFormat.of().formatHex(digest(algorithm, bytes));
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A gzipped ustar archive of {@code files} under {@code package/}. */
    static byte[] tarball(Map<String, String> files) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Map.Entry<String, String> f : files.entrySet()) entry(tar, "package/" + f.getKey(), f.getValue());
        tar.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void entry(ByteArrayOutputStream tar, String name, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, "0000755");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", body.length));
        put(header, 136, String.format("%011o", 0));
        for (int i = 148; i < 156; i++) header[i] = ' ';
        header[156] = '0';
        put(header, 257, "ustar");
        put(header, 263, "00");
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        put(header, 148, String.format("%06o", sum));
        header[154] = 0;
        header[155] = ' ';
        tar.write(header);
        tar.write(body);
        tar.write(new byte[(512 - body.length % 512) % 512]);
    }

    private static void put(byte[] header, int at, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, at, bytes.length);
    }
}
