// SPDX-License-Identifier: Apache-2.0
package io.helidon.service.maven.plugin;

import cc.jumpkick.model.command.Exit;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What {@code helidon-service-maven-plugin}'s {@code create-application} and {@code
 * create-test-application} goals do, without Maven: scan the scope's classes and its runtime
 * classpath with ClassGraph, start the service registry over them, validate the injection graph,
 * generate the application binding (and {@code ApplicationMain}), and compile them into the output
 * directory. It drives the plugin's own {@code BindingGenerator}, {@code MainGenerator}, {@code
 * ApplicationValidator}, {@code MavenCodegenContext}, {@code MavenFiler}, {@code MavenOptions} and
 * {@code WrappedServices}, none of which imports Maven; they are package-private, which is why this
 * class lives in their package. It never touches {@code MavenLogger} or a Mojo: its own {@code
 * CodegenLogger} speaks the worker's line protocol.
 *
 * <p>It runs in a JVM the {@code jk-helidon} worker forks on the project's JDK (Helidon 27 is Java
 * 27 bytecode), with this jar and the plugin's locked closure on the classpath. It reaches Helidon
 * by reflection, so the worker, which runs on jk's own JDK, compiles against nothing of Helidon's.
 *
 * <p>Arguments: {@code --scope production|test}, {@code --output <dir>} (where javac and the filer
 * write; for production it already holds a copy of the classes), {@code --generated <dir>}, {@code
 * --classpath-file <file>} (one entry per line: the scanned, loaded and compiled-against
 * classpath, the scope's classes first), {@code --source-root <dir>} and {@code --main-source-root
 * <dir>} (repeatable; module-info and package inference), {@code --release <n>}, {@code
 * --binding-class <name>}, {@code --main-class <name>}, and the switches {@code --validate},
 * {@code --fail-on-warning}, {@code --generate-binding}, {@code --generate-main}.
 *
 * <p>Output: every line it means for the worker starts with {@link #PREFIX} and a level ({@code
 * ERROR}, {@code WARNING}, {@code INFO}, {@code BINDING}, {@code MAIN}, {@code FAILED}), newlines
 * in the message escaped as {@code \n}. Exit 1 on failure, after a {@code FAILED} line.
 */
public final class JkCreateApplication {

    /** The line prefix the worker reads. */
    public static final String PREFIX = "##JKHEL-BINDING:";

    private static final String GENERATE_BINDING = "io.helidon.service.registry.Service$GenerateBinding";
    private static final String DEFAULT_NAME = "@default";
    private static final String MODULE_INFO = "module-info.java";

    private final PrintStream out;
    private int warnings;
    private final ClassLoader app = JkCreateApplication.class.getClassLoader();

    private JkCreateApplication(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) {
        PrintStream out = System.out;
        try {
            Options options = Options.parse(args);
            JkCreateApplication app = new JkCreateApplication(out);
            app.run(options);
            app.failOnWarnings(options);
        } catch (Throwable e) {
            Throwable cause = unwrap(e);
            // Off the protocol: the worker shows it only when the failure says nothing itself.
            cause.printStackTrace(System.err);
            String message = cause.getMessage();
            emit(out, "FAILED", message == null || message.isBlank() ? cause.toString() : message);
            out.flush();
            System.exit(Exit.FAILURE);
        }
        out.flush();
        System.exit(Exit.SUCCESS);
    }

    // ---- the goal -----------------------------------------------------------------------------

