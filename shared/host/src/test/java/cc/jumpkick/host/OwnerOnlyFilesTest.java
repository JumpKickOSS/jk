// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyFilesTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_fresh_file_is_owner_only_with_its_content(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("creds");
        Path file = dir.resolve("token");

        OwnerOnlyFiles.write(dir, file, "secret");

        assertThat(Files.readString(file)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * A secret is never written into a file another user can read. An existing loose file is not
     * rewritten in place: a second name for that inode (a hard link) would carry the new secret at
     * the old mode. The alias standing in for the loose inode keeps the stale bytes.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void an_existing_loose_file_never_carries_the_secret(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("creds");
        Files.createDirectories(dir);
        Path file = Files.writeString(dir.resolve("token"), "stale");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Path alias = Files.createLink(tmp.resolve("alias"), file);

        OwnerOnlyFiles.write(dir, file, "secret");

        assertThat(Files.readString(file)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(alias))
                .as("the world-readable inode never saw the secret")
                .isEqualTo("stale");
    }

    /**
     * On NTFS a secret and its directory carry only the user's entry, whatever the parent lets
     * others read, and what is later created in that directory inherits the same.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_fresh_file_on_windows_allows_only_its_owner(@TempDir Path tmp) throws IOException {
        letAForeignPrincipalRead(tmp);
        Path dir = tmp.resolve("creds");
        Path file = dir.resolve("token");

        OwnerOnlyFiles.write(dir, file, "secret");

        assertThat(Files.readString(file)).isEqualTo("secret");
        assertOwnerOnly(file);
        assertOwnerOnly(dir);
        assertOwnerOnly(Files.writeString(dir.resolve("later"), "x"));
    }

    /** A file and a directory that already let a foreign principal in are tightened in place. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void an_existing_loose_file_on_windows_is_tightened(@TempDir Path tmp) throws IOException {
        letAForeignPrincipalRead(tmp);
        Path dir = Files.createDirectories(tmp.resolve("engine"));
        Path file = Files.writeString(dir.resolve("token"), "secret");

        OwnerOnlyFiles.directory(dir);
        OwnerOnlyFiles.setOwnerOnly(file, "rw-------");

        assertOwnerOnly(dir);
        assertOwnerOnly(file);
        assertThat(Files.readString(file)).isEqualTo("secret");
    }

    /** A profile's own ACL (the user, SYSTEM and Administrators) is not rewritten on every run. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void an_existing_acl_of_the_user_system_and_administrators_is_left_alone(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("home"));
        String root = System.getenv("SystemRoot");
        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        view.setAcl(List.of(
                fullControl(me()),
                fullControl(Files.getOwner(Path.of(root).getRoot().resolve("ProgramData"))),
                fullControl(Files.getOwner(Path.of(root, "System32", "drivers", "etc", "hosts")))));
        Path file = Files.writeString(dir.resolve("config.toml"), "x");
        List<AclEntry> dirAcl = view.getAcl();
        List<AclEntry> fileAcl =
                Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();

        OwnerOnlyFiles.directory(dir);
        OwnerOnlyFiles.setOwnerOnly(file, "rw-------");

        assertThat(view.getAcl()).hasSize(3).isEqualTo(dirAcl);
        assertThat(Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl())
                .isEqualTo(fileAcl);
    }

    /** A filesystem that refuses ACLs costs the protection, never the write. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_refused_acl_never_stops_a_write(@TempDir Path tmp) throws IOException {
        letAForeignPrincipalRead(tmp);
        Path loose = Files.writeString(tmp.resolve("loose"), "kept");
        OwnerOnlyAcl.refuseForTests(true);
        try {
            Path dir = tmp.resolve("state/engine");
            OwnerOnlyFiles.write(dir, dir.resolve("token"), "secret");
            OwnerOnlyFiles.writeString(dir.resolve("port"), "4242");
            OwnerOnlyFiles.touch(dir.resolve("lock"));
            OwnerOnlyFiles.createDirectory(dir.resolve("runs"));
            OwnerOnlyFiles.directory(tmp);
            OwnerOnlyFiles.setOwnerOnly(loose, "rw-------");
            OwnerOnlyFiles.seal(dir);

            assertThat(Files.readString(dir.resolve("token"))).isEqualTo("secret");
            assertThat(Files.readString(dir.resolve("port"))).isEqualTo("4242");
            assertThat(dir.resolve("lock")).isRegularFile();
            assertThat(dir.resolve("runs")).isDirectory();
            assertThat(Files.readString(loose)).isEqualTo("kept");
            assertThat(OwnerOnlyAcl.unenforcedNoted())
                    .as("the refusal is warned about")
                    .isTrue();
        } finally {
            OwnerOnlyAcl.refuseForTests(false);
        }
    }

    /** Lets the owner of {@code System32}, a principal jk never tolerates, read what {@code dir} holds. */
    private static void letAForeignPrincipalRead(Path dir) throws IOException {
        UserPrincipal foreign = Files.getOwner(Path.of(System.getenv("SystemRoot"), "System32"));
        assertThat(foreign).isNotEqualTo(me());
        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        List<AclEntry> acl = new ArrayList<>(view.getAcl());
        acl.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(foreign)
                .setPermissions(
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.READ_ACL,
                        AclEntryPermission.SYNCHRONIZE)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build());
        view.setAcl(acl);
    }

    private static AclEntry fullControl(UserPrincipal who) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(who)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
    }

    private static UserPrincipal me() throws IOException {
        return FileSystems.getDefault()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
    }

    private static void assertOwnerOnly(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        UserPrincipal owner = view.getOwner();
        UserPrincipal me = me();
        assertThat(view.getAcl()).as("%s's ACL", path).isNotEmpty().allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(e.principal()).isIn(owner, me);
        });
    }
}
