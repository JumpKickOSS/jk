// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.hibernate;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Runs the project's own Hibernate enhancer over a classes dir, writing every file to {@code out}
 * with each enhanced class rewritten. Hibernate is loaded from the module's runtime classpath in a
 * loader of its own, so the enhancer is the version the application runs against; its {@code
 * EnhancementContext} is Hibernate's {@code DefaultEnhancementContext} with the switches applied
 * on top, the way hibernate-enhance-maven-plugin configures it.
 */
final class HibernateEnhancer {

    private static final String CONTEXT = "org.hibernate.bytecode.enhance.spi.EnhancementContext";
    private static final String DEFAULT_CONTEXT = "org.hibernate.bytecode.enhance.spi.DefaultEnhancementContext";
    private static final String ENHANCER = "org.hibernate.bytecode.enhance.spi.Enhancer";
    private static final String PROVIDER = "org.hibernate.bytecode.spi.BytecodeProvider";

    private HibernateEnhancer() {}

    /** The four switches, as hibernate-enhance-maven-plugin names them without the {@code enable}. */
    record Switches(
            boolean lazyInitialization,
            boolean dirtyTracking,
            boolean associationManagement,
            boolean extendedEnhancement) {}

    /** How many classes the enhancer rewrote, and the Hibernate release that did it. */
    record Result(int enhanced, String hibernateVersion) {}

    static Result enhance(Path classes, List<Path> runtimeClasspath, Path out, Switches switches) throws IOException {
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(classes, (file, attrs) -> files.add(file));
        files.sort(null);
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (Path entry : runtimeClasspath) urls.add(entry.toUri().toURL());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                "hibernate-enhance", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            Enhancer enhancer = Enhancer.load(loader, switches);
            for (Path file : files) {
                if (isClass(file)) enhancer.discover(className(classes, file), Files.readAllBytes(file));
            }
            int enhanced = 0;
            for (Path file : files) {
                Path target = out.resolve(classes.relativize(file).toString());
                Files.createDirectories(target.getParent());
                byte[] bytes = Files.readAllBytes(file);
                byte[] rewritten = isClass(file) ? enhancer.enhance(className(classes, file), bytes) : null;
                if (rewritten != null) enhanced++;
                Files.write(target, rewritten != null ? rewritten : bytes);
            }
            return new Result(enhanced, enhancer.version());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static boolean isClass(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".class") && !name.equals("module-info.class") && !name.equals("package-info.class");
    }

    private static String className(Path classes, Path file) {
        String rel = classes.relativize(file).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
    }

    /** Hibernate's enhancer, reached reflectively through the loader that holds the project's Hibernate. */
    private record Enhancer(
            Object target, Method enhance, @Nullable Method discover, String version) {

        static Enhancer load(ClassLoader loader, Switches switches) throws IOException {
            Class<?> context;
            try {
                context = loader.loadClass(CONTEXT);
            } catch (ClassNotFoundException e) {
                throw new IOException("[hibernate] enhance runs the project's own Hibernate, and this module's"
                        + " runtime classpath has none: declare org.hibernate.orm:hibernate-core");
            }
            try {
                Object defaults =
                        loader.loadClass(DEFAULT_CONTEXT).getConstructor().newInstance();
                Object proxy = Proxy.newProxyInstance(
                        loader, new Class<?>[] {context}, new SwitchedContext(defaults, loader, switches));
                Class<?> providerType = loader.loadClass(PROVIDER);
                Object provider = provider(loader);
                Object target = providerType.getMethod("getEnhancer", context).invoke(provider, proxy);
                Class<?> enhancerType = loader.loadClass(ENHANCER);
                Method enhance = enhancerType.getMethod("enhance", String.class, byte[].class);
                Method discover = null;
                try {
                    discover = enhancerType.getMethod("discoverTypes", String.class, byte[].class);
                } catch (NoSuchMethodException older) {
                    // Releases before type discovery enhance each class on its own.
                }
                String version = String.valueOf(loader.loadClass("org.hibernate.Version")
                        .getMethod("getVersionString")
                        .invoke(null));
                return new Enhancer(target, enhance, discover, version);
            } catch (ReflectiveOperationException e) {
                throw new IOException("the project's Hibernate has no enhancer jk can drive: " + cause(e), e);
            }
        }

        /** Hibernate 6 and later build the provider directly; 5 hands out the configured one. */
        private static Object provider(ClassLoader loader) throws ReflectiveOperationException {
            try {
                return loader.loadClass("org.hibernate.bytecode.internal.BytecodeProviderInitiator")
                        .getMethod("buildDefaultBytecodeProvider")
                        .invoke(null);
            } catch (ClassNotFoundException | NoSuchMethodException older) {
                return loader.loadClass("org.hibernate.cfg.Environment")
                        .getMethod("getBytecodeProvider")
                        .invoke(null);
            }
        }

        void discover(String className, byte[] bytes) throws IOException {
            if (discover != null) call(discover, className, bytes);
        }

        byte @Nullable [] enhance(String className, byte[] bytes) throws IOException {
            return (byte[]) call(enhance, className, bytes);
        }

        private @Nullable Object call(Method method, String className, byte[] bytes) throws IOException {
            try {
                return method.invoke(target, className, bytes);
            } catch (ReflectiveOperationException e) {
                throw new IOException("Hibernate could not enhance " + className + ": " + cause(e), e);
            }
        }
    }

    private static String cause(Throwable e) {
        Throwable t = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /**
     * Hibernate's default context with the switches applied: dirty tracking, association management
     * and extended enhancement answer the switch, and lazy loading is the default's answer only when
     * lazy initialization is on. Classes load through the project's loader.
     */
    private record SwitchedContext(Object defaults, ClassLoader loader, Switches switches)
            implements InvocationHandler {

        @Override
        public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] args)
                throws Throwable {
            return switch (method.getName()) {
                case "getLoadingClassLoader" -> loader;
                case "doDirtyCheckingInline" -> switches.dirtyTracking();
                case "doBiDirectionalAssociationManagement" -> switches.associationManagement();
                case "doExtendedEnhancement" -> switches.extendedEnhancement();
                case "hasLazyLoadableAttributes", "isLazyLoadable" ->
                    switches.lazyInitialization() && Boolean.TRUE.equals(delegate(method, args));
                case "equals" -> args != null && proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "jk's Hibernate enhancement context";
                default -> delegate(method, args);
            };
        }

        private @Nullable Object delegate(Method method, @Nullable Object @Nullable [] args) throws Throwable {
            try {
                return method.invoke(defaults, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }
}
