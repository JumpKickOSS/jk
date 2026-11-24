// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps the template catalog's native links apart from its builds, across every test JVM of the
 * suite: a link runs only while no shard builds, and a build waits while a link runs or is waiting.
 * {@code native-image} takes gigabytes and every core. Beside the other shards' framework builds a
 * link's engine waited ten minutes for memory it never got and the host killed a shard's JVM; on its
 * own a link has the memory the shards' engines leave idle.
 *
 * <p>Two lock files in the suite's temp root, which every test JVM of the run shares: the room,
 * held shared by a build and exclusively by a link, and the door, held while the room is taken. A
 * link keeps the door until it has the room, so builds that start after it wait at the door rather
 * than keep the room shared for as long as any shard builds. A build holds the door only for the
 * moment it takes the room. The room is never waited for by whoever holds it, so neither waits on
 * the other in a circle.
 *
 * <p>The locks are the operating system's, held by the JVM: one JVM takes one turn at a time, as
 * the shards' cases run one after another in each.
 */
final class NativeLinkGate {

    /** The file a turn waits on to take the room. */
    static final String DOOR = "template-catalog-link.door";

    /** The file a build holds shared and a link exclusively. */
    static final String ROOM = "template-catalog-link.room";

    /** Work done inside a turn. */
    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    private final Path dir;

    NativeLinkGate(Path dir) {
        this.dir = dir;
    }

    /** The gate of this run of the suite: in the temp root jk gives every test JVM of the module. */
    static NativeLinkGate forSuite() {
        return new NativeLinkGate(TestHomes.tmpFor(RepoRoot.dir(NativeLinkGate.class, "e2e/cli")));
    }

    /** Runs {@code work} once no build holds the room, and keeps every build out until it ends. */
    void link(Work work) throws Exception {
        try (FileChannel door = open(DOOR);
                FileLock atDoor = door.lock();
                FileChannel room = open(ROOM);
                FileLock inRoom = room.lock()) {
            work.run();
        }
    }

    /** Runs {@code work} beside other builds, once no link holds the room or waits for it. */
    void build(Work work) throws Exception {
        try (FileChannel room = open(ROOM)) {
            FileLock inRoom;
            try (FileChannel door = open(DOOR);
                    FileLock atDoor = door.lock()) {
                inRoom = room.lock(0, Long.MAX_VALUE, true);
            }
            try (inRoom) {
                work.run();
            }
        }
    }

    private FileChannel open(String name) throws IOException {
        Files.createDirectories(dir);
        return FileChannel.open(
                dir.resolve(name), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    /**
     * A turn from another JVM, for the gate's own test: {@code <dir> link|build <marker>} takes that
     * turn, creates {@code <marker>.in} once inside, and leaves when {@code <marker>.out} exists.
     */
    public static void main(String[] args) throws Exception {
        NativeLinkGate gate = new NativeLinkGate(Path.of(args[0]));
        Path marker = Path.of(args[2]);
        Work hold = () -> {
            Files.createFile(Path.of(marker + ".in"));
            while (!Files.exists(Path.of(marker + ".out"))) Thread.sleep(20);
        };
        if (args[1].equals("link")) gate.link(hold);
        else gate.build(hold);
    }
}
