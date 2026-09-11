// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where one {@link IdeGenerator} run puts its files. {@link #writing()} writes them; {@link
 * #preview()} only records what would be written, so a caller can list the file set without
 * touching the tree. Either way the generator's work is the same code, and {@link #files()} is the
 * same list.
 */
public final class IdeOutput {

    private final boolean write;
    private final List<Path> files = new ArrayList<>();
    private final List<Path> sdkTables = new ArrayList<>();

    private IdeOutput(boolean write) {
        this.write = write;
    }

    /** Writes every file and creates every directory. */
    public static IdeOutput writing() {
        return new IdeOutput(true);
    }

    /** Records the file list; the tree is left as it is. */
    public static IdeOutput preview() {
        return new IdeOutput(false);
    }

    public boolean writes() {
        return write;
    }

    /** One project file, UTF-8; the parent directory is created on demand. */
    public void file(Path file, String content) throws IOException {
        if (write) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
        files.add(file);
    }

    /** A directory the IDE expects to exist (an annotation-processor output root), with no file in it. */
    public void directory(Path dir) throws IOException {
        if (write) Files.createDirectories(dir);
    }

    /** Upsert SDKs into the IDE's global tables; a preview registers nothing. */
    public void registerSdks(IntellijSdkRegistrar registrar, List<IntellijSdkRegistrar.SdkEntry> sdks) {
        if (write) sdkTables.addAll(registrar.register(sdks));
    }

    /** Files produced so far, in emit order. */
    public List<Path> files() {
        return List.copyOf(files);
    }

    /** The IDE JDK tables actually rewritten (empty for a preview or when no IDE config is present). */
    public List<Path> sdkTables() {
        return List.copyOf(sdkTables);
    }
}
