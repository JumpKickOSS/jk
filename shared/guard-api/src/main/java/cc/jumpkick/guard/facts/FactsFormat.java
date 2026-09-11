// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import cc.jumpkick.host.Hashing;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The on-disk shape of a {@link FactsIndex}: a small header (magic, version, body digest, stamps),
 * then a string table, then the class table with every string as a table index. About 3 kB per
 * class against 15 kB for a line-oriented form; the header reads without the body, so an unchanged
 * module costs a stat and a few hundred bytes.
 */
public final class FactsFormat {

    static final int MAGIC = 0x4A4B4746; // JKGF
    static final int VERSION = 1;

    private FactsFormat() {}

    /** Header only: digest and stamps. Empty when the file is absent or not this format. */
    public static Optional<Header> readHeader(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            Header h = readHeader(in);
            return Optional.ofNullable(h);
        }
    }

    public static FactsIndex read(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            return read(raw);
        }
    }

    public static FactsIndex read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        Header h = readHeader(in);
        if (h == null) throw new IOException("not a facts index");
        int strings = in.readInt();
        String[] table = new String[strings];
        for (int i = 0; i < strings; i++) table[i] = in.readUTF();
        Reader r = new Reader(in, table);
        int n = in.readInt();
        Map<String, ClassFacts> classes = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            ClassFacts c = r.classFacts();
            classes.put(c.name(), c);
        }
        return new FactsIndex(classes, h.stamps(), h.bodyDigest());
    }

    public static void write(Path file, FactsIndex index) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        byte[] bytes = toBytes(index);
        // A staging name of this writer's own: two writers of one index never share a temp file,
        // so neither can rename or delete the other's.
        Path tmp = Files.createTempFile(dir, file.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    /** Serialize; the body digest in the header is recomputed from the class table. */
    public static byte[] toBytes(FactsIndex index) throws IOException {
        Body body = Body.of(index);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.bytes.length + 1024);
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(MAGIC);
        d.writeInt(VERSION);
        d.writeUTF(body.digest);
        d.writeInt(index.stamps().size());
        for (var e : new TreeMap<>(index.stamps()).entrySet()) {
            d.writeUTF(e.getKey());
            d.writeUTF(e.getValue());
        }
        d.writeInt(body.table.size());
        for (String s : body.table) d.writeUTF(s);
        d.write(body.bytes);
        d.flush();
        return out.toByteArray();
    }

    /** The content digest a freshly built index would carry: what the lane keys on. */
    public static String digestOf(FactsIndex index) throws IOException {
        return Body.of(index).digest;
    }

    public record Header(int version, String bodyDigest, Map<String, String> stamps) {}

    private static @Nullable Header readHeader(DataInputStream in) throws IOException {
        int magic;
        try {
            magic = in.readInt();
        } catch (IOException eof) {
            return null;
        }
        if (magic != MAGIC) return null;
        int version = in.readInt();
        if (version != VERSION) return null;
        String digest = in.readUTF();
        int n = in.readInt();
        Map<String, String> stamps = new TreeMap<>();
        for (int i = 0; i < n; i++) stamps.put(in.readUTF(), in.readUTF());
        return new Header(version, digest, stamps);
    }

    // ---- body ---------------------------------------------------------------------------------

    private static final class Body {
        final List<String> table;
        final byte[] bytes;
        final String digest;

        private Body(List<String> table, byte[] bytes, String digest) {
            this.table = table;
            this.bytes = bytes;
            this.digest = digest;
        }

        static Body of(FactsIndex index) throws IOException {
            Writer w = new Writer();
            w.out.writeInt(index.classes().size());
            for (ClassFacts c : new TreeMap<>(index.classes()).values()) w.classFacts(c);
            w.out.flush();
            byte[] body = w.buf.toByteArray();
            // Digest the body plus the string table in index order: the two together are the content.
            StringBuilder tableText = new StringBuilder();
            for (String s : w.table.keySet()) tableText.append(s).append('\n');
            String digest = Hashing.sha256Hex(concat(tableText.toString().getBytes(StandardCharsets.UTF_8), body));
            return new Body(new ArrayList<>(w.table.keySet()), body, digest);
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] out = new byte[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }

    private static final class Writer {
        final Map<String, Integer> table = new LinkedHashMap<>();
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
        final DataOutputStream out = new DataOutputStream(buf);

        void str(@Nullable String s) throws IOException {
            if (s == null) {
                out.writeInt(-1);
                return;
            }
            Integer i = table.get(s);
            if (i == null) {
                i = table.size();
                table.put(s, i);
            }
            out.writeInt(i);
        }

        void strings(List<String> list) throws IOException {
            out.writeInt(list.size());
            for (String s : list) str(s);
        }

        void classFacts(ClassFacts c) throws IOException {
            str(c.name());
            out.writeInt(c.access());
            str(c.superName());
            strings(c.interfaces());
            str(c.sourceFile());
            annotations(c.annotations());
            out.writeInt(c.fields().size());
            for (FieldFacts f : c.fields()) {
                str(f.name());
                str(f.desc());
                out.writeInt(f.access());
                str(f.constantValue());
                annotations(f.annotations());
            }
            out.writeInt(c.methods().size());
            for (MethodFacts m : c.methods()) {
                str(m.name());
                str(m.desc());
                out.writeInt(m.access());
                annotations(m.annotations());
                out.writeInt(m.parameterAnnotations().size());
                for (List<AnnotationFacts> p : m.parameterAnnotations()) annotations(p);
                out.writeInt(m.calls().size());
                for (CallSite s : m.calls()) {
                    str(s.owner());
                    str(s.name());
                    str(s.desc());
                    out.writeInt(s.line());
                    str(s.literalBefore());
                    out.writeInt(s.count());
                    strings(s.literals());
                }
                out.writeInt(m.fieldRefs().size());
                for (FieldRef r : m.fieldRefs()) {
                    str(r.owner());
                    str(r.name());
                    str(r.desc());
                    out.writeInt(r.line());
                    out.writeBoolean(r.write());
                    out.writeInt(r.count());
                }
                out.writeInt(m.branches());
                out.writeInt(m.firstLine());
            }
            strings(new ArrayList<>(c.typeRefs()));
        }

        void annotations(List<AnnotationFacts> list) throws IOException {
            out.writeInt(list.size());
            for (AnnotationFacts a : list) {
                str(a.desc());
                out.writeBoolean(a.runtimeVisible());
                out.writeInt(a.values().size());
                for (var e : new TreeMap<>(a.values()).entrySet()) {
                    str(e.getKey());
                    strings(e.getValue());
                }
            }
        }
    }

    private static final class Reader {
        final DataInputStream in;
        final String[] table;

        Reader(DataInputStream in, String[] table) {
            this.in = in;
            this.table = table;
        }

        @Nullable
        String str() throws IOException {
            int i = in.readInt();
            return i < 0 ? null : table[i];
        }

        String req() throws IOException {
            String s = str();
            if (s == null) throw new IOException("corrupt facts index: null string");
            return s;
        }

        List<String> strings() throws IOException {
            int n = in.readInt();
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(req());
            return out;
        }

        ClassFacts classFacts() throws IOException {
            String name = req();
            int access = in.readInt();
            String superName = str();
            List<String> interfaces = strings();
            String sourceFile = str();
            List<AnnotationFacts> annotations = annotations();
            int nf = in.readInt();
            List<FieldFacts> fields = new ArrayList<>(nf);
            for (int i = 0; i < nf; i++) {
                fields.add(new FieldFacts(req(), req(), in.readInt(), str(), annotations()));
            }
            int nm = in.readInt();
            List<MethodFacts> methods = new ArrayList<>(nm);
            for (int i = 0; i < nm; i++) {
                String mn = req();
                String md = req();
                int ma = in.readInt();
                List<AnnotationFacts> mann = annotations();
                int np = in.readInt();
                List<List<AnnotationFacts>> pann = new ArrayList<>(np);
                for (int p = 0; p < np; p++) pann.add(annotations());
                int nc = in.readInt();
                List<CallSite> calls = new ArrayList<>(nc);
                for (int c = 0; c < nc; c++) {
                    calls.add(new CallSite(req(), req(), req(), in.readInt(), str(), in.readInt(), strings()));
                }
                int nr = in.readInt();
                List<FieldRef> refs = new ArrayList<>(nr);
                for (int r = 0; r < nr; r++) {
                    refs.add(new FieldRef(req(), req(), req(), in.readInt(), in.readBoolean(), in.readInt()));
                }
                int branches = in.readInt();
                int firstLine = in.readInt();
                methods.add(new MethodFacts(mn, md, ma, mann, pann, calls, refs, branches, firstLine));
            }
            Set<String> refs = new TreeSet<>(strings());
            return new ClassFacts(name, access, superName, interfaces, sourceFile, annotations, fields, methods, refs);
        }

        List<AnnotationFacts> annotations() throws IOException {
            int n = in.readInt();
            List<AnnotationFacts> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String desc = req();
                boolean visible = in.readBoolean();
                int nv = in.readInt();
                Map<String, List<String>> values = new TreeMap<>();
                for (int v = 0; v < nv; v++) values.put(req(), strings());
                out.add(new AnnotationFacts(desc, visible, values));
            }
            return out;
        }
    }
}