    private void run(Options o) throws Exception {
        boolean production = o.production;
        List<Path> classpath = o.classpath;
        Path scopeClasses = classpath.getFirst();
        List<Path> scopeRoots = production ? o.mainSourceRoots : o.sourceRoots;

        boolean hasModuleInfo = findModuleInfo(scopeRoots).isPresent();
        List<Path> modulepath = hasModuleInfo ? classpath : List.of();
        URLClassLoader loader = loader(classpath);

        Optional<Object> nonTestModuleInfo = findModuleInfo(o.mainSourceRoots).map(this::parseModuleInfo);
        Optional<Object> myModuleInfo = findModuleInfo(scopeRoots).map(this::parseModuleInfo);

        Class<?> codegenOptionsType = type("io.helidon.codegen.CodegenOptions");
        Object codegenOptions = invokeStatic(cls("MavenOptions"), "create", new Class<?>[] {Set.class}, Set.of());
        invoke(codegenOptions, codegenOptionsType, "validate", new Class<?>[] {Set.class}, Set.of());
        Class<?> scopeType = type("io.helidon.codegen.CodegenScope");
        Object scope = production
                ? scopeType.getField("PRODUCTION").get(null)
                : scopeType.getConstructor(String.class).newInstance("test");

        Object logger = logger();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Object classGraph =
                type("io.github.classgraph.ClassGraph").getConstructor().newInstance();
        classGraph = invoke(
                classGraph, classGraph.getClass(), "overrideClasspath", new Class<?>[] {Iterable.class}, classpath);
        classGraph = invoke(classGraph, classGraph.getClass(), "enableAllInfo", new Class<?>[0]);
        try (AutoCloseable scan = (AutoCloseable) invoke(classGraph, classGraph.getClass(), "scan", new Class<?>[0])) {
            // The class carrying @Service.GenerateBinding names the binding the annotation processor
            // wrote empty: the real one replaces it at the same name.
            Optional<Binding> annotated = annotatedBinding(scan, scopeClasses);
            String packageName = annotated
                    .map(Binding::packageName)
                    .or(() -> myModuleInfo.flatMap(this::exportedPackage))
                    .or(() -> nonTestModuleInfo.flatMap(this::exportedPackage))
                    .or(() -> firstUsedPackage(o.mainSourceRoots))
                    .or(() -> firstUsedPackage(o.sourceRoots))
                    .or(() -> firstUsedPackage(List.of(scopeClasses)))
                    .orElseThrow(() -> new IllegalStateException("Unable to determine package for binding class."));
            String bindingClass = annotated.map(Binding::className).orElse(o.bindingClass);
            String moduleName = myModuleInfo.isPresent()
                    ? (String)
                            invoke(myModuleInfo.get(), type("io.helidon.codegen.ModuleInfo"), "name", new Class<?>[0])
                    : "unnamed/" + packageName + (production ? "" : "/test");

            Class<?> fileType = cls("MavenFiler");
            Object filer =
                    invokeStatic(fileType, "create", new Class<?>[] {Path.class, Path.class}, o.generated, o.output);
            Constructor<?> contextCtor = cls("MavenCodegenContext")
                    .getDeclaredConstructor(
                            codegenOptionsType,
                            fileType,
                            type("io.helidon.codegen.CodegenLogger"),
                            scopeType,
                            type("io.github.classgraph.ScanResult"),
                            type("io.helidon.codegen.ModuleInfo"));
            Object context =
                    contextCtor.newInstance(codegenOptions, filer, logger, scope, scan, myModuleInfo.orElse(null));

            Thread.currentThread().setContextClassLoader(loader);
            Object compilerOptions = compilerOptions(classpath, modulepath, o, logger);
            applicationBinding(o, loader, logger, context, compilerOptions, moduleName, packageName, bindingClass);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** {@code CreateApplicationAbstractMojo.applicationBinding}, step for step. */
    private void applicationBinding(
            Options o,
            ClassLoader loader,
            Object logger,
            Object context,
            Object compilerOptions,
            String moduleName,
            String packageName,
            String bindingClass)
            throws Exception {
        Class<?> wrappedType = cls("WrappedServices");
        Class<?> contextType = cls("MavenCodegenContext");
        Object services = invokeStatic(
                wrappedType,
                "create",
                new Class<?>[] {ClassLoader.class, type("io.helidon.codegen.CodegenLogger"), boolean.class},
                loader,
                logger,
                false);
        try {
            Class<?> serviceInfoType = type("io.helidon.service.registry.ServiceInfo");
            List<?> all = (List<?>) invoke(services, wrappedType, "all", new Class<?>[0]);
            Set<Object> allServices = new TreeSet<>();
            for (Object info : all) allServices.add(invoke(info, serviceInfoType, "serviceType", new Class<?>[0]));
            if (allServices.isEmpty()) {
                warn("Binding generator found no services to process");
                return;
            }
            if (o.validate) {
                // As the Mojo: a finding is a warning, and fails the build only with failOnWarning.
                Object validator = cls("ApplicationValidator")
                        .getDeclaredConstructor(contextType, boolean.class)
                        .newInstance(context, o.failOnWarning);
                invoke(validator, validator.getClass(), "validate", new Class<?>[] {wrappedType}, services);
            }
            Class<?> typeNameType = type("io.helidon.common.types.TypeName");
            Object bindingType = invokeStatic(
                    typeNameType, "create", new Class<?>[] {String.class}, packageName + "." + bindingClass);
            List<Path> sourcesToCompile = new ArrayList<>();
            if (o.generateBinding) {
                Object generator = cls("BindingGenerator")
                        .getDeclaredConstructor(contextType, boolean.class)
                        .newInstance(context, true);
                emit(out, "BINDING", packageName + "." + bindingClass);
                invoke(
                        generator,
                        generator.getClass(),
                        "createBinding",
                        new Class<?>[] {wrappedType, Set.class, typeNameType, String.class, List.class},
                        services,
                        allServices,
                        bindingType,
                        moduleName,
                        sourcesToCompile);
            }
            if (o.generateMain) {
                Object builder = invokeStatic(typeNameType, "builder", new Class<?>[0]);
                builder =
                        invoke(builder, builder.getClass(), "packageName", new Class<?>[] {String.class}, packageName);
                builder = invoke(builder, builder.getClass(), "className", new Class<?>[] {String.class}, o.mainClass);
                Object mainType = invoke(builder, builder.getClass(), "build", new Class<?>[0]);
                Object generator =
                        cls("MainGenerator").getDeclaredConstructor(contextType).newInstance(context);
                emit(out, "MAIN", packageName + "." + o.mainClass);
                invoke(
                        generator,
                        generator.getClass(),
                        "createMain",
                        new Class<?>[] {wrappedType, boolean.class, typeNameType, typeNameType, List.class},
                        services,
                        o.generateBinding,
                        bindingType,
                        mainType,
                        sourcesToCompile);
            }
            if (!sourcesToCompile.isEmpty()) {
                Class<?> compilerType = type("io.helidon.codegen.compiler.Compiler");
                invokeStatic(
                        compilerType,
                        "compile",
                        new Class<?>[] {type("io.helidon.codegen.compiler.CompilerOptions"), Path[].class},
                        compilerOptions,
                        sourcesToCompile.toArray(new Path[0]));
            }
        } finally {
            invoke(services, wrappedType, "close", new Class<?>[0]);
        }
    }

    private Object compilerOptions(List<Path> classpath, List<Path> modulepath, Options o, Object logger)
            throws Exception {
        Object builder = invokeStatic(type("io.helidon.codegen.compiler.CompilerOptions"), "builder", new Class<?>[0]);
        Class<?> b = builder.getClass();
        invoke(builder, b, "classpath", new Class<?>[] {List.class}, List.copyOf(classpath));
        invoke(builder, b, "modulepath", new Class<?>[] {List.class}, List.copyOf(modulepath));
        invoke(builder, b, "release", new Class<?>[] {String.class}, o.release);
        invoke(builder, b, "commandLineArguments", new Class<?>[] {List.class}, List.of());
        invoke(builder, b, "outputDirectory", new Class<?>[] {Path.class}, o.output);
        invoke(builder, b, "logger", new Class<?>[] {type("io.helidon.codegen.CodegenLogger")}, logger);
        return invoke(builder, b, "build", new Class<?>[0]);
    }

    // ---- the binding's name -------------------------------------------------------------------

    /** The binding {@code @Service.GenerateBinding} names. */
    private record Binding(String packageName, String className) {}

    /** The binding named by a class of {@code scopeClasses} annotated {@code @Service.GenerateBinding}. */
    private Optional<Binding> annotatedBinding(Object scan, Path scopeClasses) throws Exception {
        Collection<?> annotated = (Collection<?>) invoke(
                scan, scan.getClass(), "getClassesWithAnnotation", new Class<?>[] {String.class}, GENERATE_BINDING);
        Class<?> classInfoType = type("io.github.classgraph.ClassInfo");
        Class<?> annotationInfoType = type("io.github.classgraph.AnnotationInfo");
        Class<?> valuesType = type("io.github.classgraph.AnnotationParameterValueList");
        Path root = scopeClasses.toAbsolutePath().normalize();
        List<Binding> found = new ArrayList<>();
        for (Object info : annotated) {
            File element = (File) invoke(info, classInfoType, "getClasspathElementFile", new Class<?>[0]);
            if (element == null
                    || !element.toPath().toAbsolutePath().normalize().equals(root)) continue;
            String classPackage = (String) invoke(info, classInfoType, "getPackageName", new Class<?>[0]);
            Object annotation =
                    invoke(info, classInfoType, "getAnnotationInfo", new Class<?>[] {String.class}, GENERATE_BINDING);
            Object values = invoke(annotation, annotationInfoType, "getParameterValues", new Class<?>[0]);
            Object value = invoke(values, valuesType, "getValue", new Class<?>[] {String.class}, "value");
            Object packageName = invoke(values, valuesType, "getValue", new Class<?>[] {String.class}, "packageName");
            String name = value instanceof String s && !s.isBlank() ? s : "ApplicationBinding";
            String pkg = packageName instanceof String s && !s.isBlank() && !DEFAULT_NAME.equals(s) ? s : classPackage;
            found.add(new Binding(pkg, name));
        }
        found.sort(Comparator.comparing(Binding::packageName).thenComparing(Binding::className));
        return found.stream().findFirst();
    }

    private Optional<Path> findModuleInfo(List<Path> roots) {
        return roots.stream()
                .map(r -> r.resolve(MODULE_INFO))
                .filter(Files::exists)
                .findFirst();
    }

    private Object parseModuleInfo(Path file) {
        try {
            return invokeStatic(
                    type("io.helidon.codegen.ModuleInfoSourceParser"), "parse", new Class<?>[] {Path.class}, file);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + file + ": " + e.getMessage(), e);
        }
    }

    /** The module's shortest unqualified export, as the Mojo picks it. */
    private Optional<String> exportedPackage(Object moduleInfo) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, List<String>> exports = (Map<String, List<String>>)
                    invoke(moduleInfo, type("io.helidon.codegen.ModuleInfo"), "exports", new Class<?>[0]);
            return exports.entrySet().stream()
                    .filter(e -> e.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .min(Comparator.comparingInt(String::length));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The shortest package any {@code .java} (or {@code .class}) file under the roots lives in. */
    private static Optional<String> firstUsedPackage(List<Path> roots) {
        Set<String> found =
                new TreeSet<>(Comparator.comparingInt(String::length).thenComparing(s -> s));
        for (Path root : roots) {
            collectPackages(root.toFile(), "", found);
        }
        return found.stream().findFirst();
    }

    private static void collectPackages(File dir, String packageName, Set<String> found) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                collectPackages(child, packageName.isEmpty() ? name : packageName + "." + name, found);
            } else if (!packageName.isEmpty()
                    && (name.endsWith(".java") || name.endsWith(".class"))
                    && !name.startsWith("module-info.")) {
                found.add(packageName);
            }
        }
    }

    // ---- the CodegenLogger --------------------------------------------------------------------

    /** A {@code CodegenLogger} that writes the worker's lines; trace and debug stay quiet. */
    private Object logger() throws Exception {
        Class<?> loggerType = type("io.helidon.codegen.CodegenLogger");
        Class<?> eventType = type("io.helidon.codegen.CodegenEvent");
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            return switch (method.getName()) {
                case "log" -> {
                    log(eventType, args[0]);
                    yield null;
                }
                case "toString" -> "jk CodegenLogger";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && proxy == args[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
        };
        return Proxy.newProxyInstance(app, new Class<?>[] {loggerType}, handler);
    }

    /** A warning, counted as Maven's logger counts them for {@code failOnWarning}. */
    private void warn(String message) {
        warnings++;
        emit(out, "WARNING", message);
    }

    /**
     * As the Mojo with {@code failOnWarning}: a run that logged a warning fails once it is done, so
     * every warning is said first.
     */
    private void failOnWarnings(Options o) {
        if (o.failOnWarning && warnings > 0) {
            throw new IllegalStateException(
                    warnings + " warning(s) while generating the binding, and fail-on-warning is set");
        }
    }

    private void log(Class<?> eventType, Object event) throws Exception {
        System.Logger.Level level = (System.Logger.Level) invoke(event, eventType, "level", new Class<?>[0]);
        String message = String.valueOf(invoke(event, eventType, "message", new Class<?>[0]));
        List<?> objects = (List<?>) invoke(event, eventType, "objects", new Class<?>[0]);
        Optional<?> throwable = (Optional<?>) invoke(event, eventType, "throwable", new Class<?>[0]);
        StringBuilder text = new StringBuilder(message);
        if (!objects.isEmpty()) {
            text.append(", originating in: ");
            for (int i = 0; i < objects.size(); i++) {
                if (i > 0) text.append(", ");
                text.append(describe(objects.get(i)));
            }
        }
        throwable.ifPresent(t -> text.append(": ").append(t));
        switch (level) {
            case ERROR -> emit(out, "ERROR", text.toString());
            case WARNING -> warn(text.toString());
            case INFO -> emit(out, "INFO", text.toString());
            default -> {
                // trace and debug: the Mojo sends them to Maven's debug log, which a build hides
            }
        }
    }

    private String describe(@Nullable Object object) {
        if (object == null) return "null";
        try {
            Class<?> typeName = type("io.helidon.common.types.TypeName");
            if (typeName.isInstance(object)) return String.valueOf(invoke(object, typeName, "fqName", new Class<?>[0]));
            Class<?> classInfo = type("io.github.classgraph.ClassInfo");
            if (classInfo.isInstance(object))
                return String.valueOf(invoke(object, classInfo, "getName", new Class<?>[0]));
        } catch (Exception e) {
            // fall through to the object's own words
        }
        return String.valueOf(object);
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private static void emit(PrintStream out, String level, String message) {
        out.println(PREFIX + level + " "
                + message.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n"));
    }

    private URLClassLoader loader(List<Path> classpath) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Path entry : classpath) urls.add(entry.toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), app);
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, app);
    }

    private Class<?> cls(String simpleName) throws ClassNotFoundException {
        return type(JkCreateApplication.class.getPackageName() + "." + simpleName);
    }

    private static Object invokeStatic(Class<?> owner, String name, Class<?>[] types, @Nullable Object... args)
            throws Exception {
        return invoke(null, owner, name, types, args);
    }

    /**
     * {@code owner.name(types)} on {@code target}, its declared member first (the package-private
     * classes this adapter shares a package with), then a public one it inherits; the callee's own
     * exception surfaces rather than the reflective wrapper.
     */
    @SuppressWarnings("NullAway") // a void callee answers null, and its callers read nothing back
    private static Object invoke(
            @Nullable Object target, Class<?> owner, String name, Class<?>[] types, @Nullable Object... args)
            throws Exception {
        Method method;
        try {
            method = owner.getDeclaredMethod(name, types);
        } catch (NoSuchMethodException e) {
            try {
                method = owner.getMethod(name, types);
            } catch (NoSuchMethodException missing) {
                throw new IllegalStateException(
                        "Helidon " + owner.getName() + "." + name
                                + " is not the shape this jk adapter drives — the helidon-service-maven-plugin release"
                                + " does not match the one jk supports",
                        missing);
            }
        }
        // A public method declared by a package-private supertype (Helidon's *Blueprint
        // interfaces) is not accessible through it; everything here is in the unnamed module.
        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) method.trySetAccessible();
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception x) throw x;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof InvocationTargetException || t instanceof UndeclaredThrowableException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    // ---- arguments ----------------------------------------------------------------------------

    private static final class Options {
        boolean production = true;
        Path output = Path.of("");
        Path generated = Path.of("");
        List<Path> classpath = List.of();
        final List<Path> sourceRoots = new ArrayList<>();
        final List<Path> mainSourceRoots = new ArrayList<>();
        String release = "27";
        String bindingClass = "ApplicationBinding";
        String mainClass = "ApplicationMain";
        boolean validate;
        boolean failOnWarning;
        boolean generateBinding;
        boolean generateMain;

        static Options parse(String[] args) throws IOException {
            Options o = new Options();
            boolean sawOutput = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--scope" -> o.production = !"test".equals(value(args, ++i));
                    case "--output" -> {
                        o.output = Path.of(value(args, ++i));
                        sawOutput = true;
                    }
                    case "--generated" -> o.generated = Path.of(value(args, ++i));
                    case "--classpath-file" -> {
                        Set<Path> entries = new LinkedHashSet<>();
                        for (String line : Files.readAllLines(Path.of(value(args, ++i)), StandardCharsets.UTF_8)) {
                            if (!line.isBlank()) entries.add(Path.of(line.strip()));
                        }
                        o.classpath = List.copyOf(entries);
                    }
                    case "--source-root" -> o.sourceRoots.add(Path.of(value(args, ++i)));
                    case "--main-source-root" -> o.mainSourceRoots.add(Path.of(value(args, ++i)));
                    case "--release" -> o.release = value(args, ++i);
                    case "--binding-class" -> o.bindingClass = value(args, ++i);
                    case "--main-class" -> o.mainClass = value(args, ++i);
                    case "--validate" -> o.validate = true;
                    case "--fail-on-warning" -> o.failOnWarning = true;
                    case "--generate-binding" -> o.generateBinding = true;
                    case "--generate-main" -> o.generateMain = true;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (!sawOutput || o.classpath.isEmpty()) {
                throw new IllegalArgumentException("--output and a non-empty --classpath-file are required");
            }
            // The test goal never generates a main class.
            if (!o.production) o.generateMain = false;
            return o;
        }

        private static String value(String[] args, int at) {
            if (at >= args.length) throw new IllegalArgumentException(args[at - 1] + " needs a value");
            return args[at];
        }
    }
}
