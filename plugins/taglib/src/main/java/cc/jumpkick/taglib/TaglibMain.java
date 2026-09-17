// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.taglib;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The typed tag-library interfaces Jenkins' Groovy views call, as the Jenkins Maven build
 * generates them: {@code --out <dir> [--encoding <charset>] <resource dir>…} walks each directory
 * and writes, for every directory holding a {@code taglib} marker, one interface named after it
 * ({@code lib/layout} → {@code lib.LayoutTagLib}, a {@code hudson} segment read as
 * {@code jenkins}) with four overloads per Jelly view — {@code (Map, Closure)}, {@code (Closure)},
 * {@code (Map)} and {@code ()} — the view's {@code st:documentation} text as their Javadoc. The
 * interfaces extend {@code TypedTagLibrary} and carry the library's URI, so the module compiles
 * them against its own {@code stapler-groovy}. Runs in the generator step's forked JVM with
 * nothing of jk's on the classpath.
 */
public final class TaglibMain {

    /** Java keywords: a view of that name gets a trailing underscore, as the Maven generator gives it. */
    private static final Set<String> RESERVED = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null");

    /** The documentation element, whatever prefix the view binds {@code jelly:stapler} to. */
    private static final Pattern DOCUMENTATION =
            Pattern.compile("<(\\w+):documentation\\b[^>]*>(.*?)</\\1:documentation>", Pattern.DOTALL);

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** One nested element with its content, self-closing or paired, innermost first. */
    private static final Pattern NESTED = Pattern.compile(
            "<([\\w.:-]+)\\b[^<>]*/>|<([\\w.:-]+)\\b[^<>]*>(?:(?!<\\2\\b)(?!</\\2>).)*?</\\2>", Pattern.DOTALL);

    private final Path out;
    private final Charset encoding;
    private int interfaces;

    private TaglibMain(Path out, Charset encoding) {
        this.out = out;
        this.encoding = encoding;
    }

    public static void main(String[] args) throws Exception {
        @Nullable String out = null;
        Charset encoding = Charset.forName("UTF-8");
        List<Path> dirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = value(args, ++i);
                case "--encoding" -> encoding = Charset.forName(value(args, ++i));
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown option " + args[i]);
                    dirs.add(Path.of(args[i]));
                }
            }
        }
        if (out == null) throw new IllegalArgumentException("--out <dir> is required");
        int written = generate(Path.of(out), encoding, dirs);
        System.out.println("taglib: " + written + (written == 1 ? " interface" : " interfaces") + " -> " + out);
    }

    /** Every tag library under {@code resourceDirs} as an interface under {@code out}; the count written. */
    static int generate(Path out, Charset encoding, List<Path> resourceDirs) throws IOException {
        TaglibMain main = new TaglibMain(out.toAbsolutePath().normalize(), encoding);
        for (Path dir : resourceDirs) {
            if (Files.isDirectory(dir)) main.walk(dir.toAbsolutePath().normalize(), List.of(), "");
        }
        return main.interfaces;
    }

    /**
     * {@code pkg} is the package the directory names (each segment its directory, {@code hudson}
     * read as {@code jenkins}); {@code uri} the directory's path under the resource root.
     */
    private void walk(Path dir, List<String> pkg, String uri) throws IOException {
        for (File child : children(dir, File::isDirectory)) {
            String name = child.getName();
            List<String> childPkg = new ArrayList<>(pkg);
            childPkg.add(hudsonToJenkins(name));
            walk(child.toPath(), childPkg, uri + "/" + name);
        }
        if (!pkg.isEmpty() && Files.exists(dir.resolve("taglib"))) writeInterface(dir, pkg, uri);
    }

    private void writeInterface(Path dir, List<String> pkg, String uri) throws IOException {
        String taglib = pkg.getLast();
        String owner = String.join(".", pkg.subList(0, pkg.size() - 1));
        String name = Character.toUpperCase(taglib.charAt(0)) + taglib.substring(1) + "TagLib";
        List<File> views = children(dir, f -> f.isFile() && f.getName().endsWith(".jelly"));

        StringBuilder src = new StringBuilder();
        if (!owner.isEmpty()) src.append("package ").append(owner).append(";\n\n");
        src.append("import groovy.lang.Closure;\n")
                .append("import java.util.Map;\n")
                .append("import org.kohsuke.stapler.jelly.groovy.TagFile;\n")
                .append("import org.kohsuke.stapler.jelly.groovy.TagLibraryUri;\n")
                .append("import org.kohsuke.stapler.jelly.groovy.TypedTagLibrary;\n\n")
                .append("@TagLibraryUri(\"")
                .append(uri)
                .append("\")\n")
                .append("public interface ")
                .append(name)
                .append(" extends TypedTagLibrary {\n");
        for (File view : views) {
            String file = view.getName();
            String base = file.substring(0, file.length() - ".jelly".length());
            String method = base.replaceAll("[.-]", "_");
            if (RESERVED.contains(method)) method += "_";
            String doc = documentation(Files.readString(view.toPath(), encoding));
            for (String params : List.of("Map args, Closure body", "Closure body", "Map args", "")) {
                src.append('\n');
                if (doc != null) src.append("    /**\n     * ").append(doc).append("\n     */\n");
                if (!method.equals(base))
                    src.append("    @TagFile(\"").append(base).append("\")\n");
                src.append("    void ")
                        .append(method)
                        .append('(')
                        .append(params)
                        .append(");\n");
            }
        }
        src.append("}\n");
        Path file = out.resolve(owner.replace('.', '/')).resolve(name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, src.toString(), encoding);
        interfaces++;
    }

    /**
     * The view's own {@code st:documentation} text — the text directly inside the element, not the
     * text of its attribute entries or inline tags — or null. Lifted by a scan of the file rather
     * than an XML parse: the forked JVM carries none of jk's, and a Jelly view's documentation is
     * one element.
     */
    static @Nullable String documentation(String view) {
        Matcher doc = DOCUMENTATION.matcher(view);
        if (!doc.find()) return null;
        String text = COMMENT.matcher(doc.group(2)).replaceAll("");
        for (Matcher nested = NESTED.matcher(text); nested.find(); nested = NESTED.matcher(text)) {
            text = nested.replaceFirst("");
        }
        List<String> lines = new ArrayList<>();
        for (String line : unescape(text).strip().split("\\R")) {
            String stripped = line.strip();
            if (stripped.isEmpty() && (lines.isEmpty() || lines.getLast().isEmpty())) continue;
            lines.add(stripped);
        }
        if (lines.isEmpty()) return null;
        return String.join("\n     * ", lines)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("*/", "*&#47;");
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** {@code dir}'s entries passing {@code keep}, in case-insensitive name order — the order the Maven generator writes. */
    private static List<File> children(Path dir, Predicate<File> keep) {
        File[] entries = dir.toFile().listFiles();
        List<File> kept = new ArrayList<>();
        if (entries == null) return kept;
        for (File entry : entries) {
            if (keep.test(entry)) kept.add(entry);
        }
        kept.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return kept;
    }

    private static String hudsonToJenkins(String segment) {
        return segment.equals("hudson") ? "jenkins" : segment;
    }

    private static String value(String[] args, int at) {
        if (at >= args.length) throw new IllegalArgumentException(args[at - 1] + " needs a value");
        return args[at];
    }
}
