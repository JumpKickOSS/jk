// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.DeterministicTar;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.TreeContent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipOutputStream;

/**
 * A distribution archive of a packaged layout: a {@code .tar.gz} or {@code .zip} holding one
 * top-level directory, entries in name order, every entry at one fixed time and with no owner. In a
 * tar a file under the top's {@code bin/}, or one executable on disk, is {@code 0755}, every other
 * file {@code 0644}, a directory {@code 0755}; a zip carries no modes.
 */
public final class DistArchive {

    private DistArchive() {}

    /** The container, chosen by the archive's file name. */
    public enum Format {
        TAR_GZ,
        ZIP;

        /** {@link #ZIP} for a {@code .zip}, else {@link #TAR_GZ}. */
        public static Format of(Path archive) {
            return String.valueOf(archive.getFileName())
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".zip")
                    ? ZIP
                    : TAR_GZ;
        }
    }

    /** One file of the archive: its {@code /}-separated name below the top directory, its bytes, its mode. */
    public record Entry(String name, Path file, boolean executable) {}

    /**
     * Every content file under {@code dir} ({@link TreeContent}), named {@code prefix} plus its path
     * below {@code dir}; links to files are followed.
     */
    public static List<Entry> tree(Path dir, String prefix) throws IOException {
        List<Entry> out = new ArrayList<>();
        TreeContent.forEach(dir, (rel, file, attrs) -> {
            String name = prefix + rel;
            out.add(new Entry(name, file, name.startsWith("bin/") || executableOnDisk(file)));
        });
        return out;
    }

    /** {@code file} as the entry {@code name}. */
    public static Entry file(Path file, String name) throws IOException {
        return new Entry(name, file, name.startsWith("bin/") || executableOnDisk(file));
    }

    /**
     * Write {@code entries} under {@code top}/ into {@code archive}, its format by its name, every
     * entry at {@code epochSeconds}. The archive is replaced whole, never left half-written.
     */
    public static void write(Path archive, String top, List<Entry> entries, long epochSeconds) throws IOException {
        TreeMap<String, Entry> files = new TreeMap<>();
        for (Entry e : entries) files.put(top + "/" + e.name(), e);
        List<String> names = new ArrayList<>(files.keySet());
        Set<String> dirs = new HashSet<>();
        for (String name : files.keySet()) {
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                dirs.add(name.substring(0, slash + 1));
            }
        }
        if (files.isEmpty()) dirs.add(top + "/");
        names.addAll(dirs);
        names.sort(Comparator.naturalOrder());

        Path staging = AtomicWrites.staging(archive);
        try {
            switch (Format.of(archive)) {
                case TAR_GZ -> writeTar(staging, names, files, epochSeconds);
                case ZIP -> writeZip(staging, names, files, epochSeconds);
            }
            AtomicWrites.moveInto(staging, archive);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void writeTar(Path target, List<String> names, TreeMap<String, Entry> files, long epochSeconds)
            throws IOException {
        OutputStream raw = DeterministicZip.archiveStream(target);
        try (DeterministicTar tar = new DeterministicTar(new GZIPOutputStream(raw, 1 << 16), epochSeconds)) {
            for (String name : names) {
                Entry e = files.get(name);
                if (e == null) tar.directory(name, 0755);
                else tar.file(name, e.executable() ? 0755 : 0644, e.file());
            }
        }
    }

    private static void writeZip(Path target, List<String> names, TreeMap<String, Entry> files, long epochSeconds)
            throws IOException {
        DeterministicZip zip = new DeterministicZip(epochSeconds);
        try (ZipOutputStream out = DeterministicZip.newArchive(target)) {
            for (String name : names) {
                Entry e = files.get(name);
                if (e == null) {
                    out.putNextEntry(zip.entry(name));
                    out.closeEntry();
                } else {
                    zip.writeEntry(out, name, e.file());
                }
            }
        }
    }

    private static boolean executableOnDisk(Path file) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        return view != null && view.readAttributes().permissions().contains(PosixFilePermission.OWNER_EXECUTE);
    }
}
