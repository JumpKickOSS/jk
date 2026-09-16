// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.SelectorResolutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.launcher.EngineDiscoveryResult;
import org.junit.platform.launcher.LauncherDiscoveryListener;
import org.junit.platform.launcher.LauncherDiscoveryRequest;

/**
 * What discovery dropped without a word. The Platform's classpath scan loads every class file under
 * the root and keeps the ones its filters admit; a class the loader cannot produce — a framework
 * loader that boots the application while loading the class and fails (Quarkus's {@code
 * FacadeClassLoader} for a {@code @QuarkusTest}), a superclass missing from the classpath, a class
 * file compiled for a newer JVM — is skipped as if it held no tests, and a run that named it ends
 * as "no test classes matched". This listener records the failures the Platform does report (a
 * selector that failed to resolve, an engine that failed) and, once discovery has finished, loads
 * every class file the request admits through the same context loader the scan used — inside the
 * launcher call, so a framework's launcher interceptor still has its loader installed — and records
 * each one that throws. {@link #report} prints them in the runner's header shape, which the engine
 * reads back as a launcher failure naming the class and the cause.
 */
final class DiscoveryFailures implements LauncherDiscoveryListener {

    /** One thing discovery could not load or run: what it was, and why. */
    record Failure(String subject, @Nullable Throwable cause) {}

    /** Frames printed under each cause: enough to name where, not the whole trace. */
    static final int FRAMES = 3;

    private static final int CAUSE_DEPTH = 6;

    private final Path root;
    private final @Nullable Pattern classNames;
    private final List<Failure> failures = new CopyOnWriteArrayList<>();

    DiscoveryFailures(Path root, @Nullable String filter) {
        this.root = root;
        this.classNames =
                filter == null || filter.isBlank() ? null : Pattern.compile(TestRunner.classNamePattern(filter));
    }

    List<Failure> failures() {
        return List.copyOf(failures);
    }

    @Override
    public void selectorProcessed(UniqueId engineId, DiscoverySelector selector, SelectorResolutionResult result) {
        if (result.getStatus() != SelectorResolutionResult.Status.FAILED) return;
        String subject = selector instanceof ClassSelector c ? c.getClassName() : selector.toString();
        failures.add(new Failure(subject, result.getThrowable().orElse(null)));
    }

    @Override
    public void engineDiscoveryFinished(UniqueId engineId, EngineDiscoveryResult result) {
        if (result.getStatus() != EngineDiscoveryResult.Status.FAILED) return;
        failures.add(new Failure(
                "engine " + engineId.getLastSegment().getValue(),
                result.getThrowable().orElse(null)));
    }

    /**
     * Load every top-level class file under the root the class-name filter admits, with the loader
     * the Platform scanned with. A class the scan already loaded is a lookup; one it dropped throws
     * here, and the throwable is the reason it was dropped.
     */
    @Override
    public void launcherDiscoveryFinished(LauncherDiscoveryRequest request) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();
        for (String name : topLevelClassNames(root)) {
            if (classNames != null && !classNames.matcher(name).matches()) continue;
            if (failures.stream().anyMatch(f -> f.subject().equals(name))) continue;
            try {
                Class.forName(name, false, loader);
            } catch (OutOfMemoryError | StackOverflowError fatal) {
                throw fatal;
            } catch (Throwable t) {
                failures.add(new Failure(name, t));
            }
        }
    }

    /**
     * Binary names of the top-level class files under {@code root}: a nested or anonymous class is
     * part of its outer one, and {@code module-info} / {@code package-info} are not classes a
     * loader produces.
     */
    static List<String> topLevelClassNames(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<String> names = new ArrayList<>();
        try {
            PathUtil.forEachRegularFile(root, (file, attrs) -> {
                String leaf = file.getFileName().toString();
                if (!leaf.endsWith(".class") || leaf.contains("$") || leaf.endsWith("-info.class")) return;
                String rel = root.relativize(file).toString().replace('\\', '/');
                names.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
            });
        } catch (IOException | RuntimeException unreadable) {
            return List.copyOf(names);
        }
        names.sort(null);
        return List.copyOf(names);
    }

    /**
     * Print every recorded failure, or nothing. One header line the engine reads back — {@code
     * jk-test-runner: test discovery failed: <count> could not be loaded: <names>} — then the
     * root, and per failure a {@code class:} line, its cause chain as {@code caused by:} lines, and
     * the first {@link #FRAMES} frames under each cause.
     *
     * @return whether anything was printed
     */
    boolean report(PrintStream err) {
        if (failures.isEmpty()) return false;
        List<String> subjects = failures.stream().map(Failure::subject).toList();
        err.println("jk-test-runner: test discovery failed: " + headline(subjects));
        err.println("  under " + root);
        for (Failure f : failures) {
            err.println("  class: " + f.subject());
            Throwable c = f.cause();
            int depth = 0;
            while (c != null && depth++ < CAUSE_DEPTH) {
                err.println("  caused by: " + c.getClass().getName() + ": " + c.getMessage());
                StackTraceElement[] frames = c.getStackTrace();
                for (int i = 0; i < Math.min(FRAMES, frames.length); i++) err.println("    at " + frames[i]);
                c = c.getCause();
            }
        }
        return true;
    }

    /**
     * {@code 1 class could not be loaded during discovery: a.B} — the count, then every name; an
     * engine among them makes it {@code discovery failed for: engine junit-jupiter, a.B}.
     */
    static String headline(List<String> subjects) {
        if (subjects.stream().anyMatch(s -> s.startsWith("engine "))) {
            return "discovery failed for: " + String.join(", ", subjects);
        }
        return subjects.size()
                + (subjects.size() == 1 ? " class" : " classes")
                + " could not be loaded during discovery: "
                + String.join(", ", subjects);
    }
}
