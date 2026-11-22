// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.updates;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The update manifest ({@code updates.json}): when it was published and, per kind of data, the
 * version it names and the file and SHA-256 to fetch it by. It is signed once, by the release key,
 * in {@code updates.json.sig} beside it (base64 RSA/SHA-256 over the manifest's exact bytes); every
 * file it lists is trusted only when its bytes match the SHA-256 the manifest gives.
 *
 * <pre>{@code
 * {
 *   "published": "2026-11-22T04:00:00Z",
 *   "jk": {"stable": {"version": "0.60.0", "sha256": "<64 hex>"}},
 *   "data": {
 *     "prices":    {"version": "2026-11-21T18:00:00Z", "file": "prices.toml", "sha256": "<64 hex>"},
 *     "templates": {"version": "<jk-templates commit>"}
 *   }
 * }
 * }</pre>
 *
 * A {@code file} is a relative path under the manifest's own directory, so a mirror of the manifest
 * serves the files too. {@code jk} names the current JumpKick release of each channel and the
 * SHA-256 of that release's {@code SHA256SUMS}, which lists every artifact's own checksum. Keys,
 * kinds and channels a JumpKick does not know are skipped, and so is a channel it cannot read, so
 * an older one reads a newer manifest.
 */
public record UpdateManifest(Instant published, Map<String, Release> jk, Map<String, Entry> data) {

    /** The manifest's file name, beside the files it lists and in {@link UpdateState#dir()}. */
    public static final String FILE = "updates.json";

    /** What the signature file's name adds to the manifest's. */
    public static final String SIGNATURE_SUFFIX = ".sig";

    /** The release channel this JumpKick follows. */
    public static final String CHANNEL = "stable";

    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RELEASE =
            Pattern.compile("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(-[A-Za-z0-9.]{1,32})?");

    public UpdateManifest {
        jk = Map.copyOf(jk);
        data = Map.copyOf(data);
    }

    /**
     * A channel's current JumpKick: its version and the SHA-256 of its {@code SHA256SUMS}.
     */
    public record Release(String version, String sha256) {

        /** Refuse a release's {@code SHA256SUMS} unless they hash to this release's SHA-256. */
        public void verifySums(byte[] sums) throws IOException {
            if (!sha256.equals(Hashing.sha256Hex(sums))) {
                throw new IOException("jk " + version + " SHA256SUMS does not match the update manifest's sha256");
            }
        }
    }

    /**
     * One kind's current copy. {@code version} is what the engine compares with the version it last
     * applied; {@code file} and {@code sha256} are both set or both null (a templates revision is
     * fetched by git, and the commit id is its own checksum).
     */
    public record Entry(
            String version, @Nullable String file, @Nullable String sha256) {

        /** Refuse {@code bytes} unless they hash to this entry's SHA-256. */
        public void verify(byte[] bytes) throws IOException {
            if (sha256 == null || !sha256.equals(Hashing.sha256Hex(bytes))) {
                throw new IOException("update file " + file + " does not match the manifest's sha256");
            }
        }
    }

    /** The entry for {@code kind}, or null. */
    public @Nullable Entry entry(String kind) {
        return data.get(kind);
    }

    /** The JumpKick release {@link #CHANNEL} names, or empty when the manifest names none. */
    public Optional<Release> release() {
        return Optional.ofNullable(jk.get(CHANNEL));
    }

    /**
     * The manifest the engine's update check holds in {@link UpdateState#dir()}, or empty when there
     * is none or it does not parse. Only a manifest whose signature verified is ever written there.
     */
    public static Optional<UpdateManifest> held() {
        return held(UpdateState.dir());
    }

    /** {@link #held()} under {@code dir}. */
    public static Optional<UpdateManifest> held(Path dir) {
        Path file = dir.resolve(FILE);
        try {
            return Files.isRegularFile(file) ? Optional.of(parse(Files.readAllBytes(file))) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** {@code json} once {@code signature} (the {@code .sig} file's bytes) verifies over it, parsed. */
    public static UpdateManifest verified(byte[] json, byte[] signature, ReleaseVerifier verifier) throws IOException {
        verifier.verify(json, new String(signature, StandardCharsets.US_ASCII));
        return parse(json);
    }

    /** Parse a manifest's bytes; throws when it is not a manifest. */
    public static UpdateManifest parse(byte[] json) throws IOException {
        Object root;
        try {
            root = MiniJson.parse(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("update manifest is not JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?>)) throw new IOException("update manifest is not a JSON object");
        Instant published = instant(MiniJson.str(root, "published"));
        Map<String, Entry> data = new LinkedHashMap<>();
        Object table = MiniJson.get(root, "data");
        if (table != null && !(table instanceof Map<?, ?>))
            throw new IOException("update manifest's data is not an object");
        if (table instanceof Map<?, ?> kinds) {
            for (Map.Entry<?, ?> e : kinds.entrySet()) {
                String kind = String.valueOf(e.getKey());
                if (!KIND.matcher(kind).matches()) throw new IOException("update manifest names a bad kind: " + kind);
                data.put(kind, entry(kind, e.getValue()));
            }
        }
        return new UpdateManifest(published, releases(MiniJson.get(root, "jk")), data);
    }

    /** The readable channels of a {@code jk} table; anything else in it is skipped. */
    private static Map<String, Release> releases(@Nullable Object table) {
        Map<String, Release> out = new LinkedHashMap<>();
        if (!(table instanceof Map<?, ?> channels)) return out;
        channels.forEach((name, node) -> {
            String version = MiniJson.str(node, "version");
            String sha256 = MiniJson.str(node, "sha256");
            if (KIND.matcher(String.valueOf(name)).matches()
                    && version != null
                    && RELEASE.matcher(version).matches()
                    && !Versions.isSnapshot(version)
                    && sha256 != null
                    && SHA256.matcher(sha256).matches()) {
                out.put(String.valueOf(name), new Release(version, sha256));
            }
        });
        return out;
    }

    private static Entry entry(String kind, @Nullable Object node) throws IOException {
        if (!(node instanceof Map<?, ?>)) throw new IOException("update manifest's " + kind + " is not an object");
        String version = MiniJson.str(node, "version");
        if (version == null || version.isBlank() || version.length() > 128) {
            throw new IOException("update manifest's " + kind + " has no version");
        }
        String file = MiniJson.str(node, "file");
        String sha256 = MiniJson.str(node, "sha256");
        if ((file == null) != (sha256 == null)) {
            throw new IOException("update manifest's " + kind + " needs both file and sha256, or neither");
        }
        if (file != null && !relative(file)) {
            throw new IOException("update manifest's " + kind + " file is not a relative path: " + file);
        }
        if (sha256 != null && !SHA256.matcher(sha256).matches()) {
            throw new IOException("update manifest's " + kind + " sha256 is not 64 lowercase hex digits");
        }
        return new Entry(version, file, sha256);
    }

    /** Whether {@code file} is a plain relative path: segments of safe characters, none of them a dot path. */
    private static boolean relative(String file) {
        String[] segments = file.split("/", -1);
        if (segments.length > 8) return false;
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) return false;
        }
        return true;
    }

    private static Instant instant(@Nullable String text) throws IOException {
        if (text == null) throw new IOException("update manifest has no published time");
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IOException("update manifest's published time is not an ISO instant: " + text, e);
        }
    }
}
