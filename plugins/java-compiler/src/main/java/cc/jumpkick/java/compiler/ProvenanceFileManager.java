// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import javax.tools.FileObject;
import javax.tools.ForwardingFileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
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

    ProvenanceFileManager(JavaFileManager fm, ApProvenance provenance) {
        super(fm);
        this.provenance = provenance;
    }

    @Override
    public JavaFileObject getJavaFileForOutputForOriginatingFiles(
            Location location, String className, JavaFileObject.Kind kind, FileObject... originatingFiles)
            throws IOException {
        JavaFileObject file =
                super.getJavaFileForOutputForOriginatingFiles(location, className, kind, originatingFiles);
        return new FilerJavaFile(file, new Use(provenance, originatingFiles));
    }

    @Override
    public FileObject getFileForOutputForOriginatingFiles(
            Location location, String pkg, String relativeName, FileObject... originatingFiles) throws IOException {
        FileObject file = super.getFileForOutputForOriginatingFiles(location, pkg, relativeName, originatingFiles);
        return new FilerFile(file, new Use(provenance, originatingFiles));
    }

    @Override
    public @Nullable JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, @Nullable FileObject sibling)
            throws IOException {
        return super.getJavaFileForOutput(location, className, kind, sibling == null ? null : unwrap(sibling));
    }

    @Override
    public @Nullable FileObject getFileForOutput(
            Location location, String pkg, String relativeName, @Nullable FileObject sibling) throws IOException {
        return super.getFileForOutput(location, pkg, relativeName, sibling == null ? null : unwrap(sibling));
    }

    /**
     * A read the Filer makes from an output location through the input call: no JDK this runs on
     * does, but one that did would still be counted.
     */
    @Override
    public @Nullable FileObject getFileForInput(Location location, String pkg, String relativeName) throws IOException {
        FileObject file = super.getFileForInput(location, pkg, relativeName);
        if (file != null && location.isOutputLocation()) provenance.recordRead(file);
        return file;
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
