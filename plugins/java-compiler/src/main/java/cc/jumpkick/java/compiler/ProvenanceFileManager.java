// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import javax.tools.FileObject;
import javax.tools.ForwardingFileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.jspecify.annotations.Nullable;

/**
 * Records what annotation processors write and read back, from where javac's Filer meets the file
 * manager: every file the Filer opens goes through {@code getJavaFileForOutputForOriginatingFiles} or
 * {@code getFileForOutputForOriginatingFiles}, carrying the source files of the elements the
 * processor named as its origins.
 *
 * <p>The Filer asks for a file it is to <em>read</em> from an output location through the same call,
 * with no origins, so the call alone cannot tell a write from a read. The file it hands back can: a
 * write opens it for output, a read for input. A file javac re-reads after writing it, a generated
 * source it compiles next round, is the processor's own output and not a read-back, so input opened
 * on a file already opened for output is not counted.
 */
final class ProvenanceFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    private final ApProvenance provenance;

    /**
     * The compile's main source root, the directory of its {@code module-info.java}; null for a
     * compile without one. What a processor asks the Filer to read from {@code SOURCE_PATH} is
     * looked up here when the manager's own source path has no such file.
     */
    private final @Nullable Path sourceRoot;

    /** Makes a file object for a path, as the standard manager underneath does. */
    private final Function<Path, FileObject> files;

    /**
     * Whether javac's Filer opens its files through the plain output calls (JDK 17): it then names
     * no origins, so what a processor writes is recorded with none, which marks the compile
     * aggregating and lists the file for the next compile to delete.
     */
    private final boolean legacyFiler;

    ProvenanceFileManager(
            JavaFileManager fm, ApProvenance provenance, @Nullable Path sourceRoot, Function<Path, FileObject> files) {
        this(fm, provenance, sourceRoot, files, !ZincJavaCompiler.FILER_PROVENANCE);
    }

    ProvenanceFileManager(
            JavaFileManager fm,
            ApProvenance provenance,
            @Nullable Path sourceRoot,
            Function<Path, FileObject> files,
            boolean legacyFiler) {
        super(fm);
        this.provenance = provenance;
        this.sourceRoot = sourceRoot;
        this.files = files;
        this.legacyFiler = legacyFiler;
    }

    /**
     * The Filer's output call on JDK 18 and later. The worker is built for JDK 17, whose {@code
     * JavaFileManager} lacks it, so this is a plain method of the same signature: javac 18+ calls it
     * as the override it is, and javac 17 never does (there a compile with processors goes whole).
     */
    public JavaFileObject getJavaFileForOutputForOriginatingFiles(
            Location location, String className, JavaFileObject.Kind kind, FileObject... originatingFiles)
            throws IOException {
        JavaFileObject file =
                (JavaFileObject) Jdk18.call(Jdk18.JAVA_FILE, fileManager, location, className, kind, originatingFiles);
        return new FilerJavaFile(file, new Use(provenance, originatingFiles));
    }

    /** The Filer's resource output call on JDK 18 and later; see the method above. */
    public FileObject getFileForOutputForOriginatingFiles(
            Location location, String pkg, String relativeName, FileObject... originatingFiles) throws IOException {
        FileObject file =
                (FileObject) Jdk18.call(Jdk18.FILE, fileManager, location, pkg, relativeName, originatingFiles);
        return new FilerFile(file, new Use(provenance, originatingFiles));
    }

    /** The delegate's JDK 18 output calls, looked up the first time javac makes one. */
    private static final class Jdk18 {
        static final MethodHandle JAVA_FILE = find(
                "getJavaFileForOutputForOriginatingFiles",
                MethodType.methodType(
                        JavaFileObject.class,
                        Location.class,
                        String.class,
                        JavaFileObject.Kind.class,
                        FileObject[].class));
        static final MethodHandle FILE = find(
                "getFileForOutputForOriginatingFiles",
                MethodType.methodType(
                        FileObject.class, Location.class, String.class, String.class, FileObject[].class));

        private static MethodHandle find(String name, MethodType type) {
            try {
                // Fixed arity: the origins array is one argument, not a varargs list to spread.
                return MethodHandles.publicLookup()
                        .findVirtual(JavaFileManager.class, name, type)
                        .asFixedArity();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("javac called " + name + " on a JDK without it", e);
            }
        }

        static Object call(MethodHandle h, JavaFileManager fm, Object... args) throws IOException {
            try {
                return h.invokeWithArguments(prepend(fm, args));
            } catch (IOException | RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        private static Object[] prepend(Object first, Object[] rest) {
            Object[] all = new Object[rest.length + 1];
            all[0] = first;
            System.arraycopy(rest, 0, all, 1, rest.length);
            return all;
        }
    }

    @Override
    public @Nullable JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, @Nullable FileObject sibling)
            throws IOException {
        JavaFileObject file =
                super.getJavaFileForOutput(location, className, kind, sibling == null ? null : unwrap(sibling));
        // On JDK 17 a generated source comes through here; javac's own class files never go to
        // SOURCE_OUTPUT, so only the Filer's writes are recorded.
        if (legacyFiler && file != null && location == StandardLocation.SOURCE_OUTPUT) {
            return new FilerJavaFile(file, new Use(provenance, new FileObject[0]));
        }
        return file;
    }

    @Override
    public @Nullable FileObject getFileForOutput(
            Location location, String pkg, String relativeName, @Nullable FileObject sibling) throws IOException {
        FileObject file = super.getFileForOutput(location, pkg, relativeName, sibling == null ? null : unwrap(sibling));
        // On JDK 17 a processor's resource (a META-INF/services file, a registry) comes through here.
        boolean filerOutput = location == StandardLocation.CLASS_OUTPUT || location == StandardLocation.SOURCE_OUTPUT;
        if (legacyFiler && file != null && filerOutput)
            return new FilerFile(file, new Use(provenance, new FileObject[0]));
        return file;
    }

    /**
     * A read the Filer makes from an output location through the input call: no JDK this runs on
     * does, but one that did would still be counted.
     */
    @Override
    public @Nullable FileObject getFileForInput(Location location, String pkg, String relativeName) throws IOException {
        FileObject file = super.getFileForInput(location, pkg, relativeName);
        if (file != null && location.isOutputLocation()) provenance.recordRead(file);
        if (file == null && location == StandardLocation.SOURCE_PATH) file = fromSourceRoot(pkg, relativeName);
        return file;
    }

    /**
     * A source a processor reads through the Filer, from the compile's own source root. jk passes
     * javac no {@code -sourcepath}: given one, javac compiles whatever it finds there on its own,
     * behind the incremental compile's back. Maven does pass one, and processors rely on it:
     * Helidon's codegen reads {@code module-info.java} this way to learn the module's name, and
     * without it names the module "unnamed" and files its service registry under that name. javac
     * itself never asks for a source through this call, so answering it reaches processors alone.
     */
    private @Nullable FileObject fromSourceRoot(String pkg, String relativeName) {
        Path root = sourceRoot;
        if (root == null) return null;
        Path dir = pkg.isEmpty() ? root : root.resolve(pkg.replace('.', '/'));
        Path candidate = dir.resolve(relativeName).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) return null;
        return files.apply(candidate);
    }

    // The file objects handed out above come back as arguments, and the manager underneath downcasts
    // what it is given (inferBinaryName, contains, isSameFile all do), so each is unwrapped first.

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return super.inferBinaryName(location, (JavaFileObject) unwrap(file));
    }

    @Override
    public boolean contains(Location location, FileObject file) throws IOException {
        return super.contains(location, unwrap(file));
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return super.isSameFile(unwrap(a), unwrap(b));
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject file) throws IOException {
        return super.getLocationForModule(location, (JavaFileObject) unwrap(file));
    }

    private static FileObject unwrap(FileObject file) {
        if (file instanceof FilerJavaFile filer) return filer.bare();
        if (file instanceof FilerFile filer) return filer.bare();
        return file;
    }

    /** One file the Filer handed out: written, it is generated; read before that, it was read back. */
    private static final class Use {
        private final ApProvenance provenance;
        private final FileObject[] originating;
        private boolean written;

        Use(ApProvenance provenance, FileObject[] originating) {
            this.provenance = provenance;
            this.originating = originating.clone();
        }

        synchronized void writing(FileObject file) {
            if (!written) provenance.record(file, originating);
            written = true;
        }

        synchronized void reading(FileObject file) {
            if (!written) provenance.recordRead(file);
        }
    }

    private static final class FilerJavaFile extends ForwardingJavaFileObject<JavaFileObject> {
        private final Use use;

        FilerJavaFile(JavaFileObject file, Use use) {
            super(file);
            this.use = use;
        }

        JavaFileObject bare() {
            return fileObject;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            use.writing(fileObject);
            return super.openOutputStream();
        }

        @Override
        public Writer openWriter() throws IOException {
            use.writing(fileObject);
            return super.openWriter();
        }

        @Override
        public InputStream openInputStream() throws IOException {
            use.reading(fileObject);
            return super.openInputStream();
        }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            use.reading(fileObject);
            return super.openReader(ignoreEncodingErrors);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            use.reading(fileObject);
            return super.getCharContent(ignoreEncodingErrors);
        }
    }

    private static final class FilerFile extends ForwardingFileObject<FileObject> {
        private final Use use;

        FilerFile(FileObject file, Use use) {
            super(file);
            this.use = use;
        }

        FileObject bare() {
            return fileObject;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            use.writing(fileObject);
            return super.openOutputStream();
        }

        @Override
        public Writer openWriter() throws IOException {
            use.writing(fileObject);
            return super.openWriter();
        }

        @Override
        public InputStream openInputStream() throws IOException {
            use.reading(fileObject);
            return super.openInputStream();
        }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            use.reading(fileObject);
            return super.openReader(ignoreEncodingErrors);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            use.reading(fileObject);
            return super.getCharContent(ignoreEncodingErrors);
        }
    }
}
