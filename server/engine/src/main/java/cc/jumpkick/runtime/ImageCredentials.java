// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.repo.RepoCredentialResolver;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * The registry credentials one image build needs, resolved by the engine and handed to the worker
 * on the spec file's {@code secret} lines.
 *
 * <p>Two of them, because an image build reads one registry and may write another: the base image
 * is pulled in <em>every</em> mode (tarball, daemon, push), so a private base breaks a plain
 * {@code jk build} long before anyone asks for a push. Both are resolved here for the same reason
 * {@code jk publish} resolves its repository credential outside the worker — the resolution order
 * (inline → env → {@code jk repo login} → {@code settings.xml} → forge token) lives in one place,
 * and {@link RepoCredentialResolver} files whatever it returns with the redactor, so the value is
 * masked in wire events, the journal and cache keys before anything can print it.
 *
 * <p>The lookup id is the registry <em>host</em> — {@code ghcr.io}, {@code 127.0.0.1:5000} — so
 * {@code jk repo login ghcr.io}, {@code JK_REPO_GHCR_IO_TOKEN} and a {@code settings.xml} server
 * of that name all reach it with no new credential store and no new config key.
 *
 * <p>Nothing here ever reaches argv: the worker is forked with the spec path alone, and the spec
 * file is created owner-only ({@link #newSpecFile}). Process arguments are world-readable in
 * {@code /proc}, which is the bug [[]] fixed for keystore passwords.
 */
final class ImageCredentials {

    private ImageCredentials() {}

    /**
     * Write the pull and push credentials for this build into {@code sw}. {@code push} is false in
     * tarball and daemon mode: there is no second registry to authenticate against, and resolving
     * one would file a credential the build never uses.
     */
    static void write(SpecWriter sw, String baseRef, ImageConfig config, JkBuild project, boolean push, Path dir) {
        credential(sw, "base", baseRef, dir);
        if (!push) return;
        credential(
                sw,
                "push",
                config.targetReference(
                        project.project().name(), project.project().version()),
                dir);
    }

    private static void credential(SpecWriter sw, String prefix, String reference, Path moduleDir) {
        switch (resolve(reference, moduleDir)) {
            case RepoCredential.Basic b ->
                sw.configString(prefix + "AuthType", "basic")
                        .secret(prefix + "User", b.username())
                        .secret(prefix + "Pass", b.password());
            case RepoCredential.Bearer b ->
                sw.configString(prefix + "AuthType", "bearer").secret(prefix + "Token", b.token());
            case RepoCredential.Anonymous ignored -> sw.configString(prefix + "AuthType", "anonymous");
        }
    }

    /** The credential for the registry {@code reference} names, or anonymous when none is stored. */
    static RepoCredential resolve(String reference, Path moduleDir) {
        if (reference == null || reference.isBlank()) return RepoCredential.ANONYMOUS;
        String host = BaseImageDigest.parse(reference).registry();
        return RepoCredentialResolver.withEnv(BuildEnv.forModule(moduleDir))
                .resolve(host, registryUrl(host), Optional.empty());
    }

    /**
     * {@code https://<host>}, which is what the forge-token bridge matches on. Null when the host
     * is not a URI authority: a malformed base image belongs to Jib's error message, not to a
     * {@code URISyntaxException} thrown while writing a spec.
     */
    private static URI registryUrl(String host) {
        try {
            return URI.create("https://" + host);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * An owner-only spec file. The secrets above are in it in the clear — a default-permission temp
     * file hands every local account the registry password, the same reason {@code jk publish}
     * writes its spec 0600.
     */
    static Path newSpecFile() throws IOException {
        try {
            return Files.createTempFile(
                    "jk-image-",
                    ".spec",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile("jk-image-", ".spec"); // non-POSIX filesystem (Windows)
        }
    }
}
