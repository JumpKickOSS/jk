// SPDX-License-Identifier: Apache-2.0

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.StackTraceUtils;
import org.codehaus.groovy.tools.GroovyClass;

/**
 * Runs one build-logic {@code .groovy} script from its compiled jar, compiling it there first when
 * the jar is absent.
 *
 * <p>{@code run <script> <jar> <projectDir> <outDir>} or {@code compile <script> <jar>}. The jar's
 * {@code Main-Class} names the script class. The compiler lives in {@link ScriptCompiler}, so a run
 * that finds the jar never links a compiler class.
 */
public final class JkGroovyHost {

    private JkGroovyHost() {}

    public static void main(String[] args) throws IOException {
        boolean run = args.length == 5 && args[0].equals("run");
        if (!run && !(args.length == 3 && args[0].equals("compile"))) {
            System.err.println("usage: run <script> <jar> <projectDir> <outDir> | compile <script> <jar>");
            System.exit(64);
        }
        Path script = Path.of(args[1]);
        Path jar = Path.of(args[2]);
        GroovyClassLoader loader = new GroovyClassLoader(JkGroovyHost.class.getClassLoader());
        String main = Files.isRegularFile(jar) ? mainClass(jar) : ScriptCompiler.compileOrExit(script, jar, loader);
        if (!run) return;

        loader.addURL(jar.toUri().toURL());
        Binding binding = new Binding();
        binding.setVariable("projectDir", Path.of(args[3]));
        binding.setVariable("outDir", Path.of(args[4]));
        binding.setVariable("properties", new HashMap<String, Object>());
        try {
            binding.setVariable(
                    "ant",
                    Class.forName("groovy.ant.AntBuilder", true, loader)
                            .getDeclaredConstructor()
                            .newInstance());
        } catch (Throwable noAnt) {
            // No Ant on the classpath: the script has no `ant` binding.
        }
        try {
            Class<?> cls = loader.loadClass(main);
            Script s = InvokerHelper.createScript(cls, binding);
            s.run();
        } catch (Throwable t) {
            System.err.println("Caught: " + t);
            StackTraceUtils.deepSanitize(t).printStackTrace();
            System.exit(1);
        }
    }

    private static String mainClass(Path jar) throws IOException {
        try (JarFile f = new JarFile(jar.toFile())) {
            Manifest m = f.getManifest();
            String main = m == null ? null : m.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (main == null) throw new IOException(jar + " names no Main-Class");
            return main;
        }
    }
}

/** Compiles a script into its jar; loaded only when the jar is absent. */
final class ScriptCompiler {

    private ScriptCompiler() {}

    /** The script class's name, or exit 1 with the compiler's message, which leads with file and line. */
    static String compileOrExit(Path script, Path jar, GroovyClassLoader loader) throws IOException {
        try {
            return compile(script, jar, loader);
        } catch (CompilationFailedException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            throw new AssertionError(e);
        }
    }

    /**
     * Compile {@code script} into {@code jar}: the classes go to a temp jar beside it, which one move
     * puts in place, so a half-written jar is never where a later run would load it.
     */
    private static String compile(Path script, Path jar, GroovyClassLoader loader) throws IOException {
        CompilationUnit unit = new CompilationUnit(new CompilerConfiguration(), null, loader);
        unit.addSource(script.toFile());
        unit.compile(Phases.CLASS_GENERATION);
        String main = mainOf(unit);

        Path dir = jar.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, jar.getFileName().toString(), ".tmp");
        try {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
            try (OutputStream raw = Files.newOutputStream(tmp);
                    JarOutputStream out = new JarOutputStream(raw, manifest)) {
                for (GroovyClass c : unit.getClasses()) {
                    out.putNextEntry(new JarEntry(c.getName().replace('.', '/') + ".class"));
                    out.write(c.getBytes());
                    out.closeEntry();
                }
            }
            try {
                Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileAlreadyExistsException | AccessDeniedException raced) {
                // Windows will not replace a jar a racing run moved in and holds open; its bytes are ours.
                if (!Files.isRegularFile(jar)) throw raced;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return main;
    }

    /** The script class of the one source, else its first class (a class with {@code main}). */
    private static String mainOf(CompilationUnit unit) throws IOException {
        List<ModuleNode> modules = unit.getAST().getModules();
        if (modules.isEmpty() || modules.get(0).getClasses().isEmpty()) {
            throw new IOException("the script declares nothing to run");
        }
        for (ClassNode c : modules.get(0).getClasses()) {
            if (c.isScript()) return c.getName();
        }
        return modules.get(0).getClasses().get(0).getName();
    }
}
