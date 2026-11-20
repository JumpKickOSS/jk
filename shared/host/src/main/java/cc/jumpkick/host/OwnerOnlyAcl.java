// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Owner-only where the filesystem has Windows ACLs and no POSIX modes. What jk creates gets an ACL
 * of full control for the user jk runs as and no entry inherited from the parent; a directory's
 * entry is inheritable, so what is created inside it is owner-only too. An existing file or
 * directory is rewritten only when its ACL lets in a principal other than that user, its owner,
 * SYSTEM or Administrators, so a profile's inherited ACL is left as it is.
 *
 * <p>Nothing here stops jk: a filesystem that refuses or does not keep the ACL (a network share,
 * FAT, a WSL path) gets a plain create and one warning per process.
 */
final class OwnerOnlyAcl {

    private static final AtomicBoolean UNENFORCED_NOTED = new AtomicBoolean();

    private static volatile boolean refusedForTests;

    private OwnerOnlyAcl() {}

    /** A create that takes the attributes to create with. */
    @FunctionalInterface
    interface Create<T> {
        T with(FileAttribute<?>... attrs) throws IOException;
    }

    /** True when {@code path} is on the default filesystem and it has ACLs and no POSIX modes (NTFS, ReFS). */
    static boolean applies(Path path) {
        return path.getFileSystem() == FileSystems.getDefault()
                && Files.getFileAttributeView(path, PosixFileAttributeView.class) == null
                && Files.getFileAttributeView(path, AclFileAttributeView.class) != null;
    }

    /**
     * Run {@code create} with the owner-only ACL where {@link #applies}, so the new file or
     * {@code directory} never carries the parent's entries; a refused ACL falls back to a plain
     * create, warned about once when that succeeds. An existing target's
     * {@link FileAlreadyExistsException} is the caller's.
     */
    static <T> T create(Path path, boolean directory, Create<T> create) throws IOException {
        UserPrincipal me = applies(path) ? Me.USER : null;
        if (me == null) return create.with();
        FileAttribute<?>[] attrs = {ownerOnly(List.of(fullControl(me, directory)))};
        try {
            if (refusedForTests) throw new FileSystemException(path.toString(), null, "ACL refused by a test");
            return create.with(attrs);
        } catch (FileAlreadyExistsException exists) {
            throw exists;
        } catch (IOException | UnsupportedOperationException | SecurityException refused) {
            T made = create.with();
            unenforced(path, refused);
            return made;
        }
    }

