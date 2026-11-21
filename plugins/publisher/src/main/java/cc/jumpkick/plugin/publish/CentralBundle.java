// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.model.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipOutputStream;

/**
 * The zip the Central Portal's upload API takes: a Maven repository layout holding every artifact
 * of one release with its detached GPG signature and its {@code .md5} / {@code .sha1} checksums.
 * The Portal validates the layout — jar, POM, sources jar, javadoc jar, a signature on each —
 * and refuses a release short of any of them, so the caller assembles the four before it gets
 * here. Entry order is fixed and every entry carries the pinned archive instant, so two bundles
 * of the same bytes are the same zip.
 */
public final class CentralBundle {

    private CentralBundle() {}

    /** The bundle file, its size in bytes, and its entry paths in the order they were written. */
    public record Bundle(Path zip, long size, List<String> entries) {
        public Bundle {
            Objects.requireNonNull(zip, "zip");
            entries = List.copyOf(entries);
        }
    }

    /**
     * Write the bundle for {@code project}'s {@code artifacts} to {@code target}: each artifact, its
     * {@code .asc} from {@code signer}, and the two checksums the Portal reads. Signatures carry no
     * checksums. The zip goes straight to disk, so the heap never holds a copy of the release.
     */
    public static Bundle build(
            Project project, Iterable<MavenPublisher.Artifact> artifacts, GpgSigner signer, Path target)
            throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(signer, "signer");
        String prefix = project.group().replace('.', '/') + "/" + project.name() + "/" + project.version() + "/";
        String stem = project.name() + "-" + project.version();
        List<String> entries = new ArrayList<>();
        try (ZipOutputStream zip = DeterministicZip.newArchive(target)) {
            for (MavenPublisher.Artifact a : artifacts) {
                String path = prefix + stem + a.filenameSuffix();
                write(zip, entries, path, a.body());
                write(zip, entries, path + ".asc", signer.signArmored(a.body()));
                Checksums.Set sums = Checksums.of(a.body());
                write(zip, entries, path + ".md5", sums.md5().getBytes(StandardCharsets.US_ASCII));
                write(zip, entries, path + ".sha1", sums.sha1().getBytes(StandardCharsets.US_ASCII));
            }
        }
        return new Bundle(target, Files.size(target), entries);
    }

    private static void write(ZipOutputStream zip, List<String> entries, String path, byte[] body) throws IOException {
        DeterministicZip.PINNED.writeEntry(zip, path, body);
        entries.add(path);
    }
}
