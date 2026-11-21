// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.host.Classpaths;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Run by {@code FailureSnapshotsTest} in a JVM of its own under the JDWP agent: each case throws
 * through a reflective call, as a test method's failure reaches JUnit, and the snapshot recorded for
 * it is printed as one line, {@code <case> <payload JSON>}; then {@link SnapshotFailingFixture} runs
 * through the launcher and its failure's {@code throwable} payload is printed as {@code launched}.
 */
public final class FailureSnapshotFixture {

    private FailureSnapshotFixture() {}

    /** A project type, rendered by its fields. */
    record Order(int id, String customer, List<String> lines) {}

    enum Speed {
        OVERNIGHT
    }

    public static void main(String[] args) throws Exception {
        FailureSnapshots.startIfAsked();
        FailureSnapshots store = Objects.requireNonNull(FailureSnapshots.current(), "snapshots did not start");
        for (String name : List.of("thrownHere", "thrownInTheJdk", "handledByTheProject", "longValue")) {
            long mark = store.mark();
            Method m = FailureSnapshotFixture.class.getDeclaredMethod(name);
            Throwable failure;
            try {
                m.invoke(null);
                failure = new AssertionError("no failure");
            } catch (InvocationTargetException e) {
                failure = e;
            }
            System.out.println(name + " "
                    + JsonOut.string(
                            store.payload(failure, mark, Thread.currentThread().getName())));
        }
        String file = SnapshotFailingFixture.class.getName().replace('.', '/') + ".class";
        Path root = Classpaths.split(System.getProperty("java.class.path")).stream()
                .filter(dir -> Files.isRegularFile(dir.resolve(file)))
                .findFirst()
                .orElseThrow();
        String only = "^" + SnapshotFailingFixture.class.getName().replace(".", "\\.") + "$";
        LauncherPath.runOneShot(root, only, List.of(), TagFilters.NONE, 0, new EventWriter() {
            @Override
            public void write(EventType type, Map<String, Object> payload) {
                if (type == EventType.FINISHED && payload.get("throwable") != null) {
                    System.out.println("launched " + JsonOut.string(payload.get("throwable")));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    static void thrownHere() {
        Order order = new Order(7, "ada", new ArrayList<>(List.of("A1", "B2")));
        ship(order, 3, Speed.OVERNIGHT);
    }

    static void ship(Order order, int qty, Speed speed) {
        int available = order.lines().size();
        if (qty > available) throw new IllegalStateException("only " + available + " lines");
    }

    static void thrownInTheJdk() {
        String customer = null;
        Objects.requireNonNull(customer, "customer");
    }

    /** Throws and catches on its own: the caught one is the program's flow; the failure is the second. */
    static void handledByTheProject() {
        int parsed;
        try {
            parsed = parse("x");
        } catch (NumberFormatException e) {
            parsed = -1;
        }
        throw new UnsupportedOperationException("parsed " + parsed);
    }

    static int parse(String text) {
        throw new NumberFormatException(text);
    }

    static void longValue() {
        String token = "s3cr3t-".repeat(40);
        throw new IllegalArgumentException("bad token of " + token.length());
    }
}
