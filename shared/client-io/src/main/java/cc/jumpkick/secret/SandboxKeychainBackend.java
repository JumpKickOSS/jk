// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.util.KeychainSandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The keychain of a test sandbox ({@link KeychainSandbox}): one owner-only file per item in the
 * sandbox's directory, {@code item.repo+nexus} for {@code repo/nexus}. It always answers, as an
 * unlocked desktop keychain does, so a sandboxed {@code jk repo login} takes the keychain path a
 * desktop takes, and its record points here ({@code sandbox-keychain repo/nexus}) rather than at a
 * keychain of the machine's.
 */
public final class SandboxKeychainBackend implements SecretBackend {

    /** What a record's pointer names this keychain. */
    public static final String ID = "sandbox-keychain";

    private final Path dir;

    public SandboxKeychainBackend(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "sandbox keychain";
    }

    /** Where it keeps its items. */
    public Path dir() {
        return dir;
    }

    @Override
    public Probe probe() {
        return Probe.AVAILABLE;
    }

    @Override
    public Optional<String> read(String item) {
        try {
            return Optional.of(Files.readString(file(item), StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException e) {
            throw new SecretBackendException("the sandbox keychain could not read " + item + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String item, String label, String secret) {
        try {
            OwnerOnlyFiles.write(dir, file(item), secret);
        } catch (IOException e) {
            throw new SecretBackendException("the sandbox keychain could not store " + item + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String item) {
        try {
            Files.deleteIfExists(file(item));
        } catch (IOException e) {
            throw new SecretBackendException(
                    "the sandbox keychain could not remove " + item + ": " + e.getMessage(), e);
        }
    }

    /** One file name per item: {@code /} is no file name's, and the prefix keeps {@code ..} one too. */
    private Path file(String item) {
        return dir.resolve("item." + SecretItems.requireValid(item).replace('/', '+'));
    }
}
