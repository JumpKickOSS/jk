// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo.s3;

import cc.jumpkick.host.Hashing;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC Signature Version 4 for Amazon S3 and Google Cloud Storage.
 */
public final class SigV4Signer {

    enum Dialect {
        AWS("AWS4", "aws4_request", "x-amz-"),
        GOOGLE("GOOG4", "goog4_request", "x-goog-");

        private final String keyPrefix;
        private final String requestType;
        final String headerPrefix;

        Dialect(String keyPrefix, String requestType, String headerPrefix) {
            this.keyPrefix = keyPrefix;
            this.requestType = requestType;
            this.headerPrefix = headerPrefix;
        }
    }

    private SigV4Signer() {}

    /**
     * Compute the {@code Authorization} header value for a request.
     *
     * @param dialect signing algorithm and credential scope
     * @param method HTTP method (GET, PUT, …)
     * @param uri full request URI (host taken from it; path/query signed)
     * @param signedHeaders headers to include in the signature — MUST contain {@code host}; callers
     *     typically also pass {@code x-amz-date} and {@code x-amz-content-sha256}. Header names are
     *     treated case-insensitively.
     * @param payloadHashHex hex SHA-256 of the body (use {@link #sha256Hex} or {@code
     *     "UNSIGNED-PAYLOAD"})
     * @param accessKeyId AWS access key id
     * @param secretKey AWS secret access key
     * @param region e.g. {@code us-east-1}
     * @param service e.g. {@code s3}
     * @param amzDate ISO basic timestamp {@code yyyyMMdd'T'HHmmss'Z'}
     */
    static String authorization(
            Dialect dialect,
            String method,
            URI uri,
            Map<String, String> signedHeaders,
            String payloadHashHex,
            String accessKeyId,
            String secretKey,
            String region,
            String service,
            String amzDate) {
        // Normalise headers: lowercase names, trimmed values, sorted.
        TreeMap<String, String> headers = new TreeMap<>();
        for (Map.Entry<String, String> e : signedHeaders.entrySet()) {
            headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().trim());
        }

        String canonicalHeaders = headers.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .reduce("", String::concat);
        String signedHeaderNames = String.join(";", headers.keySet());

        String canonicalRequest = method
                + "\n"
                + canonicalUri(uri)
                + "\n"
                + canonicalQuery(uri)
                + "\n"
                + canonicalHeaders
                + "\n"
                + signedHeaderNames
                + "\n"
                + payloadHashHex;

        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + region + "/" + service + "/" + dialect.requestType;
        String algorithm = dialect.keyPrefix + "-HMAC-SHA256";
        String stringToSign = algorithm
                + "\n"
                + amzDate
                + "\n"
                + scope
                + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = signingKey(dialect, secretKey, dateStamp, region, service);
        String signature = Hashing.hex(hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        return algorithm
                + " Credential="
                + accessKeyId
                + "/"
                + scope
                + ", SignedHeaders="
                + signedHeaderNames
                + ", Signature="
                + signature;
    }

    /**
     * Canonical URI: the path with each segment RFC3986-encoded; {@code /} preserved; empty → "/".
     */
    static String canonicalUri(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.isEmpty()) return "/";
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(rfc3986(segments[i]));
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    /**
     * Canonical query string: params sorted by encoded key, {@code key=value} joined by {@code &}.
     */
    static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return "";
        TreeMap<String, String> params = new TreeMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            // Re-encode from the decoded form to get canonical RFC3986 encoding.
            params.put(rfc3986(urlDecode(k)), rfc3986(urlDecode(v)));
        }
        StringJoiner sj = new StringJoiner("&");
        params.forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }

    public static String sha256Hex(byte[] data) {
        return Hashing.sha256Hex(data);
    }

    private static byte[] signingKey(
            Dialect dialect, String secretKey, String dateStamp, String region, String service) {
        byte[] kDate = hmac(
                (dialect.keyPrefix + secretKey).getBytes(StandardCharsets.UTF_8),
                dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmac(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmac(kService, dialect.requestType.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** RFC 3986 unreserved set is left as-is; everything else percent-encoded (uppercase hex). */
    static String rfc3986(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '~') {
                out.append(c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
