// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * What a file's metadata says about its bytes: size, modification time, change time and the file's
 * identity on its volume. Two equal identities are trusted to name the same content, which is what
 * every stat-based skip in jk rests on.
 *
 * <p>Size and mtime alone are not enough: {@code cp -p}, {@code rsync -a}, {@code tar x}, {@code
 * touch -r} and a backup restore put different bytes behind the size and mtime of the original.
 * The change time cannot be set by any of them (setting the mtime moves it), and the file id moves
 * when a tool replaces the file instead of rewriting it. On Unix these are {@code st_ctime} and
 * {@code (st_dev, st_ino)}; on Windows {@code ChangeTime} and the volume serial plus file id, read
 * through a {@link WindowsReader}. A field the platform cannot supply is zero or empty, which leaves
 * the identity as strong as what is left, never stronger.
 *
 * @param size bytes
 * @param mtimeNanos last modification, nanoseconds since the epoch
 * @param ctimeNanos last metadata or content change, nanoseconds since the epoch; 0 when unknown
 * @param fileKey the file's identity on its volume ({@code dev:ino}, {@code volume:fileId}); empty
 *     when unknown
 */
public record StatIdentity(long size, long mtimeNanos, long ctimeNanos, String fileKey) {

    /** The identity of {@code file}, following a symbolic link as {@link Files#readAttributes} does. */
    public static StatIdentity of(Path file) throws IOException {
        StatIdentity whole = Os.isWindows() ? windowsByName(file) : null;
        return whole != null ? whole : of(file, Files.readAttributes(file, BasicFileAttributes.class));
    }

    /**
     * The identity of {@code file} from the attributes a caller already read — a tree walk hands
     * them over. On Unix the change time and inode come from those same attributes when the JDK's
     * file-system internals are open to jk ({@code --add-opens java.base/sun.nio.fs}, which the
     * engine runs with), and from one more stat otherwise.
     */
    public static StatIdentity of(Path file, BasicFileAttributes attrs) throws IOException {
        long size = attrs.size();
        long mtime = nanos(attrs.lastModifiedTime());
        if (Os.isWindows()) {
            StatIdentity whole = attrs.isSymbolicLink() ? null : windowsByName(file);
            if (whole != null) return whole;
            WindowsReader reader = Windows.READER;
            WindowsIds ids = reader == null ? null : reader.read(file, attrs.isSymbolicLink());
            return ids == null
                    ? new StatIdentity(size, mtime, 0, "")
                    : new StatIdentity(size, mtime, ids.changeTimeNanos(), ids.fileKey());
        }
        Object key = attrs.fileKey();
        Long ctime = UnixCtime.from(attrs);
        if (ctime != null && key != null) return new StatIdentity(size, mtime, ctime, key.toString());
        LinkOption[] links = attrs.isSymbolicLink() ? new LinkOption[] {LinkOption.NOFOLLOW_LINKS} : new LinkOption[0];
        Map<String, Object> unix;
        try {
            unix = Files.readAttributes(file, "unix:ctime,dev,ino", links);
        } catch (UnsupportedOperationException | IllegalArgumentException noUnixView) {
            return new StatIdentity(size, mtime, 0, key == null ? "" : key.toString());
        }
        long c = unix.get("ctime") instanceof FileTime t ? nanos(t) : 0;
        String k = "(dev=" + Long.toHexString(asLong(unix.get("dev"))) + ",ino=" + asLong(unix.get("ino")) + ")";
        return new StatIdentity(size, mtime, c, key == null ? k : key.toString());
    }

    /** The modification time in milliseconds, for callers that record it at that grain. */
    public long mtimeMillis() {
        return Math.floorDiv(mtimeNanos, 1_000_000L);
    }

    /**
     * When the file was last written, in milliseconds: the later of its mtime and its change time. A
     * file written within one clock tick of now may still change under the same identity, so callers
     * distrust a hash taken that soon. The change time counts even when the mtime was set back, and
     * even at nanosecond resolution: NTFS and ext4 stamp it from a clock that ticks every few
     * milliseconds, so two same-size writes in one tick under a pinned mtime share every field here.
     */
    public long lastTouchedMillis() {
        long mtime = mtimeMillis();
        if (ctimeNanos == 0) return mtime;
        return Math.max(mtime, Math.floorDiv(ctimeNanos, 1_000_000L));
    }

