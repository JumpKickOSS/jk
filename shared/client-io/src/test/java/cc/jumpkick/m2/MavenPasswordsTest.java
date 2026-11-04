// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven 3's password encryption, against known-answer pairs published with the reference
 * implementation. No {@code mvn} was at hand to produce fresh ones, so every ciphertext here is
 * copied from the plexus projects' own tests, which ran against the code {@code mvn
 * --encrypt-master-password} / {@code --encrypt-password} call:
 *
 * <ul>
 *   <li>{@link #PBE_CIPHER}: {@code PBECipherTest} in codehaus-plexus/plexus-cipher, tag {@code
 *       plexus-cipher-2.0} ({@code src/test/java/org/sonatype/plexus/components/cipher/}).
 *   <li>{@link #DEFAULT_CIPHER}: {@code DefaultPlexusCipherTest}, same tag and directory.
 *   <li>{@link #MASTER} and {@link #SERVER_PASSWORD}: {@code SecUtilTest} in
 *       codehaus-plexus/plexus-sec-dispatcher, tag {@code plexus-sec-dispatcher-2.0} — a master
 *       password as {@code settings-security.xml} holds it, and a password encrypted under it.
 * </ul>
 */
class MavenPasswordsTest {

    /** {@code veryOpenText} under the passphrase {@code testtest}. */
    static final String PBE_CIPHER = "ibeHrdCOonkH7d7YnH7sarQLbwOk1ljkkM/z8hUhl4c=";

    /** {@code my testing phrase} under the passphrase {@code testtest}. */
    static final String DEFAULT_CIPHER = "LFulS0pAlmMHpDtm+81oPcqctcwpco5p4Fo7640/gqDRifCahXBefG4FxgKcu17v";

    /** The master password {@code testtest}, encrypted under {@code settings.security} and decorated. */
    static final String MASTER = "{1wQaa6S/o8MH7FnaTNL53XmhT5O0SEGXQi3gC49o6OY=}";

    /** The password {@code testtest}, encrypted under the master password {@code testtest}. */
    static final String SERVER_PASSWORD = "{BteqUEnqHecHM7MZfnj9FwLcYbdInWxou1C929Txa0A=}";

    @Test
    void the_reference_ciphertexts_decrypt_to_their_published_plaintexts() throws Exception {
        assertThat(MavenPasswords.decrypt(PBE_CIPHER, "testtest")).isEqualTo("veryOpenText");
        assertThat(MavenPasswords.decrypt(DEFAULT_CIPHER, "testtest")).isEqualTo("my testing phrase");
    }

    @Test
    void the_master_password_decrypts_under_the_fixed_passphrase_and_unlocks_the_server_password() throws Exception {
        String master = MavenPasswords.master(MASTER);

        assertThat(master).isEqualTo("testtest");
        assertThat(MavenPasswords.decrypt(Objects.requireNonNull(MavenPasswords.payload(SERVER_PASSWORD)), master))
                .isEqualTo("testtest");
    }

    @Test
    void text_outside_the_braces_is_a_comment() {
        assertThat(MavenPasswords.payload("rotated 2026-10-01, ask ops " + SERVER_PASSWORD + " before then"))
                .isEqualTo(SERVER_PASSWORD.substring(1, SERVER_PASSWORD.length() - 1));
    }

    /** The brace permutations of {@code DefaultPlexusCipherTest}, with its expected answers. */
    @Test
    void escaped_braces_follow_the_reference_implementation() {
        assertThat(MavenPasswords.payload("This is a test")).isNull();
        assertThat(MavenPasswords.payload("\\{This is a test\\}")).isNull();
        assertThat(MavenPasswords.payload("Comment {This is a test} other comment with a: }"))
                .isEqualTo("This is a test");
        assertThat(MavenPasswords.payload("Comment {foo\\{This is a test\\}} other comment with a: }"))
                .isEqualTo("foo\\{This is a test\\}");
        assertThat(MavenPasswords.payload("{}")).isNull();
    }

    @Test
    void the_wrong_passphrase_does_not_decrypt() {
        assertThatThrownBy(() -> MavenPasswords.decrypt(PBE_CIPHER, MavenPasswords.MASTER_PASSPHRASE))
                .isInstanceOf(MavenPasswords.Undecryptable.class)
                .hasMessage("it does not decrypt with that key");
    }

    @Test
    void a_maven_4_value_is_recognised_and_not_guessed_at() {
        assertThatThrownBy(() -> MavenPasswords.decrypt(
                        "[name=master,cipher=AES/GCM/NoPadding,version=4.0]AAAAAAAAAAAAAAAAAAAAAAAA", "testtest"))
                .isInstanceOf(MavenPasswords.Undecryptable.class)
                .hasMessageContaining("Maven 4's mvnenc format");
    }

    @Test
    void a_relocation_is_followed_relative_to_the_file_that_names_it(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve(".m2"));
        Path first = home.resolve(".m2").resolve("settings-security.xml");
        Files.writeString(first, "<settingsSecurity><relocation>../vault/sec.xml</relocation></settingsSecurity>");
        Path vault = home.resolve("vault");
        Files.createDirectories(vault);
        Files.writeString(
                vault.resolve("sec.xml"), "<settingsSecurity><master>" + MASTER + "</master></settingsSecurity>");
        Set<Path> read = new LinkedHashSet<>();

        assertThat(MavenPasswords.readMaster(first, home, read)).isEqualTo("testtest");
        assertThat(read).containsExactly(first, vault.resolve("sec.xml"));
    }

    @Test
    void a_relocation_loop_is_reported(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sec.xml");
        Files.writeString(file, "<settingsSecurity><relocation>sec.xml</relocation></settingsSecurity>");

        assertThatThrownBy(() -> MavenPasswords.readMaster(file, dir, new LinkedHashSet<>()))
                .isInstanceOf(MavenPasswords.Undecryptable.class)
                .hasMessageContaining("loops");
    }
}
