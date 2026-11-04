// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.MinimalXml;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Maven 4's encrypted {@code settings.xml} passwords, as {@code mvnenc encrypt} writes them, read
 * so a Maven 4 user's working file keeps working. Compatibility, not protection, like {@link
 * MavenPasswords}: jk reads the format and never writes it.
 *
 * <p>The format is plexus-sec-dispatcher 4's ({@code DefaultSecDispatcher}, {@code
 * MasterDispatcher}, {@code MasterSourceLookupDispatcher}, {@code AESGCMNoPadding} and the master
 * sources under {@code org.codehaus.plexus.components.secdispatcher.internal}, release 4.2.0, the
 * one Maven 4.0.0-rc-7 ships). A value is encrypted when it is a single {@code {...}}, with no
 * brace inside, that opens with an attribute list naming at least a dispatcher and a version:
 * {@code {[name=master,cipher=AES/GCM/NoPadding,version=4.2]<base64>}}. The dispatchers:
 *
 * <ul>
 * <li>{@code master}: the base64 is {@code iv[12] || salt[16] || ciphertext-and-tag}, AES/GCM with
 * a 128-bit tag, under a 256-bit key that PBKDF2-HMAC-SHA512 derives from the master password and
 * the salt in 310,000 iterations. The master password comes from the {@code source} property of
 * the {@code master} configuration in {@code ~/.m2/settings-security4.xml}, or the file {@code
 * JK_M2_SETTINGS_SECURITY4} names — Maven's {@code -Dmaven.settings.security}.
 * <li>{@code masterSourceLookup}: the payload is itself a master source, and its value is the
 * password.
 * <li>{@code legacy}: Maven 3's format, with Maven 3's master ({@link MavenPasswords}).
 * </ul>
 *
 * <p>A master source is read only when it needs no person: {@code env:NAME} (the request's shell,
 * like every {@code ${env.NAME}}), {@code file:/absolute/path} (its first line not starting with
 * {@code #}), and {@code gpg-agent:socket} — always asked with {@code --no-ask}, so only a
 * passphrase the agent already holds is used, whatever the configuration says. The sources that
 * ask someone ({@code console-prompt}, {@code pinentry-prompt:}, {@code onepassword:}) and {@code
 * system-property:}, which names a property of Maven's own JVM, are reported, not read: jk never
 * prompts, and headless is the normal case.
 */
final class MvnencPasswords {

    private MvnencPasswords() {}

    /** Where Maven 4 keeps the dispatcher configuration, under {@code ~/.m2}. */
    static final String SECURITY_FILE = "settings-security4.xml";

    /** The environment variable naming the Maven 4 security file: Maven's {@code -Dmaven.settings.security} for jk. */
    static final String SECURITY_ENV = "JK_M2_SETTINGS_SECURITY4";

    /** The cache id Maven's gpg-agent source asks the agent for. */
    static final String GPG_CACHE_ID = "plexus:secDispatcherMasterPassword";

    private static final String MASTER = "master";
    private static final String LOOKUP = "masterSourceLookup";
    private static final String LEGACY = "legacy";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV = 12;
    private static final int SALT = 16;
    private static final int TAG_BITS = 128;
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;

    /** How long the agent gets to answer before the value is given up as undecryptable. */
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(5);

    /** What decrypting a value needs from the request: its shell, its home, and a log of what was read. */
    interface Request {

        /** The environment variable {@code name} as the request's shell has it; null when unset. */
        @Nullable
        String env(String name);

        /** The home directory, {@code ~}. */
        Path home() throws MavenPasswords.Undecryptable;

        /** {@code settings-security4.xml}: the file {@link #SECURITY_ENV} names, else the one under {@code ~/.m2}. */
        Path securityFile() throws MavenPasswords.Undecryptable;

        /** {@code file} was read, so a change to it invalidates the expansion. */
        void read(Path file);

        /** What was read can change without any file changing, so the expansion is not reused. */
        void unstable();

        /** Maven 3's master password, for a value the {@code legacy} dispatcher wrote. */
        String legacyMaster() throws MavenPasswords.Undecryptable;
    }

    /** Whether {@code value} is in Maven 4's format: one brace pair, with a dispatcher name and version first. */
    static boolean encrypted(String value) {
        if (value.length() < 2 || !value.startsWith("{") || !value.endsWith("}")) return false;
        String inside = value.substring(1, value.length() - 1);
        if (inside.indexOf('{') >= 0 || inside.indexOf('}') >= 0) return false;
        Map<String, String> attributes = attributes(inside);
        return attributes != null && attributes.containsKey("name") && attributes.containsKey("version");
    }

    /**
     * The attributes of {@code inside} ({@code [k=v,…]} at its start); null when there is no list
     * or it is malformed.
     */
    private static @Nullable Map<String, String> attributes(String inside) {
        if (!inside.startsWith("[")) return null;
        int stop = inside.indexOf(']');
        if (stop < 0) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : inside.substring(1, stop).split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) return null;
            out.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
        }
        return out;
    }

    /**
     * {@code value}, which {@link #encrypted} accepts, decrypted for {@code request}.
     *
     * @throws MavenPasswords.Undecryptable with the reason, never the value or a master password
     */
    static String decrypt(String value, Request request) throws MavenPasswords.Undecryptable {
        String inside = value.substring(1, value.length() - 1);
        Map<String, String> attributes = Objects.requireNonNull(attributes(inside));
        String payload = inside.substring(inside.indexOf(']') + 1);
        String dispatcher = attributes.getOrDefault("name", "");
        return switch (dispatcher) {
            case MASTER -> {
                String cipher = attributes.get("cipher");
                if (!AES_GCM.equals(cipher)) {
                    throw new MavenPasswords.Undecryptable(
                            "it names the cipher `" + cipher + "`, and Maven 4 writes " + AES_GCM);
                }
                yield aesGcm(payload, master(request));
            }
            case LOOKUP -> source(payload, request);
            case LEGACY -> MavenPasswords.decrypt(payload, request.legacyMaster());
            default ->
                throw new MavenPasswords.Undecryptable("it names the dispatcher `" + dispatcher
                        + "`, which only a Maven extension provides; jk reads master, masterSourceLookup and legacy");
        };
    }

    /** {@code payload} (base64 of IV, salt and ciphertext) decrypted under {@code password}. */
    static String aesGcm(String payload, String password) throws MavenPasswords.Undecryptable {
        byte[] material;
        try {
            material = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException notBase64) {
            throw new MavenPasswords.Undecryptable("it is not base64");
        }
        if (material.length <= IV + SALT) throw new MavenPasswords.Undecryptable("it is too short to be encrypted");
        ByteBuffer buffer = ByteBuffer.wrap(material);
        byte[] iv = new byte[IV];
        byte[] salt = new byte[SALT];
        buffer.get(iv).get(salt);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            byte[] key;
            try {
                key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                        .generateSecret(spec)
                        .getEncoded();
            } finally {
                spec.clearPassword();
            }
            Cipher aes = Cipher.getInstance(AES_GCM);
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(aes.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException wrongKey) {
            throw new MavenPasswords.Undecryptable("it does not decrypt with the master password");
        }
    }

    /** The master password the {@code master} configuration's {@code source} names. */
    private static String master(Request request) throws MavenPasswords.Undecryptable {
        Path file = request.securityFile();
        request.read(file);
        if (!Files.isRegularFile(file)) {
            throw new MavenPasswords.Undecryptable("there is no " + file + " to name its master password source");
        }
        MinimalXml.Element root;
        try {
            root = MinimalXml.parse(Files.readString(file));
        } catch (IOException | RuntimeException unreadable) {
            throw new MavenPasswords.Undecryptable(file + " does not parse");
        }
        String source = property(root, MASTER, "source");
        if (source == null) {
            throw new MavenPasswords.Undecryptable(file + " has no `source` for the master dispatcher");
        }
        return source(source, request);
    }

    /** The {@code name} property of the configuration {@code configuration}; null when absent. */
    private static @Nullable String property(MinimalXml.Element root, String configuration, String name) {
        for (MinimalXml.Element config : root.element("configurations")
                .map(c -> c.elements("configuration"))
                .orElse(List.of())) {
            if (!configuration.equals(text(config, "name"))) continue;
            for (MinimalXml.Element property : config.element("properties")
                    .map(p -> p.elements("property"))
                    .orElse(List.of())) {
                if (name.equals(text(property, "name"))) return text(property, "value");
            }
        }
        return null;
    }

    private static @Nullable String text(MinimalXml.Element parent, String tag) {
        return parent.element(tag)
                .map(MinimalXml.Element::text)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .orElse(null);
    }

    /** The value of the master source {@code source} ({@code env:NAME}, {@code file:…}, {@code gpg-agent:…}). */
    static String source(String source, Request request) throws MavenPasswords.Undecryptable {
        int colon = source.indexOf(':');
        String kind = colon < 0 ? source : source.substring(0, colon);
        String rest = colon < 0 ? "" : source.substring(colon + 1);
        return switch (kind) {
            case "env" -> {
                String value = request.env(rest);
                if (value == null) {
                    throw new MavenPasswords.Undecryptable("its master password source is " + source + ", and " + rest
                            + " is not set in the shell that ran jk");
                }
                yield value;
            }
            case "file" -> masterFile(rest, request);
            case "gpg-agent" -> gpgAgent(rest, request);
            case "console-prompt", "pinentry-prompt", "onepassword" ->
                throw new MavenPasswords.Undecryptable("its master password source is `" + kind
                        + "`, which asks for the password interactively, and jk never prompts; name the master"
                        + " password with env:, file: or gpg-agent: in " + SECURITY_FILE);
            case "system-property" ->
                throw new MavenPasswords.Undecryptable("its master password source is a system property of Maven's"
                        + " own JVM, which jk does not have; name the master password with env: or file: in "
                        + SECURITY_FILE);
            default ->
                throw new MavenPasswords.Undecryptable(
                        "its master password source `" + kind + "` is not one jk reads (env:, file:, gpg-agent:)");
        };
    }

    /** {@code file:} — an absolute path whose first line not starting with {@code #} is the password. */
    private static String masterFile(String written, Request request) throws MavenPasswords.Undecryptable {
        Path file;
        try {
            file = Path.of(written);
        } catch (InvalidPathException notAPath) {
            throw new MavenPasswords.Undecryptable("its master password source file:" + written + " is not a path");
        }
        if (!file.isAbsolute()) {
            throw new MavenPasswords.Undecryptable(
                    "its master password source file:" + written + " is not an absolute path, which Maven requires");
        }
        request.read(file);
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.startsWith("#")) return line.strip();
            }
        } catch (IOException unreadable) {
            throw new MavenPasswords.Undecryptable("its master password file " + file + " cannot be read");
        }
        throw new MavenPasswords.Undecryptable("its master password file " + file + " holds no password");
    }

    /**
     * {@code gpg-agent:socket[?options]}: the passphrase the agent caches under {@link
     * #GPG_CACHE_ID}, asked with {@code --no-ask} so the agent never starts a pinentry. A relative
     * socket path is under the home directory, as in Maven.
     */
    private static String gpgAgent(String written, Request request) throws MavenPasswords.Undecryptable {
        request.unstable();
        int query = written.indexOf('?');
        String path = query < 0 ? written : written.substring(0, query);
        Path socket;
        try {
            socket = Path.of(path);
            if (!socket.isAbsolute()) socket = request.home().resolve(socket);
        } catch (InvalidPathException notAPath) {
            throw new MavenPasswords.Undecryptable("its gpg-agent socket " + path + " is not a path");
        }
        String answer;
        try {
            answer = askAgent(socket);
        } catch (IOException e) {
            throw new MavenPasswords.Undecryptable(
                    "gpg-agent did not answer on " + socket + " (" + e.getMessage() + ")");
        }
        if (answer == null) {
            throw new MavenPasswords.Undecryptable("gpg-agent on " + socket + " holds no cached master password ("
                    + GPG_CACHE_ID + "), and jk never asks the agent to prompt; unlock it once with mvnenc, or"
                    + " preset it with gpg-preset-passphrase");
        }
        return answer;
    }

    /**
     * One Assuan exchange: the greeting, then {@code GET_PASSPHRASE --no-ask}. The cached
     * passphrase, or null when the agent answers {@code ERR} (nothing cached). Bounded by {@link
     * #AGENT_TIMEOUT}: a watchdog closes the channel, which ends a blocked read.
     */
    static @Nullable String askAgent(Path socket) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            Thread watchdog = Thread.ofVirtual().name("jk-gpg-agent-watchdog").start(() -> {
                try {
                    Thread.sleep(AGENT_TIMEOUT);
                    channel.close();
                } catch (InterruptedException | IOException done) {
                    // answered in time, or already closed
                }
            });
            try {
                channel.connect(UnixDomainSocketAddress.of(socket));
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
                OutputStream out = Channels.newOutputStream(channel);
                String greeting = line(in);
                if (!greeting.startsWith("OK")) throw new IOException("it greeted with something other than OK");
                out.write(("GET_PASSPHRASE --no-ask " + GPG_CACHE_ID + " X Maven+Master+Password"
                                + " Maven+master+password+for+decrypting+Maven+settings\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                String reply = line(in);
                if (reply.startsWith("ERR")) return null;
                if (!reply.startsWith("OK")) throw new IOException("it answered something other than OK or ERR");
                String hex = reply.substring(2).strip();
                try {
                    return new String(Hashing.unhex(hex), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException notHex) {
                    throw new IOException("its answer is not hex");
                }
            } finally {
                watchdog.interrupt();
            }
        }
    }

    /** The next Assuan line that is not a comment or a status line. */
    private static String line(BufferedReader in) throws IOException {
        while (true) {
            String line = in.readLine();
            if (line == null) throw new IOException("it closed the connection");
            if (line.startsWith("#") || line.startsWith("S ")) continue;
            return line;
        }
    }
}
