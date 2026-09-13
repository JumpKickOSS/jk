// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

/**
 * Declaration-level digest of a Java source file: what a compiler that reads the file for its
 * declarations rather than compiling it — kotlinc over {@code -Xjava-source-roots}, groovyc's
 * joint mode — can see of it. The package, the imports, every type with its modifiers,
 * annotations, type parameters, supertypes and permitted subclasses, every member's signature at
 * every access level (an annotation processor shapes its output from a private field), and the
 * initializer of every {@code final} field (a compile-time constant is copied into the consumer).
 * Method and constructor bodies, initializer blocks and non-final field initializers are not in
 * it: a body-only edit keeps the digest, a signature edit moves it.
 *
 * <p>Parsing is javac's own ({@code JavacTask.parse}), so the shape is what javac would see, and
 * the JDK the engine runs on is what renders it. Digests are memoized on the file's content hash
 * under the {@link AbiMemo}, so a file is parsed once per distinct content. A file javac cannot
 * parse, or a runtime without {@code jdk.compiler}, keys the file on its content instead — strictly
 * finer, so never a false hit — spelled with a different prefix so the two can be told apart.
 */
public final class JavaSourceApi {

    /** A parsed declaration digest: {@code src-api:<sha256>}. */
    public static final String PREFIX = "src-api:";

    /** The fallback for a file whose declarations could not be read: {@code content:<sha256>}. */
    public static final String CONTENT_PREFIX = "content:";

    /**
     * Which declarations a digest covers. {@link #DECLARATIONS} is every member at every access
     * level — what a compiler that parses the file sees, and what an annotation processor may
     * shape its output from. {@link #EXPORTED} leaves private members out: a consumer compiled
     * against the class can reach nothing private, so for a module that runs no processor the
     * exported view is its API and a private edit is a body edit. Record component fields stay in
     * the exported view even though they are private: their accessors are the record's API.
     */
    public enum View {
        DECLARATIONS("java-src:"),
        EXPORTED("java-api:");

        private final String memoNamespace;

        View(String memoNamespace) {
            this.memoNamespace = memoNamespace;
        }
    }

    private JavaSourceApi() {}

    /** The {@link View#DECLARATIONS} token of one file; see {@link #digests}. */
    public static String digest(Path file) throws IOException {
        return digest(file, View.DECLARATIONS);
    }

