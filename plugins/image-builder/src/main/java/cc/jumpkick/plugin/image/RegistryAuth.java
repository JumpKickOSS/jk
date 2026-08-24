// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.credential.RepoCredential;
import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.JibContainer;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.global.JibSystemProperties;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * The credential behind every registry this plugin touches, and the only way it names a {@link
 * RegistryImage}.
 *
 * <p>Jib attaches none by itself: {@code RegistryImage.named(ref)} starts with an empty retriever
 * list and the caller opts in. Nothing here ever did, so every base-image pull — in tarball, daemon
 * <em>and</em> push mode — and every push was anonymous, and no private registry could be reached
 * at all (JK-2464). Constructing a {@code RegistryImage} anywhere but here is how that comes back,
 * so the three sites that had one — the build's base pull, the base-JRE extraction {@code aot-cache}
 * performs, and the push target — go through {@link #base} and {@link #target} instead, and a test
 * fails if a fourth appears.
 *
 * <p>Order is jk's own resolved credential first, then the Docker credential store
 * ({@code ~/.docker/config.json}, its {@code credHelpers}, and the well-known cloud helpers) — the
 * same order Jib's own Maven and Gradle front-ends use, so an existing {@code docker login} keeps
 * working and an explicit {@code jk repo login <registry>} wins over it.
 *
 * <p>jk's half arrives through the spec file's {@code secret} lines and never through argv: argv is
 * world-readable in {@code /proc}, which is the bug [[JK-2406]] fixed for keystore passwords.
 */
public final class RegistryAuth {

    /** No jk-resolved credential: the Docker credential store alone, over HTTPS only. */
    public static final RegistryAuth NONE = new RegistryAuth(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, false);

    private final RepoCredential basePull;
    private final RepoCredential push;
    private final boolean plainHttp;

    private RegistryAuth(RepoCredential basePull, RepoCredential push, boolean plainHttp) {
        this.basePull = basePull;
        this.push = push;
        this.plainHttp = plainHttp;
    }

    /**
     * Credentials for one image build. {@code pushRef} is the registry reference the build pushes,
     * or null in tarball and daemon mode — where the base pull is still a registry read, and still
     * needs {@code basePull}.
     */
    public static RegistryAuth of(RepoCredential basePull, RepoCredential push, String baseRef, String pushRef) {
        // Plain HTTP only when EVERY registry contacted is loopback. Both switches below are
        // wider than one reference — the containerizer flag covers the whole build and the
        // credentials-over-HTTP property is JVM-wide — so one public registry in the mix means
        // HTTPS for all of them, rather than a credential leaving the machine in the clear.
        boolean loopback = loopback(baseRef) && (pushRef == null || loopback(pushRef));
        return new RegistryAuth(
                basePull == null ? RepoCredential.ANONYMOUS : basePull,
                push == null ? RepoCredential.ANONYMOUS : push,
                loopback);
    }

    /** The base image to build on, authenticated for a pull. */
    public RegistryImage base(String reference) throws InvalidImageReferenceException {
        return named(reference, basePull);
    }

    /** The push target, authenticated for a write. */
    public RegistryImage target(String reference) throws InvalidImageReferenceException {
        return named(reference, push);
    }

    private static RegistryImage named(String reference, RepoCredential credential)
            throws InvalidImageReferenceException {
        ImageReference parsed = ImageReference.parse(reference);
        RegistryImage image = RegistryImage.named(parsed);
        CredentialRetrieverFactory retrievers = CredentialRetrieverFactory.forImage(parsed, RegistryAuth::log);
        Credential resolved = jibCredential(credential);
        if (resolved != null) image.addCredentialRetriever(retrievers.known(resolved, "jk"));
        image.addCredentialRetriever(retrievers.dockerConfig());
        image.addCredentialRetriever(retrievers.wellKnownCredentialHelpers());
        return image;
    }

    /**
     * jk's credential in Jib's shape, or null for anonymous.
     *
     * <p>A registry's token endpoint takes the credential as HTTP Basic, so there is no bearer slot
     * to put a {@link RepoCredential.Bearer} in; it rides as the password. The user name every
     * registry that issues opaque tokens ignores is spelled {@code jk} rather than Jib's
     * {@link Credential#OAUTH2_TOKEN_USER_NAME}, which would send jk down Google's refresh-token
     * grant instead. A registry that does read the user name (Docker Hub, a Harbor robot account)
     * needs {@code jk repo login <registry> --username <user>}, which resolves to Basic.
     */
    private static Credential jibCredential(RepoCredential credential) {
        return switch (credential) {
            case RepoCredential.Basic b -> Credential.from(b.username(), b.password());
            case RepoCredential.Bearer b -> Credential.from("jk", b.token());
            case RepoCredential.Anonymous ignored -> null;
        };
    }

    /**
     * Build {@code builder} into {@code containerizer}, with the loopback allowance applied.
     *
     * <p>Jib speaks HTTPS only and refuses to send an {@code Authorization} header over HTTP unless
     * told twice — once per containerizer, once per JVM. The property is restored afterwards: this
     * runs in a single-purpose worker in production, but in a test JVM leaving it set would let a
     * later test send a credential in the clear.
     */
    JibContainer containerize(JibContainerBuilder builder, Containerizer containerizer)
            throws IOException, InterruptedException, RegistryException, ExecutionException,
                    CacheDirectoryCreationException {
        containerizer.setAllowInsecureRegistries(plainHttp);
        if (!plainHttp) return builder.containerize(containerizer);
        String prior = System.getProperty(JibSystemProperties.SEND_CREDENTIALS_OVER_HTTP);
        System.setProperty(JibSystemProperties.SEND_CREDENTIALS_OVER_HTTP, "true");
        try {
            return builder.containerize(containerizer);
        } finally {
            if (prior == null) {
                System.clearProperty(JibSystemProperties.SEND_CREDENTIALS_OVER_HTTP);
            } else {
                System.setProperty(JibSystemProperties.SEND_CREDENTIALS_OVER_HTTP, prior);
            }
        }
    }

    /**
     * True when {@code reference} names a registry on the loopback interface. Docker and podman
     * both ship {@code localhost} in their default insecure-registry list, and jk's own manifest
     * probe already reads a loopback registry over {@code http://} — the worker was the one place
     * that could not.
     */
    static boolean loopback(String reference) {
        if (reference == null || reference.isBlank()) return false;
        try {
            String host = ImageReference.parse(reference).getRegistry();
            int colon = host.lastIndexOf(':');
            String name = colon < 0 ? host : host.substring(0, colon);
            return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("[::1]");
        } catch (InvalidImageReferenceException e) {
            return false; // an unparseable reference fails later, with a better message than this
        }
    }

    /**
     * Jib's retriever chatter. Only warnings and errors are surfaced — "no credential helper for X"
     * on every miss would bury the build — and none of it carries a credential value: Jib logs
     * which source answered, never what it answered with.
     */
    private static void log(LogEvent event) {
        if (event.getLevel() == LogEvent.Level.WARN || event.getLevel() == LogEvent.Level.ERROR) {
            System.err.println("jk: " + event.getMessage());
        }
    }
}