    /**
     * Give {@code path} the owner-only ACL when its ACL lets in anyone but the user, its owner,
     * SYSTEM or Administrators. Never throws: a refused or unkept ACL is warned about once.
     */
    static void tighten(Path path, boolean directory) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) return;
            UserPrincipal owner = view.getOwner();
            UserPrincipal me = Me.USER;
            if (!letsOthersIn(view.getAcl(), owner, me)) return;
            List<AclEntry> acl = new ArrayList<>(2);
            if (me != null) acl.add(fullControl(me, directory));
            if (!owner.equals(me)) acl.add(fullControl(owner, directory));
            if (refusedForTests) throw new FileSystemException(path.toString(), null, "ACL refused by a test");
            view.setAcl(acl);
            if (letsOthersIn(view.getAcl(), owner, me)) unenforced(path, null);
        } catch (IOException | RuntimeException refused) {
            unenforced(path, refused);
        }
    }

    /**
     * True when {@code acl} allows a principal other than {@code owner}, {@code me}, SYSTEM or
     * Administrators. An empty list is how the JDK reports a null DACL, which lets everyone in.
     */
    private static boolean letsOthersIn(List<AclEntry> acl, UserPrincipal owner, @Nullable UserPrincipal me) {
        if (acl.isEmpty()) return true;
        for (AclEntry e : acl) {
            if (e.type() != AclEntryType.ALLOW) continue;
            UserPrincipal p = e.principal();
            if (p.equals(owner) || p.equals(me) || WellKnown.onThisHost().either(p)) continue;
            return true;
        }
        return false;
    }

    private static void unenforced(Path path, @Nullable Throwable cause) {
        if (cause != null) Log.debug("owner-only ACL not set on " + path, cause);
        if (UNENFORCED_NOTED.compareAndSet(false, true)) {
            Log.warn(path + ": could not set an owner-only ACL");
        }
    }

    /** True once a refused or unkept ACL has been warned about in this process. */
    static boolean unenforcedNoted() {
        return UNENFORCED_NOTED.get();
    }

    /** Makes every ACL create and rewrite fail as a filesystem that refuses them would. */
    static void refuseForTests(boolean refuse) {
        refusedForTests = refuse;
    }

    private static FileAttribute<List<AclEntry>> ownerOnly(List<AclEntry> acl) {
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    private static AclEntry fullControl(UserPrincipal who, boolean directory) {
        AclEntry.Builder b = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(who)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) b.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        return b.build();
    }

    /** The user jk runs as, resolved once; null when the name does not resolve. */
    private static final class Me {
        static final @Nullable UserPrincipal USER = lookup();

        private static @Nullable UserPrincipal lookup() {
            String name = System.getProperty("user.name");
            if (name == null || name.isBlank()) return null;
            try {
                return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                return null;
            }
        }
    }

    /**
     * SYSTEM and Administrators, recognised without their names, which Windows localises, from the
     * owners of system objects a standard user can read ({@code ProgramData}, {@code System32\Tasks}
     * and {@code Users} for SYSTEM; {@code drivers\etc\hosts}, {@code ServiceProfiles},
     * {@code Prefetch} and {@code Installer} for Administrators). Which of them a machine gives to
     * whom varies with its edition and history (one Windows 11 Pro has {@code ServiceProfiles} owned
     * by SYSTEM), so each is the principal that more than half of its readable references name once
     * the user, the owner of {@code SystemRoot} (TrustedInstaller) and, for Administrators, the
     * recognised SYSTEM are set aside, and it must appear in {@code System32}'s ACL. Only an
     * administrator can give these objects an owner, so an ordinary principal is never recognised
     * unless an administrator made it so. Null without such a majority, and an unrecognised
     * principal is treated as foreign.
     */
    record WellKnown(
            @Nullable UserPrincipal system, @Nullable UserPrincipal administrators) {

        /** True when {@code p} is the recognised SYSTEM or Administrators. */
        boolean either(UserPrincipal p) {
            return p.equals(system) || p.equals(administrators);
        }

        /**
         * Recognise the two from the owners of their references (null where unreadable), the owner
         * of {@code SystemRoot}, the principals of {@code System32}'s ACL and the user.
         */
        static WellKnown recognise(
                List<@Nullable UserPrincipal> systemOwners,
                List<@Nullable UserPrincipal> administratorsOwners,
                @Nullable UserPrincipal rootOwner,
                List<UserPrincipal> system32,
                @Nullable UserPrincipal me) {
            @Nullable UserPrincipal system = agreed(systemOwners, system32, me, rootOwner, null);
            return new WellKnown(system, agreed(administratorsOwners, system32, me, rootOwner, system));
        }

        /** SYSTEM and Administrators of this machine, read once. */
        static WellKnown onThisHost() {
            return Host.WELL_KNOWN;
        }

        private static @Nullable UserPrincipal agreed(
                List<@Nullable UserPrincipal> owners,
                List<UserPrincipal> system32,
                @Nullable UserPrincipal me,
                @Nullable UserPrincipal root,
                @Nullable UserPrincipal system) {
            Map<UserPrincipal, Integer> votes = new HashMap<>();
            int left = 0;
            for (@Nullable UserPrincipal owner : owners) {
                if (owner == null || owner.equals(me) || owner.equals(root) || owner.equals(system)) continue;
                votes.merge(owner, 1, Integer::sum);
                left++;
            }
            for (Map.Entry<UserPrincipal, Integer> v : votes.entrySet()) {
                if (2 * v.getValue() > left && system32.contains(v.getKey())) return v.getKey();
            }
            return null;
        }
    }

    /** What {@link WellKnown} reads from this machine; a reference that cannot be read counts as null. */
    private static final class Host {
        private static final @Nullable String ROOT = ProcessEnv.get("SystemRoot");
        static final WellKnown WELL_KNOWN = WellKnown.recognise(
                owners(programData(), path(ROOT, "System32", "Tasks"), users()),
                owners(
                        path(ROOT, "System32", "drivers", "etc", "hosts"),
                        path(ROOT, "ServiceProfiles"),
                        path(ROOT, "Prefetch"),
                        path(ROOT, "Installer")),
                owner(path(ROOT)),
                principals(ROOT, "System32"),
                Me.USER);

        private static List<@Nullable UserPrincipal> owners(@Nullable Path... paths) {
            List<@Nullable UserPrincipal> owners = new ArrayList<>(paths.length);
            for (@Nullable Path p : paths) owners.add(owner(p));
            return owners;
        }

        private static @Nullable UserPrincipal owner(@Nullable Path path) {
            if (path == null) return null;
            try {
                return Files.getOwner(path);
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        private static List<UserPrincipal> principals(@Nullable String first, String... more) {
            Path dir = path(first, more);
            if (dir == null) return List.of();
            try {
                AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
                if (view == null) return List.of();
                return view.getAcl().stream().map(AclEntry::principal).toList();
            } catch (IOException | RuntimeException unreadable) {
                return List.of();
            }
        }

        /** {@code ProgramData} at the root of the Windows drive, where Windows puts it. */
        private static @Nullable Path programData() {
            return onWindowsDrive("ProgramData");
        }

        /** {@code Users} at the root of the Windows drive, where Windows puts it. */
        private static @Nullable Path users() {
            return onWindowsDrive("Users");
        }

        private static @Nullable Path onWindowsDrive(String name) {
            Path windows = path(ROOT);
            Path drive = windows == null ? null : windows.getRoot();
            return drive == null ? null : drive.resolve(name);
        }

        private static @Nullable Path path(@Nullable String first, String... more) {
            if (first == null || first.isBlank()) return null;
            try {
                return Path.of(first, more);
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }
}
