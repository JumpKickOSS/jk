// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.watch.SourceWatch;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretBackend;
import cc.jumpkick.secret.SecretBackendException;
import cc.jumpkick.secret.SecretVault;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientKeychainTest {

    /** A keychain that answers every read with {@code <item>-secret}, except a locked item. */
    private record Answering(String id) implements SecretBackend {
        @Override
        public String displayName() {
            return id;
        }

        @Override
        public Probe probe() {
            return Probe.AVAILABLE;
        }

        @Override
        public Optional<String> read(String item) {
            if (item.endsWith("locked")) throw new SecretBackendException("the default keyring is locked");
            if (item.endsWith("gone")) return Optional.empty();
            return Optional.of(item + "-secret");
        }

        @Override
        public void write(String item, String label, String secret) {}

        @Override
        public void delete(String item) {}
    }

    @Test
    void every_keychain_login_rides_the_request_as_its_secret_or_why_there_is_none() {
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(new Answering("bus")));
        var nexus = new SecretVault.Pointer("bus", "repo/nexus");
        var locked = new SecretVault.Pointer("bus", "repo/locked");
        var gone = new SecretVault.Pointer("bus", "repo/gone");
        var elsewhere = new SecretVault.Pointer("macos-keychain", "repo/mac");
        var forge = new SecretVault.Pointer("bus", "forge/github.com");
        List<SecretVault.Pointer> pointers = ClientKeychain.pointers(
                List.of(
                        new RepoCredentialStore.Login("nexus", "bearer", URI.create("https://n"), nexus),
                        new RepoCredentialStore.Login("locked", "bearer", URI.create("https://l"), locked),
                        new RepoCredentialStore.Login("gone", "bearer", URI.create("https://g"), gone),
                        new RepoCredentialStore.Login("mac", "bearer", URI.create("https://m"), elsewhere),
                        new RepoCredentialStore.Login("plain", "bearer", URI.create("https://p"), null)),
                List.of(new TokenStore.Login("github.com", forge)));

        ClientKeychain.Carried carried = ClientKeychain.read(vault, pointers);

        assertThat(carried.secrets())
                .isEqualTo(Map.of(
                        "bus repo/nexus", "repo/nexus-secret", "bus forge/github.com", "forge/github.com-secret"));
        assertThat(carried.refused())
                .containsOnlyKeys("bus repo/locked", "bus repo/gone", "macos-keychain repo/mac")
                .containsEntry("bus repo/locked", "the default keyring is locked")
                .containsEntry("bus repo/gone", "it holds no item repo/gone");
        assertThat(carried.refused().get("macos-keychain repo/mac")).contains("this machine has no macos-keychain");
    }

    @Test
    void no_keychain_login_reads_nothing() {
        assertThat(ClientKeychain.read(SecretVault.fileOnly(), List.of())).isEqualTo(ClientKeychain.Carried.NONE);
    }

    @Test
    void a_command_reads_the_logins_as_they_are_when_it_runs_not_as_an_earlier_command_in_the_process_found_them(
            @TempDir Path home) throws Exception {
        System.setProperty("jk.env.JK_HOME", home.toString());
        login("private", "no-such-keychain repo/private");

        ClientKeychain.Carried first = SessionContext.invocation(() -> {
            ClientKeychain.Carried once = ClientKeychain.carried();
            assertThat(ClientKeychain.carried()).as("one command reads once").isSameAs(once);
            return once;
        });
        assertThat(first.refused()).containsOnlyKeys("no-such-keychain repo/private");

        assertThat(run("repo", "logout", "private")).isZero();
        assertThat(SessionContext.invocation(ClientKeychain::carried))
                .as("the command after the logout sends nothing for it")
                .isEqualTo(ClientKeychain.Carried.NONE);

        login("private", "no-such-keychain repo/rotated");
        assertThat(SessionContext.invocation(ClientKeychain::carried).refused())
                .as("the command after a new login sends the new item")
                .containsOnlyKeys("no-such-keychain repo/rotated");
    }

    @Test
    void a_running_watch_sends_a_login_rotated_under_it_with_the_next_rebuild(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path src = Files.createDirectories(project.resolve("src"));
        System.setProperty("jk.env.JK_HOME", home.toString());
        login("private", "no-such-keychain repo/private");

        SessionContext.invocation(() -> {
            try (SourceWatch watch = SourceWatch.open(project, List.of(src), 0)) {
                assertThat(ClientKeychain.carried().refused())
                        .as("the watch's first build")
                        .containsOnlyKeys("no-such-keychain repo/private");

                login("private", "no-such-keychain repo/rotated");
                assertThat(ClientKeychain.carried().refused())
                        .as("within one pass the read stands")
                        .containsOnlyKeys("no-such-keychain repo/private");

                Files.writeString(src.resolve("Main.java"), "class Main {}\n");
                assertThat(watch.pollChange(30, TimeUnit.SECONDS)).isPresent();

                assertThat(ClientKeychain.carried().refused())
                        .as("the rebuild after the change sends the rotated login")
                        .containsOnlyKeys("no-such-keychain repo/rotated");
            }
            return null;
        });
    }

    /** A {@code jk repo login} record whose secret is the keychain item {@code pointer}. */
    private static void login(String id, String pointer) throws Exception {
        Path records = Files.createDirectories(JkDirs.creds().resolve("repo"));
        Path record = records.resolve(id);
        Files.writeString(record, "bearer https://repo.example keychain\n" + pointer + "\n");
        OwnerOnlyFiles.setOwnerOnly(record, "rw-------");
    }
}