    /** One token for persisting: {@code size:mtime:ctime:key}, read back by {@link #decode}. */
    public String encode() {
        return size + ":" + mtimeNanos + ":" + ctimeNanos + ":" + fileKey;
    }

    /** The identity {@link #encode} wrote, or {@code null} for a token that is not one. */
    public static @Nullable StatIdentity decode(String token) {
        int a = token.indexOf(':');
        int b = a < 0 ? -1 : token.indexOf(':', a + 1);
        int c = b < 0 ? -1 : token.indexOf(':', b + 1);
        if (c < 0) return null;
        try {
            return new StatIdentity(
                    Long.parseLong(token.substring(0, a)),
                    Long.parseLong(token.substring(a + 1, b)),
                    Long.parseLong(token.substring(b + 1, c)),
                    token.substring(c + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A Windows file's {@code ChangeTime}, in nanoseconds since the epoch, and its volume and file id. */
    public record WindowsIds(long changeTimeNanos, String fileKey) {}

    /**
     * Reads what the JDK's Windows attributes lack. The implementation needs the foreign-function
     * API, which this module's release predates, so it lives in {@code jk-core} and is found by
     * name; a process without it gets identities with no change time or file id.
     */
    public interface WindowsReader {
        @Nullable
        WindowsIds read(Path file, boolean noFollow);

        /**
         * The whole identity of {@code file} from one call by name, with no handle opened, or null
         * when this Windows has no such call, the call fails, or the file is a reparse point: the
         * caller then takes the path above, which reports an absent file and follows links as
         * {@link Files#readAttributes} does.
         */
        default @Nullable StatIdentity stat(Path file) {
            return null;
        }
    }

    /** {@link WindowsReader#stat}, or null without a reader. */
    private static @Nullable StatIdentity windowsByName(Path file) {
        WindowsReader reader = Windows.READER;
        return reader == null ? null : reader.stat(file);
    }

    private static final class Windows {
        static final @Nullable WindowsReader READER = load();

        private Windows() {}

        private static @Nullable WindowsReader load() {
            try {
                return (WindowsReader) Class.forName("cc.jumpkick.util.WindowsFileIds")
                        .getDeclaredConstructor()
                        .newInstance();
            } catch (ReflectiveOperationException | LinkageError | ClassCastException absent) {
                return null;
            }
        }
    }

    private static long nanos(FileTime t) {
        return t.to(TimeUnit.NANOSECONDS);
    }

    private static long asLong(@Nullable Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * {@code st_ctime} from the attributes object a Unix walk already holds, without another stat.
     * The JDK keeps it on {@code sun.nio.fs.UnixFileAttributes} and hands walks a wrapper of that
     * class; both are reached through method handles when the package is open, and not at all
     * otherwise.
     */
    private static final class UnixCtime {
        private static final @Nullable Class<?> ATTRS = load("sun.nio.fs.UnixFileAttributes");
        private static final @Nullable Class<?> WRAPPER =
                load("sun.nio.fs.UnixFileAttributes$UnixAsBasicFileAttributes");
        private static final @Nullable MethodHandle CTIME = handle(false);
        private static final @Nullable MethodHandle WRAPPED_CTIME = handle(true);

        private UnixCtime() {}

        static @Nullable Long from(BasicFileAttributes attrs) {
            try {
                Class<?> type = attrs.getClass();
                if (CTIME != null && type == ATTRS) return nanos((FileTime) CTIME.invoke(attrs));
                if (WRAPPED_CTIME != null && type == WRAPPER) return nanos((FileTime) WRAPPED_CTIME.invoke(attrs));
            } catch (Throwable unreadable) {
                return null;
            }
            return null;
        }

        private static @Nullable Class<?> load(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException | LinkageError absent) {
                return null;
            }
        }

        private static @Nullable MethodHandle handle(boolean wrapped) {
            if (ATTRS == null || (wrapped && WRAPPER == null)) return null;
            try {
                MethodHandle ctime = MethodHandles.privateLookupIn(ATTRS, MethodHandles.lookup())
                        .findVirtual(ATTRS, "ctime", MethodType.methodType(FileTime.class));
                if (!wrapped) return ctime;
                MethodHandle unwrap = MethodHandles.privateLookupIn(WRAPPER, MethodHandles.lookup())
                        .findGetter(WRAPPER, "attrs", ATTRS);
                return MethodHandles.filterArguments(ctime, 0, unwrap);
            } catch (ReflectiveOperationException | RuntimeException closed) {
                return null;
            }
        }
    }
}