    /** The token of one file under {@code view}. */
    public static String digest(Path file, View view) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        return Objects.requireNonNull(digests(List.of(abs), view).get(abs), "digest of " + abs);
    }

    /** {@link View#DECLARATIONS} tokens; see {@link #digests(Collection, View)}. */
    public static Map<Path, String> digests(Collection<Path> files) throws IOException {
        return digests(files, View.DECLARATIONS);
    }

    /**
     * One token per file under {@code view}, keyed by the absolute normalized path. Memo hits are
     * answered without a parse; the rest are parsed in one javac task.
     */
    public static Map<Path, String> digests(Collection<Path> files, View view) throws IOException {
        Map<Path, String> out = new LinkedHashMap<>();
        Map<Path, String> contentByPath = new LinkedHashMap<>();
        List<Path> pending = new ArrayList<>();
        for (Path file : files) {
            Path abs = file.toAbsolutePath().normalize();
            if (out.containsKey(abs)) continue;
            String content = FileHashMemo.contentHash(abs);
            contentByPath.put(abs, content);
            String hit = AbiMemo.get(view.memoNamespace + content);
            if (hit != null) {
                out.put(abs, hit);
            } else {
                out.put(abs, "");
                pending.add(abs);
            }
        }
        if (!pending.isEmpty()) {
            Map<Path, String> parsed = parse(pending, view);
            for (Path abs : pending) {
                String content = Objects.requireNonNull(contentByPath.get(abs), "content hash");
                @Nullable String shape = parsed.get(abs);
                String token;
                if (shape != null) {
                    token = PREFIX + Hashing.sha256Hex(shape);
                    AbiMemo.put(view.memoNamespace + content, token);
                } else {
                    // Not memoized: the next sighting asks javac again, in case the file was
                    // mid-edit.
                    token = CONTENT_PREFIX + content;
                }
                out.put(abs, token);
            }
        }
        return out;
    }

    /** True when {@code token} came from a parse rather than the content fallback. */
    public static boolean parsed(String token) {
        return token.startsWith(PREFIX);
    }

    /**
     * The rendered declaration shape per file, for every file javac parsed without an error. A
     * file with a syntax error is left out; so is every file when the runtime has no javac.
     */
    private static Map<Path, String> parse(List<Path> files, View view) {
        Map<Path, String> out = new LinkedHashMap<>();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) return out;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) javac.getTask(null, fm, diagnostics, List.of("-proc:none"), null, units);
            Map<URI, String> shapes = new LinkedHashMap<>();
            for (CompilationUnitTree unit : task.parse()) {
                StringBuilder sb = new StringBuilder();
                renderUnit(unit, sb, view == View.EXPORTED);
                shapes.put(unit.getSourceFile().toUri().normalize(), sb.toString());
            }
            Set<URI> errored = new HashSet<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null) continue;
                errored.add(d.getSource().toUri().normalize());
            }
            for (Path file : files) {
                URI uri = file.toUri().normalize();
                if (errored.contains(uri)) continue;
                String shape = shapes.get(uri);
                if (shape != null) out.put(file, shape);
            }
        } catch (IOException | RuntimeException e) {
            // A parse that fails wholesale keys every pending file on content.
            Log.debug("parse: javac could not read the declarations", e);
            return Map.of();
        }
        return out;
    }

    private static void renderUnit(CompilationUnitTree unit, StringBuilder sb, boolean exported) {
        ExpressionTree pkg = unit.getPackageName();
        sb.append("package ").append(pkg == null ? "" : pkg).append('\n');
        for (ImportTree imp : unit.getImports())
            sb.append(imp.toString().strip()).append('\n');
        ModuleTree module = unit.getModule();
        if (module != null) sb.append(module.toString().strip()).append('\n');
        for (Tree decl : unit.getTypeDecls()) {
            if (decl instanceof ClassTree c) renderClass(c, sb, 0, exported);
        }
    }

    private static void renderClass(ClassTree c, StringBuilder sb, int depth, boolean exported) {
        indent(sb, depth)
                .append(c.getModifiers().toString().strip())
                .append(' ')
                .append(c.getKind())
                .append(' ')
                .append(c.getSimpleName());
        for (Tree tp : c.getTypeParameters()) sb.append(" <").append(tp).append('>');
        Tree extendsClause = c.getExtendsClause();
        if (extendsClause != null) sb.append(" extends ").append(extendsClause);
        for (Tree impl : c.getImplementsClause()) sb.append(" implements ").append(impl);
        for (Tree permit : c.getPermitsClause()) sb.append(" permits ").append(permit);
        sb.append('\n');
        boolean implicitlyFinalFields = c.getKind() == Tree.Kind.INTERFACE || c.getKind() == Tree.Kind.ANNOTATION_TYPE;
        boolean record = c.getKind() == Tree.Kind.RECORD;
        for (Tree member : c.getMembers()) {
            if (member instanceof ClassTree nested) {
                if (exported && isPrivate(nested.getModifiers())) continue;
                renderClass(nested, sb, depth + 1, exported);
            } else if (member instanceof VariableTree field) {
                if (exported && !record && isPrivate(field.getModifiers())) continue;
                renderField(c, field, implicitlyFinalFields, sb, depth + 1);
            } else if (member instanceof MethodTree method) {
                if (exported && isPrivate(method.getModifiers())) continue;
                renderMethod(method, sb, depth + 1);
            }
            // Initializer blocks are bodies.
        }
    }

    private static void renderField(
            ClassTree owner, VariableTree field, boolean implicitlyFinal, StringBuilder sb, int depth) {
        indent(sb, depth);
        if (isEnumConstant(owner, field)) {
            // The constant's arguments and body are the enum's implementation; its name is the API.
            sb.append("enum-constant ").append(field.getName()).append('\n');
            return;
        }
        sb.append(field.getModifiers().toString().strip())
                .append(' ')
                .append(field.getType())
                .append(' ')
                .append(field.getName());
        boolean constantCandidate =
                implicitlyFinal || field.getModifiers().getFlags().contains(Modifier.FINAL);
        ExpressionTree init = field.getInitializer();
        if (constantCandidate && init != null) sb.append(" = ").append(init);
        sb.append('\n');
    }

    private static boolean isPrivate(ModifiersTree modifiers) {
        return modifiers.getFlags().contains(Modifier.PRIVATE);
    }

    private static boolean isEnumConstant(ClassTree owner, VariableTree field) {
        if (owner.getKind() != Tree.Kind.ENUM) return false;
        return field.getInitializer() instanceof NewClassTree created
                && created.getIdentifier()
                        .toString()
                        .equals(owner.getSimpleName().toString());
    }

    private static void renderMethod(MethodTree method, StringBuilder sb, int depth) {
        indent(sb, depth).append(method.getModifiers().toString().strip());
        for (Tree tp : method.getTypeParameters()) sb.append(" <").append(tp).append('>');
        @Nullable Tree returnType = method.getReturnType();
        sb.append(' ').append(returnType == null ? "<init>" : returnType.toString());
        sb.append(' ').append(method.getName()).append('(');
        boolean first = true;
        for (VariableTree param : method.getParameters()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(param.getModifiers().toString().strip())
                    .append(' ')
                    .append(param.getType())
                    .append(' ')
                    .append(param.getName());
        }
        sb.append(')');
        for (ExpressionTree thrown : method.getThrows()) sb.append(" throws ").append(thrown);
        Tree defaultValue = method.getDefaultValue();
        if (defaultValue != null) sb.append(" default ").append(defaultValue);
        sb.append('\n');
    }

    private static StringBuilder indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb;
    }
}
