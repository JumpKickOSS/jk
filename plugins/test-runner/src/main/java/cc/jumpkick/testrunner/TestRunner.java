// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;

/**
 * {@code jk test} child JVM: one-shot, list-only discovery, or pull worker ({@code RUN}/{@code DONE}).
 * Exit 0/1/2; protocol on stdout per {@link EventType}.
 *
 * <p>Supports both JUnit Platform 1.x (JUnit 5 / Spring Boot 3.x BOMs) and Platform 6.x without
 * hard classpath links to JUnit-6-only types ({@code OutputDirectoryCreator}, etc.) so ServiceLoader
 * can load this class when the project pins an older platform.
 */
public final class TestRunner implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-test-runner", "##JKT:");
    }

    @Override
    public int run(List<String> argList, ProtocolWriter out) {
        Args parsed;
        try {
            parsed = Args.parse(argList.toArray(new String[0]));
        } catch (IllegalArgumentException e) {
            System.err.println("jk-test-runner: " + e.getMessage());
            System.err.println("usage: jk-test-runner --scan-classpath=<dir> "
                    + "[--list-only] [--pull --worker=<id>] [--filter=<regex>]");
            return 2;
        }

        try (var writer = new JsonEventWriter(out)) {
            if (parsed.listOnly) {
                runListOnly(parsed, writer);
                return 0;
            } else if (parsed.pull) {
                return runPullMode(parsed, writer);
            } else {
                return runOneShot(parsed, writer);
            }
        } catch (LinkageError e) {
            String where = String.valueOf(e.getMessage());
            if (where.contains("junit/platform") || where.contains("junit.platform")) {
                System.err.println("jk-test-runner: incompatible JUnit Platform on the test classpath — "
                        + e.getClass().getSimpleName()
                        + ": "
                        + e.getMessage());
                System.err.println("  Ensure org.junit.platform:junit-platform-engine is on the test classpath "
                        + "(Spring Boot: spring-boot-starter-test; bare projects: junit-jupiter).");
                return 2;
            }
            System.err.println("jk-test-runner: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            return 2;
        } catch (Throwable t) {
            System.err.println("jk-test-runner: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            return 2;
        }
    }

    // --- mode 1: one-shot ----------------------------------------------------

    private static int runOneShot(Args args, JsonEventWriter writer) {
        var streaming = new StreamingListener(writer, args.workerId);
        var request = baseRequest(args);
        var engines = java.util.ServiceLoader.load(TestEngine.class);
        long planStart = System.nanoTime();
        for (var engine : engines) {
            var uid = UniqueId.root("[engine]", engine.getId());
            var descriptor = engine.discover(request, uid);
            emitDiscovery(descriptor, streaming);
            engine.execute(makeExecutionRequest(descriptor, streaming));
        }
        long planMs = Math.max(0, (System.nanoTime() - planStart) / 1_000_000);
        streaming.emitPlanFinished(planMs);
        return streaming.hasFailures() ? 1 : 0;
    }

    // --- mode 2: discovery ---------------------------------------------------

    private static void runListOnly(Args args, JsonEventWriter writer) {
        var streaming = new StreamingListener(writer, args.workerId);
        var request = baseRequest(args);
        var engines = java.util.ServiceLoader.load(TestEngine.class);
        for (var engine : engines) {
            var uid = UniqueId.root("[engine]", engine.getId());
            var descriptor = engine.discover(request, uid);
            emitDiscovery(descriptor, streaming);
        }
    }

    private static void emitDiscovery(TestDescriptor root, StreamingListener listener) {
        var counts = new int[] {0, 0}; // [classes, tests]
        for (var child : root.getChildren()) {
            walkAndEmit(child, listener, counts);
        }
        listener.emitDiscoveryTotal(counts[0], counts[1]);
    }

    private static void walkAndEmit(TestDescriptor node, StreamingListener listener, int[] counts) {
        boolean isContainer = node.getType() == TestDescriptor.Type.CONTAINER;
        boolean isTest = node.getType() == TestDescriptor.Type.TEST;
        node.getSource().ifPresent(src -> {
            if (src instanceof ClassSource cs && isContainer) {
                listener.emitDiscovered(cs.getClassName());
                counts[0]++;
            }
        });
        if (isTest) counts[1]++;
        for (var child : node.getChildren()) {
            walkAndEmit(child, listener, counts);
        }
    }

    // --- mode 3: pull worker -------------------------------------------------

    private static int runPullMode(Args args, JsonEventWriter writer) throws Exception {
        var streaming = new StreamingListener(writer, args.workerId);
        var engines = java.util.ServiceLoader.load(TestEngine.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .toList();

        streaming.emitReady();

        try (var stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.equals("DONE")) break;
                if (!line.startsWith("RUN ")) {
                    System.err.println("jk-test-runner: ignoring unknown command: " + line);
                    continue;
                }
                String className = line.substring(4).trim();
                var classRequest = discoveryRequest(List.of(DiscoverySelectors.selectClass(className)), List.of());
                for (var engine : engines) {
                    var uid = UniqueId.root("[engine]", engine.getId());
                    var descriptor = engine.discover(classRequest, uid);
                    if (descriptor.getChildren().isEmpty()) continue;
                    engine.execute(makeExecutionRequest(descriptor, streaming));
                }
                streaming.emitReady();
            }
        }
        return streaming.hasFailures() ? 1 : 0;
    }

    // --- shared --------------------------------------------------------------

    private static EngineDiscoveryRequest baseRequest(Args args) {
        var selectors =
                new ArrayList<DiscoverySelector>(DiscoverySelectors.selectClasspathRoots(Set.of(args.scanClasspath)));
        var filters = new ArrayList<DiscoveryFilter<?>>();
        if (args.filter != null && !args.filter.isEmpty()) {
            filters.add(ClassNameFilter.includeClassNamePatterns(args.filter));
        }
        return discoveryRequest(List.copyOf(selectors), List.copyOf(filters));
    }

    /**
     * {@link EngineDiscoveryRequest} via {@link Proxy} so we never mention JUnit-6-only types
     * ({@code OutputDirectoryCreator}) in class/method signatures — those types are absent on
     * Spring Boot 3.x BOMs (Platform 1.11 / Jupiter 5.11).
     */
    private static EngineDiscoveryRequest discoveryRequest(
            List<DiscoverySelector> selectors, List<DiscoveryFilter<?>> filters) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            return switch (name) {
                case "getSelectorsByType" -> {
                    Class<?> type = (Class<?>) args[0];
                    yield selectors.stream()
                            .filter(type::isInstance)
                            .map(type::cast)
                            .toList();
                }
                case "getFiltersByType" -> {
                    Class<?> type = (Class<?>) args[0];
                    yield filters.stream()
                            .filter(type::isInstance)
                            .map(type::cast)
                            .toList();
                }
                case "getConfigurationParameters" -> SystemPropertyConfigParams.INSTANCE;
                case "getOutputDirectoryCreator" -> outputDirectoryCreator();
                case "getDiscoveryListener" -> discoveryListenerNoOp(method.getReturnType());
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "jk-EngineDiscoveryRequest";
                default -> {
                    if (method.getReturnType() == boolean.class) yield false;
                    if (method.getReturnType().isPrimitive()) {
                        throw new UnsupportedOperationException("discovery request: " + name);
                    }
                    yield null;
                }
            };
        };
        return (EngineDiscoveryRequest) Proxy.newProxyInstance(
                EngineDiscoveryRequest.class.getClassLoader(), new Class<?>[] {EngineDiscoveryRequest.class}, handler);
    }

    /** JUnit 6 only: no-op {@code OutputDirectoryCreator}, or null if the type is absent. */
    private static Object outputDirectoryCreator() {
        try {
            Class<?> iface = Class.forName("org.junit.platform.engine.OutputDirectoryCreator");
            Path tmp = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
            return Proxy.newProxyInstance(
                    iface.getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> switch (method.getName()) {
                        case "getRootDirectory" -> tmp;
                        case "createOutputDirectory" -> null;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "jk-NoOpOutputDirectoryCreator";
                        default -> null;
                    });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object discoveryListenerNoOp(Class<?> returnType) {
        if (returnType == null || returnType == void.class) return null;
        try {
            // EngineDiscoveryListener.NOOP on 1.10+ / 6.x when present as static field.
            var noop = returnType.getField("NOOP").get(null);
            return noop;
        } catch (ReflectiveOperationException ignored) {
            if (!returnType.isInterface()) return null;
            return Proxy.newProxyInstance(
                    returnType.getClassLoader(), new Class<?>[] {returnType}, (proxy, method, args) -> {
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    });
        }
    }

    /**
     * JUnit 6.x {@code ExecutionRequest.create(...)} when available; else the JUnit 5.x 3-arg
     * constructor. No JUnit-6-only types appear in this method's signature.
     */
    @SuppressWarnings("deprecation")
    private static ExecutionRequest makeExecutionRequest(TestDescriptor descriptor, EngineExecutionListener listener) {
        // Prefer JUnit 6 factory via reflection so linking this method never requires Platform 6.
        try {
            Class<?> storeClass = Class.forName("org.junit.platform.engine.support.store.NamespacedHierarchicalStore");
            Class<?> cancelClass = Class.forName("org.junit.platform.engine.CancellationToken");
            Constructor<?> storeCtor = storeClass.getConstructor(storeClass);
            Object parentStore = storeCtor.newInstance(new Object[] {null});
            Object store = storeClass.getMethod("newChild").invoke(parentStore);
            Object cancel = cancelClass.getMethod("disabled").invoke(null);
            Object outDir = outputDirectoryCreator();
            Method create = ExecutionRequest.class.getMethod(
                    "create",
                    TestDescriptor.class,
                    EngineExecutionListener.class,
                    ConfigurationParameters.class,
                    Class.forName("org.junit.platform.engine.OutputDirectoryCreator"),
                    storeClass,
                    cancelClass);
            return (ExecutionRequest) create.invoke(
                    null, descriptor, listener, SystemPropertyConfigParams.INSTANCE, outDir, store, cancel);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // JUnit 5.x: 3-arg constructor (Platform 1.x / Jupiter 5.x — Spring Boot 3 BOMs).
            return new ExecutionRequest(descriptor, listener, SystemPropertyConfigParams.INSTANCE);
        }
    }

    private record Args(Path scanClasspath, String filter, boolean listOnly, boolean pull, int workerId) {

        static Args parse(String[] argv) {
            Path scan = null;
            String filter = null;
            boolean listOnly = false;
            boolean pull = false;
            int workerId = 0;
            for (var a : argv) {
                if (a.startsWith("--scan-classpath=")) {
                    scan = Path.of(a.substring("--scan-classpath=".length()));
                } else if (a.startsWith("--filter=")) {
                    filter = a.substring("--filter=".length());
                } else if (a.equals("--list-only")) {
                    listOnly = true;
                } else if (a.equals("--pull")) {
                    pull = true;
                } else if (a.startsWith("--worker=")) {
                    workerId = Integer.parseInt(a.substring("--worker=".length()));
                } else if (a.equals("--fail-fast")) {
                    // accepted but currently a no-op — wired in a follow-up
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            if (scan == null) {
                throw new IllegalArgumentException("--scan-classpath=<dir> is required");
            }
            if (listOnly && pull) {
                throw new IllegalArgumentException("--list-only and --pull are mutually exclusive");
            }
            return new Args(scan, filter, listOnly, pull, workerId);
        }
    }

    /**
     * JUnit configuration that honors {@code -Djunit.*} system properties and classpath {@code
     * junit-platform.properties}. The previous empty stub ignored TempDir strategy/factory settings
     * used by CLI integration tests (nested engines hardlink into {@code @TempDir} trees).
     */
    private static final class SystemPropertyConfigParams implements ConfigurationParameters {
        static final SystemPropertyConfigParams INSTANCE = new SystemPropertyConfigParams();

        private final java.util.Map<String, String> fromFile = loadPlatformProperties();

        @Override
        public java.util.Optional<String> get(String key) {
            if (key == null) return java.util.Optional.empty();
            String sys = System.getProperty(key);
            if (sys != null && !sys.isBlank()) return java.util.Optional.of(sys);
            String file = fromFile.get(key);
            if (file != null && !file.isBlank()) return java.util.Optional.of(file);
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Boolean> getBoolean(String key) {
            return get(key).map(v -> {
                String s = v.trim();
                if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
                if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
                return Boolean.parseBoolean(s);
            });
        }

        @Override
        public java.util.Set<String> keySet() {
            var keys = new java.util.LinkedHashSet<>(fromFile.keySet());
            for (var e : System.getProperties().entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.startsWith("junit.")) keys.add(k);
            }
            return keys;
        }

        private static java.util.Map<String, String> loadPlatformProperties() {
            var map = new java.util.LinkedHashMap<String, String>();
            try (var in =
                    Thread.currentThread().getContextClassLoader().getResourceAsStream("junit-platform.properties")) {
                if (in == null) return map;
                var props = new java.util.Properties();
                props.load(in);
                for (String name : props.stringPropertyNames()) {
                    map.put(name, props.getProperty(name));
                }
            } catch (Exception ignored) {
                // best-effort — system properties alone still apply
            }
            return map;
        }
    }
}
