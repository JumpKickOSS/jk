// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Hashing;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocSourcePositions;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTreePathScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.jspecify.annotations.Nullable;

/**
 * The reference index: every name in a compiled Java source that resolves to a type, method,
 * constructor, field, enum constant or record component, with the symbol it resolves to and the
 * exact span of the name, recorded while jk's own javac attributes the source. A rename reads the
 * spans instead of searching the text; the bytecode only says which classes use a symbol, not where.
 *
 * <p>Read after each type is analyzed and before javac lowers it (lambdas and enums are rewritten
 * after ANALYZE, positions with them). One file per source, under {@link #dirFor} — {@code
 * target/incremental/classes-refs/<source path>.refs} for main — whose first line carries the
 * source's SHA-256, so a reader takes a file only while it describes the source as it is. Written
 * only for a compile that passed; a failure to index is logged by the caller and never fails the
 * compile.
 *
 * <p>Format, tab-separated after the header line {@code # jk-refs 1 <sha256>}: {@code <kind> <symbol>
 * <start> <end> <line>}. Kind is {@code d} (the declaration's own name; a constructor's name records
 * its class), {@code r} (a reference in code, imports included) or {@code j} (a Javadoc reference).
 * Symbols: {@code T <binary name>}, {@code M <owner>#<name><descriptor>} (constructors as {@code
 * <init>}), {@code F <owner>#<name>}. Offsets are characters of the source as javac read it.
 */
final class References implements TaskListener {

    static final String HEADER = "# jk-refs 1 ";

    /** On unless {@code jk.java.references=false}: what its cost is measured against. */
    static final boolean ENABLED =
            EnvValues.parseBool(System.getProperty("jk.java.references", "")).orElse(true);

    /** Where the references of {@code classOutput}'s sources live: beside the facts index. */
    static Path dirFor(Path classOutput) {
        Path classes = classOutput.toAbsolutePath().normalize();
        Path target = classes.getParent();
        if (target == null) throw new IllegalArgumentException("a class output has a parent: " + classOutput);
        return target.resolve("incremental").resolve(classes.getFileName() + "-refs");
    }

    /** One reference: what it names and where the name is. */
    record Ref(char kind, String symbol, int start, int end, long line) {}

    private final DocTrees trees;
    private final Elements elements;
    private final Types types;
    private final Map<CompilationUnitTree, List<Ref>> units = new IdentityHashMap<>();
    private final Set<Tree> scanned = Collections.newSetFromMap(new IdentityHashMap<>());

    References(JavacTask task) {
        this.trees = DocTrees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
    }

    @Override
    public void finished(TaskEvent e) {
        if (e.getKind() != TaskEvent.Kind.ANALYZE) return;
        CompilationUnitTree cu = e.getCompilationUnit();
        TypeElement type = e.getTypeElement();
        if (cu == null || type == null) return;
        List<Ref> refs = units.get(cu);
        if (refs == null) {
            refs = new ArrayList<>();
            units.put(cu, refs);
            Scanner imports = new Scanner(cu, refs);
            TreePath root = new TreePath(cu);
            for (ImportTree i : cu.getImports()) imports.scan(new TreePath(root, i), null);
        }
        TreePath path = trees.getPath(type);
        if (path == null || scanned.contains(path.getLeaf())) return;
        new Scanner(cu, refs).scan(path, null);
    }

    /** The sources this compile analyzed, with what each one references. */
    Map<CompilationUnitTree, List<Ref>> units() {
        return units;
    }

