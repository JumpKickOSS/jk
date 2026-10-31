// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.util.JavacTask;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferencesTest {

    private static final String SHAPES = """
            package demo.shapes;

            import java.util.List;

            /** A shape; see {@link Area#of(Shape)} and {@link #name()}. */
            public interface Shape {
                String name();

                default double area() {
                    return Area.of(this);
                }
            }
            """;

    private static final String AREA = """
            package demo.shapes;

            import java.util.function.Function;

            public final class Area {
                public static final double UNIT = 1.0;

                private Area() {
                    this(0);
                }

                private Area(int ignored) {}

                public static double of(Shape s) {
                    Function<Shape, String> named = Shape::name;
                    double local = UNIT;
                    return named.apply(s).length() * Area.UNIT + local;
                }
            }
            """;

    private static final String SQUARE = """
            package demo.shapes.impl;

            import demo.shapes.Area;
            import demo.shapes.Shape;

            @Deprecated
            public record Square(double side) implements Shape {
                public enum Kind { SMALL, LARGE }

                public Square {
                    if (side < 0) throw new IllegalArgumentException();
                }

                @Override
                public String name() {
                    return "square " + side + " " + Kind.SMALL + " " + Area.of(this) + Shape.class;
                }

                static java.util.List<Shape> all() throws java.io.IOException {
                    return java.util.List.of(new Square(1));
                }
            }
            """;

    /** Each recorded span's text, by symbol: what a rename would replace. */
    private static Map<String, List<String>> compile(Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Path shapes = write(src, "demo/shapes/Shape.java", SHAPES);
        Path area = write(src, "demo/shapes/Area.java", AREA);
        Path square = write(src, "demo/shapes/impl/Square.java", SQUARE);
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) javac.getTask(
                    new PrintWriter(err, true, StandardCharsets.UTF_8),
                    fm,
                    null,
                    List.of("-proc:none", "-d", classes.toString()),
                    null,
                    fm.getJavaFileObjects(shapes, area, square));
            References refs = new References(task);
            task.addTaskListener(refs);
            assertThat(task.call()).as(err.toString(StandardCharsets.UTF_8)).isTrue();
            refs.write(classes, dir);
        }
        Map<String, List<String>> out = new TreeMap<>();
        for (Path file : List.of(shapes, area, square)) {
            String text = Files.readString(file);
            Path index = References.dirFor(classes).resolve(References.relative(dir, file) + ".refs");
            List<String> lines = Files.readAllLines(index);
            assertThat(lines.getFirst()).isEqualTo(References.HEADER + References.sha256(Files.readAllBytes(file)));
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split("\t");
                String at = text.substring(Integer.parseInt(f[2]), Integer.parseInt(f[3]));
                out.computeIfAbsent(f[1], k -> new ArrayList<>()).add(f[0] + ":" + at + "@" + f[4]);
            }
        }
        return out;
    }

    private static Path write(Path root, String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        return f;
    }

    @Test
    void every_name_of_a_type_is_recorded_where_it_is_written(@TempDir Path dir) throws Exception {
        Map<String, List<String>> refs = compile(dir);
        assertThat(refs.get("T demo.shapes.Shape"))
                .contains(
                        "d:Shape@6", // the declaration
                        "r:Shape@4", // an import
                        "r:Shape@7", // implements Shape
                        "r:Shape@16", // Shape.class
                        "r:Shape@19"); // java.util.List<Shape>
        assertThat(refs.get("T demo.shapes.Area"))
                .as("declaration, both constructors' names, an import, a call's qualifier, a doc link's type")
                .contains("d:Area@5", "d:Area@8", "d:Area@12", "r:Area@3", "r:Area@16", "j:Area@5");
        assertThat(refs.get("T demo.shapes.impl.Square"))
                .as("the record, its compact constructor, and new Square(1)")
                .containsExactlyInAnyOrder("d:Square@7", "d:Square@10", "r:Square@20");
        assertThat(refs.get("T demo.shapes.impl.Square$Kind"))
                .as("not the enum constants' made-up `new Kind()`")
                .containsExactlyInAnyOrder("d:Kind@8", "r:Kind@16");
        assertThat(refs.get("T java.io.IOException")).containsExactly("r:IOException@19");
        assertThat(refs.keySet()).noneMatch(k -> k.endsWith("#class"));
    }

    @Test
    void methods_fields_and_constants_are_recorded_by_owner_and_descriptor(@TempDir Path dir) throws Exception {
        Map<String, List<String>> refs = compile(dir);
        assertThat(refs.get("M demo.shapes.Shape#name()Ljava/lang/String;"))
                .containsExactlyInAnyOrder("j:name@5", "d:name@7", "r:name@15");
        assertThat(refs.get("M demo.shapes.impl.Square#name()Ljava/lang/String;"))
                .as("an override is its own symbol; the hierarchy joins them")
                .containsExactly("d:name@15");
        assertThat(refs.get("M demo.shapes.Area#of(Ldemo/shapes/Shape;)D"))
                .containsExactlyInAnyOrder("j:of@5", "r:of@10", "d:of@14", "r:of@16");
        assertThat(refs.get("F demo.shapes.Area#UNIT")).containsExactlyInAnyOrder("d:UNIT@6", "r:UNIT@16", "r:UNIT@17");
        assertThat(refs.get("F demo.shapes.impl.Square$Kind#SMALL"))
                .containsExactlyInAnyOrder("d:SMALL@8", "r:SMALL@16");
        assertThat(refs.get("F demo.shapes.impl.Square#side"))
                .as("the compact constructor's `side` is its parameter, not the field")
                .containsExactlyInAnyOrder("d:side@7", "r:side@16");
    }

    @Test
    void the_words_this_and_super_and_local_names_are_not_recorded(@TempDir Path dir) throws Exception {
        Map<String, List<String>> refs = compile(dir);
        assertThat(refs.values().stream().flatMap(List::stream))
                .noneMatch(r -> r.startsWith("r:this@") || r.startsWith("r:super@"))
                .noneMatch(r -> r.contains(":local@") || r.contains(":named@") || r.contains(":ignored@"));
    }
}
