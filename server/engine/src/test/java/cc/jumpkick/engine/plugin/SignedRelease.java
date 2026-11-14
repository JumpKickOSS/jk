// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.ReleaseRepoDigests;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * A release directory ({@code releases/<version>/}) served from a {@link LoopbackHttp}: the digest
 * list of the repository files a test names, the {@code SHA256SUMS} over it and a signature by a
 * throwaway key, which {@link #trust} makes the test home's {@code [release] trusted-keys}.
 */
final class SignedRelease {

    private static final KeyPair KEY = generate();

    private SignedRelease() {}

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Write {@code home}'s {@code config.toml} trusting the throwaway key, after {@code extra}. */
    static void trust(Path home, String extra) throws IOException {
        String spki = Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded());
        Files.writeString(home.resolve("config.toml"), extra + "\n[release]\ntrusted-keys = \"" + spki + "\"\n");
    }

    /** The releases root on {@code http}: what {@code JK_RELEASES_URL} names. */
    static String releasesUrl(LoopbackHttp http) {
        return http.base() + "/releases";
    }

    /**
     * Publish {@code version}'s release on {@code http}, listing the digests of {@code repoFiles}
     * (repository-relative path to bytes), signed.
     */
    static void publish(LoopbackHttp http, String version, Map<String, byte[]> repoFiles) {
        StringBuilder list = new StringBuilder();
        new TreeMap<>(repoFiles).forEach((path, bytes) -> list.append(Hashing.sha256Hex(bytes))
                .append("  ")
                .append(path)
                .append('\n'));
        byte[] digests = list.toString().getBytes(StandardCharsets.UTF_8);
        String name = ReleaseRepoDigests.fileName(version);
        byte[] sums = (Hashing.sha256Hex(digests) + "  " + name + "\n").getBytes(StandardCharsets.UTF_8);
        String dir = "/releases/" + version + "/";
        http.served().put(dir + name, digests);
        http.served().put(dir + "SHA256SUMS", sums);
        http.served().put(dir + "SHA256SUMS.sig", sign(sums).getBytes(StandardCharsets.US_ASCII));
    }

    /** Publish the release listing every file {@code http} serves under {@code cc/jumpkick/}, as served now. */
    static void publishServed(LoopbackHttp http, String version) {
        Map<String, byte[]> files = new TreeMap<>();
        http.served().forEach((path, bytes) -> {
            if (path.startsWith("/cc/jumpkick/")) files.put(path.substring(1), bytes);
        });
        publish(http, version, files);
    }

    private static String sign(byte[] bytes) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(KEY.getPrivate());
            signer.update(bytes);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
