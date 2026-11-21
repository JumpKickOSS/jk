// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpTokenGateTest {

    @Test
    void a_minted_token_is_owner_only_in_a_directory_others_can_read(@TempDir Path tmp) throws IOException {
        loosen(tmp);
        Path file = tmp.resolve("engine.http-token");

        HttpTokenGate gate = new HttpTokenGate(file);
        gate.loadOrMint();

        assertThat(gate.tokenText()).isNotBlank();
        assertThat(Files.readString(file)).isEqualTo(gate.tokenText());
        assertOwnerOnly(file);
    }

    @Test
    void a_token_adopted_from_a_loose_file_is_tightened_to_owner_only(@TempDir Path tmp) throws IOException {
        loosen(tmp);
        Path file = Files.writeString(tmp.resolve("engine.http-token"), "kept-token\n");
        if (posix(file)) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        HttpTokenGate gate = new HttpTokenGate(file);
        gate.loadOrMint();

        assertThat(gate.tokenText()).as("the token survives the restart").isEqualTo("kept-token");
        assertOwnerOnly(file);
    }

    @Test
    void a_report_token_opens_its_own_run_until_it_expires_and_never_stands_for_the_bearer(@TempDir Path tmp)
            throws IOException {
        Path file = Files.writeString(tmp.resolve("engine.http-token"), "bearer\n");
        HttpTokenGate gate = new HttpTokenGate(file);
        gate.loadOrMint();
        long expires = 1_000_000;
        String token = requireNonNull(gate.reportToken("r14", expires));

        assertThat(token).doesNotContain("bearer").matches("[0-9a-z]+\\.[A-Za-z0-9_-]+");
        assertThat(gate.reportTokenValid(token, "r14", expires * 1000 - 1)).isTrue();
        assertThat(gate.reportTokenValid(token, "r14", expires * 1000))
                .as("expired")
                .isFalse();
        assertThat(gate.reportTokenValid(token, "r15", 0)).as("another run").isFalse();
        assertThat(gate.tokenValid(token)).as("not a bearer token").isFalse();
        assertThat(gate.reportTokenValid("bearer", "r14", 0))
                .as("the bearer is not a report token")
                .isFalse();
        String later = Long.toString(expires * 2, 36) + token.substring(token.indexOf('.'));
        assertThat(gate.reportTokenValid(later, "r14", expires * 1000))
                .as("an expiry moved by hand")
                .isFalse();
        for (String bad : new String[] {"", ".", "x.", ".abc", "zzzzzzzzzzzzzzzzzz.abc", "1.not base64!"}) {
            assertThat(gate.reportTokenValid(bad, "r14", 0)).as(bad).isFalse();
        }

        Files.writeString(file, "rotated\n");
        HttpTokenGate rotated = new HttpTokenGate(file);
        rotated.loadOrMint();
        assertThat(rotated.reportTokenValid(token, "r14", 0))
                .as("rotating the bearer voids it")
                .isFalse();
    }

    private static boolean posix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    /**
     * Lets others read {@code dir} and, with Windows ACLs, whatever is created in it: an inheritable
     * read entry for the owner of {@code System32}, a principal that is neither the user, SYSTEM nor
     * Administrators.
     */
    private static void loosen(Path dir) throws IOException {
        if (posix(dir)) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        List<AclEntry> acl = new ArrayList<>(view.getAcl());
        UserPrincipal other = Files.getOwner(Path.of(System.getenv("SystemRoot"), "System32"));
        acl.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(other)
                .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build());
        view.setAcl(acl);
    }

    private static void assertOwnerOnly(Path file) throws IOException {
        if (posix(file)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-------");
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        UserPrincipal owner = view.getOwner();
        UserPrincipal me = file.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        assertThat(view.getAcl()).as("the token's ACL").isNotEmpty().allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(e.principal()).isIn(owner, me);
        });
    }
}
