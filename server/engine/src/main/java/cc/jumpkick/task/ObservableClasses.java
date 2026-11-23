// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.TreeContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * What a test JVM can observe of a workspace jar or classes tree: every file by content, each
 * {@code .class} without its {@code LineNumberTable} and {@code SourceDebugExtension}. A comment,
 * blank-line or Javadoc edit moves only those, so it leaves this token where it was; a body,
 * constant or resource edit moves it. Local variable tables and parameter names stay in, as
 * frameworks read them.
 *
 * <p>The token cannot see a test that asserts a line number of a sibling's class, such as a stack
 * trace's {@code Foo.java:42}: such a suite replays its earlier result after a comment edit.
 */
public final class ObservableClasses {

    /** Prefix of every token; the memo namespace moves with what {@link #classDigest} keeps. */
    public static final String PREFIX = "observable:";

    private static final String MEMO_NAMESPACE = "observable.1:";

    private ObservableClasses() {}

    /** Reads the bytes of one {@code .class} file whose content sha a tree's digests name. */
    @FunctionalInterface
    public interface ClassBytes {
        byte[] read(String rel, String sha) throws IOException;
    }

    /**
     * The token of {@code entry} on disk, whose content identity is {@code identity} ({@link
     * ClasspathFingerprint#entry}): a jar or a classes tree by what its classes do, anything else
     * (a missing entry, a lone file) as {@code identity} itself.
     */
    public static String onDisk(Path entry, String identity) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        if (identity.startsWith("dir:") && ProjectedFiles.isDirectory(abs)) {
            Map<String, String> digests = ClasspathFingerprint.digests(abs);
            if (!ClasspathFingerprint.entryFromOutputDigests(digests).equals(identity))
                throw new IOException(abs + " changed while it was read");
            return tree(digests, (rel, sha) -> {
                Path file = abs.resolve(rel);
                Path blob = ProjectedFiles.blob(file);
                return verified(Files.readAllBytes(blob != null ? blob : file), sha, file);
            });
        }
        if (identity.startsWith("file:") && Files.isRegularFile(abs) && isArchive(abs)) return jar(identity, abs);
        return identity;
    }

    /**
     * {@code bytes} when they are the content {@code sha} names; a file that moved since its digest
     * was taken fails the read, so no token is taken over bytes its identity does not name.
     */
    public static byte[] verified(byte[] bytes, String sha, Path file) throws IOException {
        if (!Hashing.sha256Hex(bytes).equals(sha)) throw new IOException(file + " changed while it was read");
        return bytes;
    }

    /**
     * The token of the jar at {@code jar}, whose content identity is {@code file:<sha>}. A jar
     * whose bytes are not that content before and after the read, or that is no archive, fails it.
     */
    public static String jar(String identity, Path jar) throws IOException {
        String hit = AbiMemo.get(MEMO_NAMESPACE + identity);
        if (hit != null) return hit;
        String sha = identity.substring(identity.indexOf(':') + 1);
        verified(Files.readAllBytes(jar), sha, jar);
        Map<String, String> lines = new TreeMap<>();
        // The central directory, not the local headers: a file that is no archive fails to open
        // rather than reading as one with no entries.
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                lines.put(e.getName(), e.getName().endsWith(".class") ? classDigest(bytes) : Hashing.sha256Hex(bytes));
            }
        }
        verified(Files.readAllBytes(jar), sha, jar);
        String token = PREFIX + digestOf(lines);
        AbiMemo.put(MEMO_NAMESPACE + identity, token);
        return token;
    }

    /**
     * The token of a classes tree whose files are {@code digests} (tree-relative path to content
     * sha, as {@link ClasspathFingerprint#digests} reads them); {@code bytes} reads a class's bytes.
     */
    public static String tree(Map<String, String> digests, ClassBytes bytes) throws IOException {
        String identity = ClasspathFingerprint.entryFromOutputDigests(digests);
        String hit = AbiMemo.get(MEMO_NAMESPACE + identity);
        if (hit != null) return hit;
        Map<String, String> lines = new TreeMap<>();
        for (Map.Entry<String, String> file : digests.entrySet()) {
            // The files the content identity is taken over, and no others.
            String rel = file.getKey().replace('\\', '/');
            String sha = file.getValue();
            if (rel.isEmpty() || !TreeContent.isContent(rel) || sha == null || sha.isBlank()) continue;
            lines.put(rel, rel.endsWith(".class") ? classDigest(bytes.read(rel, sha)) : sha);
        }
        String token = PREFIX + digestOf(lines);
        AbiMemo.put(MEMO_NAMESPACE + identity, token);
        return token;
    }

    /**
     * The digest of a class file without its line numbers and source debug extension; of its raw
     * bytes when ASM cannot read it.
     */
    static String classDigest(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new WithoutLines(writer), 0);
            return Hashing.sha256Hex(writer.toByteArray());
        } catch (RuntimeException unreadable) {
            return Hashing.sha256Hex(bytes);
        }
    }

    private static String digestOf(Map<String, String> lines) {
        MessageDigest md = Hashing.newSha256();
        for (Map.Entry<String, String> line : lines.entrySet()) {
            md.update((line.getKey() + ":" + line.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return Hashing.hex(md.digest());
    }

    private static boolean isArchive(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    /** Copies a class, dropping its line number entries and its SMAP. */
    private static final class WithoutLines extends ClassVisitor {
        WithoutLines(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visitSource(@Nullable String source, @Nullable String debug) {
            super.visitSource(source, null);
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                @Nullable String signature,
                String @Nullable [] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitLineNumber(int line, Label start) {}
            };
        }
    }
}