    /**
     * Writes one file per analyzed source under {@link #dirFor}{@code (classOutput)}, at the source's
     * path relative to {@code moduleRoot} (an absolute path's digest when the source is outside it).
     */
    void write(Path classOutput, Path moduleRoot) throws IOException {
        Path dir = dirFor(classOutput);
        Path root = moduleRoot.toAbsolutePath().normalize();
        for (Map.Entry<CompilationUnitTree, List<Ref>> unit : units.entrySet()) {
            Path source = Path.of(unit.getKey().getSourceFile().toUri())
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(source)) continue;
            Path target = dir.resolve(relative(root, source) + ".refs");
            StringBuilder out = new StringBuilder(HEADER)
                    .append(sha256(Files.readAllBytes(source)))
                    .append('\n');
            for (Ref r : unit.getValue()) {
                out.append(r.kind())
                        .append('\t')
                        .append(r.symbol())
                        .append('\t')
                        .append(r.start())
                        .append('\t')
                        .append(r.end())
                        .append('\t')
                        .append(r.line())
                        .append('\n');
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** {@code source}'s path below {@code root}, slash-separated; a digest of it when it is elsewhere. */
    static String relative(Path root, Path source) {
        if (source.startsWith(root)) return root.relativize(source).toString().replace('\\', '/');
        return "abs/"
                + sha256(source.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16) + "/"
                + source.getFileName();
    }

    static String sha256(byte[] bytes) {
        return Hashing.sha256Hex(bytes);
    }

    /** The symbol {@code el} is recorded under, or {@code null} for one a rename does not target. */
    @Nullable
    String symbol(@Nullable Element el) {
        if (el == null) return null;
        return switch (el.getKind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> "T " + binary((TypeElement) el);
            case METHOD, CONSTRUCTOR -> {
                Element owner = el.getEnclosingElement();
                if (!(owner instanceof TypeElement t)) yield null;
                yield "M " + binary(t) + "#" + el.getSimpleName() + descriptor((ExecutableElement) el);
            }
            case FIELD, ENUM_CONSTANT, RECORD_COMPONENT -> {
                Element owner = el.getEnclosingElement();
                if (!(owner instanceof TypeElement t)) yield null;
                yield "F " + binary(t) + "#" + el.getSimpleName();
            }
            default -> null;
        };
    }

    private String binary(TypeElement t) {
        return elements.getBinaryName(t).toString();
    }

    /** The JVM descriptor of a method's erasure, as the class files name it. */
    String descriptor(ExecutableElement method) {
        ExecutableType erased = (ExecutableType) types.erasure(method.asType());
        StringBuilder d = new StringBuilder("(");
        for (TypeMirror p : erased.getParameterTypes()) d.append(descriptor(p));
        return d.append(')').append(descriptor(erased.getReturnType())).toString();
    }

    private String descriptor(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case CHAR -> "C";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) t).getComponentType());
            case DECLARED ->
                "L" + binary((TypeElement) ((DeclaredType) t).asElement()).replace('.', '/') + ";";
            case TYPEVAR, INTERSECTION, WILDCARD -> descriptor(types.erasure(t));
            default -> "Ljava/lang/Object;";
        };
    }

    /** Collects one source's references; one per compilation unit and type event. */
    private final class Scanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree cu;
        private final List<Ref> refs;
        private final DocSourcePositions positions;
        private @Nullable CharSequence text;

        Scanner(CompilationUnitTree cu, List<Ref> refs) {
            this.cu = cu;
            this.refs = refs;
            this.positions = trees.getSourcePositions();
        }

        private CharSequence text() {
            if (text == null) {
                try {
                    text = cu.getSourceFile().getCharContent(true);
                } catch (IOException unreadable) {
                    text = "";
                }
            }
            return text;
        }

        private void add(char kind, @Nullable Element el, int start, int end) {
            String symbol = symbol(el);
            if (symbol == null || el == null || start < 0 || end <= start) return;
            // A tree javac made up (an enum constant's `new`, a record's accessor) has a position but
            // no text of its own: only a span that spells the name it records is kept.
            CharSequence t = text();
            String name = el.getKind() == ElementKind.CONSTRUCTOR
                    ? String.valueOf(
                            el.getEnclosingElement() == null
                                    ? ""
                                    : el.getEnclosingElement().getSimpleName())
                    : el.getSimpleName().toString();
            if (end > t.length() || !t.subSequence(start, end).toString().equals(name)) return;
            refs.add(new Ref(kind, symbol, start, end, cu.getLineMap().getLineNumber(start)));
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            // `this(…)` and `super(…)` resolve to constructors, but the word is not the class's name.
            if (node.getName().contentEquals("this") || node.getName().contentEquals("super")) {
                return super.visitIdentifier(node, unused);
            }
            int start = (int) positions.getStartPosition(cu, node);
            add(
                    'r',
                    trees.getElement(getCurrentPath()),
                    start,
                    start + node.getName().length());
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if (node.getIdentifier().contentEquals("class")) return super.visitMemberSelect(node, unused);
            int end = (int) positions.getEndPosition(cu, node);
            add(
                    'r',
                    trees.getElement(getCurrentPath()),
                    end - node.getIdentifier().length(),
                    end);
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            if (!node.getName().contentEquals("<init>")) {
                int end = (int) positions.getEndPosition(cu, node);
                add(
                        'r',
                        trees.getElement(getCurrentPath()),
                        end - node.getName().length(),
                        end);
            }
            return super.visitMemberReference(node, unused);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            scanned.add(node);
            String name = node.getSimpleName().toString();
            if (!name.isEmpty()) {
                int at = find(KIND_THEN_NAME, name, (int) positions.getStartPosition(cu, node), bodyStart(node));
                Element el = trees.getElement(getCurrentPath());
                if (at >= 0) add('d', el, at, at + name.length());
                docs(el);
            }
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            Element owner = el == null ? null : el.getEnclosingElement();
            if (el != null && el.getKind() == ElementKind.CONSTRUCTOR && owner != null) {
                String name = owner.getSimpleName().toString();
                int from = (int) positions.getStartPosition(cu, node);
                int to = bodyStart(node);
                int at = find(NAME_THEN_PAREN, name, from, to);
                // A record's compact canonical constructor has no parameter list: `Square {`.
                if (at < 0) at = find(NAME_THEN_BRACE, name, from, to + 1);
                if (at >= 0) add('d', owner, at, at + name.length());
            } else if (el != null && el.getKind() != ElementKind.CONSTRUCTOR) {
                String name = node.getName().toString();
                Tree ret = node.getReturnType();
                int from = ret == null
                        ? (int) positions.getStartPosition(cu, node)
                        : (int) positions.getEndPosition(cu, ret);
                int at = find(NAME_THEN_PAREN, name, from, bodyStart(node));
                if (at >= 0) add('d', el, at, at + name.length());
            }
            docs(el);
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            if (el != null && symbol(el) != null) {
                String name = node.getName().toString();
                Tree type = node.getType();
                // An enum constant's type is synthetic: its position is not before the name.
                long typeEnd = type == null || el.getKind() == ElementKind.ENUM_CONSTANT
                        ? -1
                        : positions.getEndPosition(cu, type);
                int from = typeEnd > 0 ? (int) typeEnd : (int) positions.getStartPosition(cu, node);
                int at = find(NAME_ALONE, name, from, (int) positions.getEndPosition(cu, node));
                if (at >= 0) add('d', el, at, at + name.length());
                docs(el);
            }
            return super.visitVariable(node, unused);
        }

        /** Javadoc {@code {@link}}, {@code @see} and their kin: each reference's own name. */
        private void docs(@Nullable Element owner) {
            if (owner == null) return;
            TreePath path = getCurrentPath();
            DocCommentTree doc = trees.getDocCommentTree(path);
            if (doc == null) return;
            new DocTreePathScanner<Void, Void>() {
                @Override
                public Void visitReference(ReferenceTree ref, Void unused) {
                    Element el = trees.getElement(getCurrentPath());
                    String sig = ref.getSignature();
                    int start = (int) positions.getStartPosition(cu, doc, ref);
                    if (el != null && start >= 0) {
                        int hash = sig.indexOf('#');
                        boolean member = el.getKind() != ElementKind.CLASS
                                && el.getKind() != ElementKind.INTERFACE
                                && el.getKind() != ElementKind.ENUM
                                && el.getKind() != ElementKind.RECORD
                                && el.getKind() != ElementKind.ANNOTATION_TYPE;
                        Element owner = el.getEnclosingElement();
                        if (member && hash >= 0) {
                            String name = el.getKind() == ElementKind.CONSTRUCTOR && owner != null
                                    ? owner.getSimpleName().toString()
                                    : el.getSimpleName().toString();
                            int at = sig.indexOf(name, hash + 1);
                            if (at >= 0) add('j', el, start + at, start + at + name.length());
                            // `{@link Area#of(Shape)}`: the type before `#` is a reference too.
                            if (hash > 0 && owner != null) {
                                String type = owner.getSimpleName().toString();
                                int head = sig.substring(0, hash).lastIndexOf(type);
                                if (head >= 0) add('j', owner, start + head, start + head + type.length());
                            }
                        } else if (!member) {
                            String head = hash >= 0 ? sig.substring(0, hash) : sig;
                            String name = el.getSimpleName().toString();
                            int at = head.lastIndexOf(name);
                            if (at >= 0) add('j', el, start + at, start + at + name.length());
                        }
                    }
                    return super.visitReference(ref, unused);
                }
            }.scan(new DocTreePath(path, doc), null);
        }

        private int bodyStart(Tree node) {
            Tree body = node instanceof MethodTree m ? m.getBody() : null;
            if (body != null) return (int) positions.getStartPosition(cu, body);
            // A class's implicit members (an enum's values(), a default constructor) sit at its name,
            // so its first member does not bound the search; the declaration keyword comes first.
            return (int) positions.getEndPosition(cu, node);
        }

        /** Where {@code name} is, matched by {@code shape} between {@code from} and {@code to}; -1 when not. */
        private int find(String shape, String name, int from, int to) {
            CharSequence t = text();
            if (from < 0 || from >= t.length()) return -1;
            int end = to < 0 || to > t.length() ? t.length() : to;
            Matcher m = Pattern.compile(shape.replace("NAME", Pattern.quote(name)))
                    .matcher(t)
                    .region(from, end);
            return m.find() ? m.start(1) : -1;
        }
    }

    private static final String KIND_THEN_NAME = "(?:class|interface|enum|record|@\\s*interface)\\s+(NAME)\\b";
    private static final String NAME_THEN_PAREN = "\\b(NAME)\\s*(?:<[^()]*>\\s*)?\\(";
    private static final String NAME_ALONE = "\\b(NAME)\\b";
    private static final String NAME_THEN_BRACE = "\\b(NAME)\\s*\\{";
}
