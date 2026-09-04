// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * macOS terminal preferences read from CoreFoundation through Panama FFM.
 *
 * <p>Why read native preferences at all: glyph capability is a property of the <em>font</em>, and
 * the environment only ever names the terminal. {@code TERM_PROGRAM=iTerm.app} says nothing about
 * whether the user actually selected a Nerd Font-patched build, so {@link NerdFontDetect}'s
 * terminal-name heuristic guesses where it could know. iTerm2 stores the answer in its own
 * preferences domain, which is the only place the real font name exists.
 *
 * <p>Why {@code CFPreferencesCopyAppValue} rather than reading {@code
 * ~/Library/Preferences/com.googlecode.iterm2.plist} directly: {@code cfprefsd} owns the live value
 * and flushes to disk lazily, so the file can lag a settings change indefinitely — and it is a
 * binary plist jk would otherwise need a parser for. CoreFoundation also applies the full
 * preferences search list (managed preferences, host and user scopes) exactly as iTerm2 sees it.
 *
 * <p>Everything here is best-effort and <strong>never throws</strong>. It runs on CLI launch, where
 * a missing framework, denied native access, or an unexpected preferences shape must degrade to
 * {@link Optional#empty()} rather than fail a build. That "unexpected shape" case is not theoretical:
 * the preferences search list is wider than the application's own domain, so a non-{@code NULL}
 * return is no guarantee of the type we asked for — every value is type-checked with {@code
 * CFGetTypeID} before it is used.
 */
public final class MacPrefs {

    private MacPrefs() {}

    /**
     * iTerm2's active-profile font name, e.g. {@code "JetBrainsMonoNFM-Regular 12"} — the raw
     * {@code Normal Font} string, PostScript name and point size as iTerm2 stores it. Empty on
     * non-macOS, when iTerm2 has never run, or on any failure whatsoever.
     */
    public static Optional<String> itermFontName() {
        return itermFontName(System::getenv);
    }

    /**
     * Test seam: explicit env lookup. Only {@code ITERM_PROFILE} is consulted — iTerm2 exports it
     * per-session, so a split pane running a non-default profile is read correctly instead of
     * silently reporting the default profile's font.
     */
    static Optional<String> itermFontName(Function<String, @Nullable String> env) {
        try {
            if (!Os.isDarwin()) return Optional.empty();
            return readItermFont(env);
        } catch (Throwable t) {
            // Missing symbol, denied native access, unexpected plist shape — stay silent.
            return Optional.empty();
        }
    }

    /**
     * Test seam: whether the CoreFoundation downcalls have been resolved yet. Lets a test assert
     * that the non-macOS path never touches FFM at all, not merely that it returns empty.
     */
    static boolean nativeInitAttempted() {
        return cfInitAttempted;
    }

    // --- iTerm2 preferences shape -------------------------------------------------------------

    private static final String ITERM2_APP_ID = "com.googlecode.iterm2";

    /** Top-level array of profile dictionaries. iTerm2 still uses its original "bookmark" naming. */
    private static final String KEY_PROFILES = "New Bookmarks";

    /** Top-level {@code Guid} of the profile new sessions open with. */
    private static final String KEY_DEFAULT_GUID = "Default Bookmark Guid";

    private static final String KEY_NAME = "Name";
    private static final String KEY_GUID = "Guid";
    private static final String KEY_FONT = "Normal Font";
    private static final String ITERM_PROFILE_ENV = "ITERM_PROFILE";

    /**
     * Read the {@code Normal Font} of the profile this session is actually running.
     *
     * <p>Ownership: {@code Create}/{@code Copy} results are +1 and released in {@code finally} on
     * every path; {@code Get} results (array elements, dictionary values) are borrowed and must
     * never be released — the enclosing +1 array keeps them alive.
     */
    private static Optional<String> readItermFont(Function<String, @Nullable String> env) throws Throwable {
        Cf cf = coreFoundation();
        if (cf == null) return Optional.empty();

        MemorySegment appId = newCfString(cf, ITERM2_APP_ID);
        if (appId == null) return Optional.empty();
        try {
            MemorySegment profiles = copyAppValue(cf, KEY_PROFILES, appId);
            if (profiles == null) return Optional.empty();
            try {
                MemorySegment profile = activeProfile(cf, profiles, appId, env);
                if (profile == null) return Optional.empty();
                return Optional.ofNullable(trimToNull(dictionaryString(cf, profile, KEY_FONT)));
            } finally {
                release(cf, profiles);
            }
        } finally {
            release(cf, appId);
        }
    }

    /**
     * The profile dictionary for this session: {@code ITERM_PROFILE} matched against {@code Name}
     * when the env var is set (exact and case-sensitive, as iTerm2 exports the name verbatim),
     * otherwise {@code Default Bookmark Guid} matched against {@code Guid}. Borrowed — do not
     * release. {@code null} when nothing matches.
     */
    private static @Nullable MemorySegment activeProfile(
            Cf cf, MemorySegment profiles, MemorySegment appId, Function<String, @Nullable String> env)
            throws Throwable {
        long arrayTypeId = (long) cf.arrayGetTypeId().invokeExact();
        if (typeIdOf(cf, profiles) != arrayTypeId) return null;
        long count = (long) cf.arrayGetCount().invokeExact(profiles);
        if (count <= 0) return null;

        String sessionProfile = trimToNull(env.apply(ITERM_PROFILE_ENV));
        String matchKey = sessionProfile != null ? KEY_NAME : KEY_GUID;
        String wanted = sessionProfile != null ? sessionProfile : defaultProfileGuid(cf, appId);
        if (wanted == null) return null;

        long dictionaryTypeId = (long) cf.dictionaryGetTypeId().invokeExact();
        for (long i = 0; i < count; i++) {
            MemorySegment entry = (MemorySegment) cf.arrayGetValueAtIndex().invokeExact(profiles, i);
            if (isNull(entry) || typeIdOf(cf, entry) != dictionaryTypeId) continue;
            if (wanted.equals(dictionaryString(cf, entry, matchKey))) return entry;
        }
        return null;
    }

    /** Top-level {@code Default Bookmark Guid} as text; {@code null} if absent or not a CFString. */
    private static @Nullable String defaultProfileGuid(Cf cf, MemorySegment appId) throws Throwable {
        MemorySegment guid = copyAppValue(cf, KEY_DEFAULT_GUID, appId);
        if (guid == null) return null;
        try {
            long stringTypeId = (long) cf.stringGetTypeId().invokeExact();
            return typeIdOf(cf, guid) == stringTypeId ? trimToNull(stringValue(cf, guid)) : null;
        } finally {
            release(cf, guid);
        }
    }

    // --- CoreFoundation plumbing ---------------------------------------------------------------

    /**
     * The framework binary. Only the {@code String} overload of {@link SymbolLookup#libraryLookup}
     * resolves it: the {@code Path} overload calls {@code toRealPath()} and this Mach-O exists only
     * inside the dyld shared cache, with no file on disk. A bare {@code "CoreFoundation"}, {@code
     * System.loadLibrary}, and the native linker's default lookup all fail to find these symbols.
     */
    private static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    /** {@code kCFStringEncodingUTF8} from {@code CFString.h}. */
    private static final int UTF8 = 0x08000100;

    /** Sanity ceiling on a preference string; a font name is tens of bytes, not tens of kilobytes. */
    private static final long MAX_PREF_STRING_BYTES = 1L << 16;

    /**
     * The resolved CoreFoundation entry points. Bound as a set: a partially bound library is
     * indistinguishable from an unusable one, so the record is published only once every symbol
     * resolved.
     */
    private record Cf(
            MethodHandle stringCreateWithCString,
            MethodHandle preferencesCopyAppValue,
            MethodHandle arrayGetCount,
            MethodHandle arrayGetValueAtIndex,
            MethodHandle dictionaryGetValue,
            MethodHandle getTypeId,
            MethodHandle stringGetTypeId,
            MethodHandle arrayGetTypeId,
            MethodHandle dictionaryGetTypeId,
            MethodHandle stringGetCStringPtr,
            MethodHandle stringGetCString,
            MethodHandle stringGetLength,
            MethodHandle stringGetMaximumSizeForEncoding,
            MethodHandle release) {}

    /**
     * Downcalls resolved on first use, never at class initialization: GraalVM native-image treats
     * build-time initialization of a class holding FFM handles as a fatal build error ({@code
     * should not reach here: linkToNative}). Same discipline as {@code TerminalSize}.
     */
    private static volatile @Nullable Cf cf;

    private static volatile boolean cfInitAttempted;

    /**
     * Bind the CoreFoundation entry points, at most once. A failure leaves {@link #cf} null, which
     * every caller reads as "no preferences available". {@code CFIndex} and {@code CFTypeID} are 8
     * bytes on LP64, {@code CFStringEncoding} is a {@code UInt32}, and {@code Boolean} is an
     * {@code unsigned char}.
     */
    @SuppressWarnings("restricted")
    private static @Nullable Cf coreFoundation() {
        if (cfInitAttempted) return cf;
        synchronized (MacPrefs.class) {
            if (cfInitAttempted) return cf;
            // Flag written LAST (finally): the fast path reads it unsynchronized, so an early
            // write would publish attempted=true with no handles to a concurrent caller
            // — benign here beyond one spurious empty read, but same idiom as
            // TerminalSize).
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global());

                var typeIdOfRef = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
                var typeIdConstant = FunctionDescriptor.of(ValueLayout.JAVA_LONG);

                cf = new Cf(
                        linker.downcallHandle(
                                lookup.findOrThrow("CFStringCreateWithCString"),
                                FunctionDescriptor.of(
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                lookup.findOrThrow("CFPreferencesCopyAppValue"),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                        linker.downcallHandle(lookup.findOrThrow("CFArrayGetCount"), typeIdOfRef),
                        linker.downcallHandle(
                                lookup.findOrThrow("CFArrayGetValueAtIndex"),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                        linker.downcallHandle(
                                lookup.findOrThrow("CFDictionaryGetValue"),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                        linker.downcallHandle(lookup.findOrThrow("CFGetTypeID"), typeIdOfRef),
                        linker.downcallHandle(lookup.findOrThrow("CFStringGetTypeID"), typeIdConstant),
                        linker.downcallHandle(lookup.findOrThrow("CFArrayGetTypeID"), typeIdConstant),
                        linker.downcallHandle(lookup.findOrThrow("CFDictionaryGetTypeID"), typeIdConstant),
                        linker.downcallHandle(
                                lookup.findOrThrow("CFStringGetCStringPtr"),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                lookup.findOrThrow("CFStringGetCString"),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_BOOLEAN,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_INT)),
                        linker.downcallHandle(lookup.findOrThrow("CFStringGetLength"), typeIdOfRef),
                        linker.downcallHandle(
                                lookup.findOrThrow("CFStringGetMaximumSizeForEncoding"),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                lookup.findOrThrow("CFRelease"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)));
            } catch (Throwable ignored) {
                // Missing framework, denied native access, renamed symbol — no preferences.
            } finally {
                cfInitAttempted = true;
            }
            return cf;
        }
    }

    /**
     * A +1 CFString the caller must {@link #release}. {@code CFStringCreateWithCString} copies the
     * bytes (unlike the {@code NoCopy} variant), so the scratch arena can close immediately.
     */
    private static @Nullable MemorySegment newCfString(Cf cf, String text) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment utf8 = arena.allocateFrom(text);
            MemorySegment ref =
                    (MemorySegment) cf.stringCreateWithCString().invokeExact(MemorySegment.NULL, utf8, UTF8);
            return isNull(ref) ? null : ref;
        }
    }

    /** A +1 preference value for {@code key} in {@code appId}; {@code null} when unset. */
    private static @Nullable MemorySegment copyAppValue(Cf cf, String key, MemorySegment appId) throws Throwable {
        MemorySegment keyRef = newCfString(cf, key);
        if (keyRef == null) return null;
        try {
            MemorySegment value = (MemorySegment) cf.preferencesCopyAppValue().invokeExact(keyRef, appId);
            return isNull(value) ? null : value;
        } finally {
            release(cf, keyRef);
        }
    }

    /**
     * A dictionary entry as text; {@code null} when the key is absent or the value is not a
     * CFString. The value is borrowed from the dictionary and is deliberately not released.
     */
    private static @Nullable String dictionaryString(Cf cf, MemorySegment dictionary, String key) throws Throwable {
        MemorySegment keyRef = newCfString(cf, key);
        if (keyRef == null) return null;
        try {
            MemorySegment value = (MemorySegment) cf.dictionaryGetValue().invokeExact(dictionary, keyRef);
            long stringTypeId = (long) cf.stringGetTypeId().invokeExact();
            if (isNull(value) || typeIdOf(cf, value) != stringTypeId) return null;
            return stringValue(cf, value);
        } finally {
            release(cf, keyRef);
        }
    }

    /**
     * A CFString's UTF-8 text. {@code CFStringGetCStringPtr} hands back the internal buffer when the
     * string already happens to be stored in the requested encoding — CoreFoundation explicitly
     * promises nothing, so the copying fallback is not optional.
     */
    @SuppressWarnings("restricted")
    private static @Nullable String stringValue(Cf cf, MemorySegment cfString) throws Throwable {
        long length = (long) cf.stringGetLength().invokeExact(cfString);
        if (length <= 0) return null;
        long maxBytes = (long) cf.stringGetMaximumSizeForEncoding().invokeExact(length, UTF8);
        if (maxBytes <= 0 || maxBytes > MAX_PREF_STRING_BYTES) return null;

        MemorySegment direct = (MemorySegment) cf.stringGetCStringPtr().invokeExact(cfString, UTF8);
        if (!isNull(direct)) {
            // An ADDRESS return carries no size, so the segment needs a window before it can be
            // read. Bound it by the encoder's own maximum plus the NUL rather than reinterpreting
            // the whole address space.
            return direct.reinterpret(maxBytes + 1).getString(0);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(maxBytes + 1);
            boolean copied = (boolean) cf.stringGetCString().invokeExact(cfString, buffer, maxBytes + 1, UTF8);
            return copied ? buffer.getString(0) : null;
        }
    }

    private static long typeIdOf(Cf cf, MemorySegment ref) throws Throwable {
        return (long) cf.getTypeId().invokeExact(ref);
    }

    /** Release a +1 reference. A failure here leaks one small object; it is never worth throwing. */
    private static void release(Cf cf, MemorySegment ref) {
        try {
            cf.release().invokeExact(ref);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static boolean isNull(MemorySegment ref) {
        return ref == null || ref.address() == 0L;
    }

    private static @Nullable String trimToNull(@Nullable String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
