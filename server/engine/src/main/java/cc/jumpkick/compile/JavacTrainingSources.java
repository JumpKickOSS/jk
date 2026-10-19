// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the java-compiler worker's startup cache is recorded on: a small module that reaches the
 * parts of javac an application compile does — generics and inference, records, sealed types and
 * pattern switches, lambdas and streams, enums, nested and anonymous classes, annotations,
 * exceptions and text blocks. The cache holds what the recording loaded and linked, so a Hello.java
 * leaves most of attribution, inference and code generation cold: on petclinic the worker's first
 * compile was 583–620 ms from a Hello.java cache against 495–547 ms from one recorded on the
 * application itself.
 */
final class JavacTrainingSources {

    private JavacTrainingSources() {}

    /** The lowest {@code --release} the training module compiles at: pattern switches are 21. */
    static final int MIN_RELEASE = 21;

    private static final Map<String, String> FILES =
            Map.of("Shape.java", """
            package demo.train;

            import java.util.Objects;

            /** A closed hierarchy, so the switches over it need no default. */
            public sealed interface Shape permits Shape.Circle, Shape.Rect, Shape.Poly {
                double area();

                default String describe() {
                    return switch (this) {
                        case Circle c when c.r() == 0 -> "a point";
                        case Circle c -> "a circle of " + c.r();
                        case Rect(double w, double h) -> "a %s by %s rectangle".formatted(w, h);
                        case Poly p -> "a polygon of " + p.points().size() + " points";
                    };
                }

                record Circle(double r) implements Shape {
                    public Circle {
                        if (r < 0) throw new IllegalArgumentException("r < 0: " + r);
                    }

                    @Override
                    public double area() {
                        return Math.PI * r * r;
                    }
                }

                record Rect(double w, double h) implements Shape {
                    @Override
                    public double area() {
                        return w * h;
                    }
                }

                record Poly(java.util.List<Point> points) implements Shape {
                    public Poly {
                        points = java.util.List.copyOf(Objects.requireNonNull(points));
                    }

                    @Override
                    public double area() {
                        double sum = 0;
                        for (int i = 0; i < points.size(); i++) {
                            Point a = points.get(i), b = points.get((i + 1) % points.size());
                            sum += a.x() * b.y() - b.x() * a.y();
                        }
                        return Math.abs(sum) / 2;
                    }
                }

                record Point(double x, double y) {}
            }
            """, "Repo.java", """
            package demo.train;

            import java.util.ArrayList;
            import java.util.Comparator;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;
            import java.util.function.Function;
            import java.util.function.Predicate;
            import java.util.stream.Collectors;

            /** Generic storage with bounded wildcards, inference through streams and collectors. */
            public class Repo<K extends Comparable<? super K>, V> implements Iterable<V> {
                private final Map<K, V> rows = new HashMap<>();
                private final Function<? super V, ? extends K> key;

                public Repo(Function<? super V, ? extends K> key) {
                    this.key = key;
                }

                public V save(V value) {
                    rows.put(key.apply(value), value);
                    return value;
                }

                public Optional<V> find(K k) {
                    return Optional.ofNullable(rows.get(k));
                }

                public <R extends Comparable<? super R>> List<V> sorted(Function<? super V, ? extends R> by) {
                    return rows.values().stream().sorted(Comparator.comparing(by)).collect(Collectors.toList());
                }

                public <G> Map<G, List<V>> groupBy(Function<? super V, ? extends G> f, Predicate<? super V> keep) {
                    return rows.values().stream()
                            .filter(keep)
                            .collect(Collectors.groupingBy(f, Collectors.mapping(v -> v, Collectors.toList())));
                }

                public static <T extends Comparable<? super T>> T max(List<? extends T> xs) {
                    T best = xs.get(0);
                    for (T x : xs) if (x.compareTo(best) > 0) best = x;
                    return best;
                }

                @Override
                public java.util.Iterator<V> iterator() {
                    List<V> copy = new ArrayList<>(rows.values());
                    return new java.util.Iterator<>() {
                        private int at;

                        @Override
                        public boolean hasNext() {
                            return at < copy.size();
                        }

                        @Override
                        public V next() {
                            return copy.get(at++);
                        }
                    };
                }
            }
            """, "Pets.java", """
            package demo.train;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import java.time.LocalDate;
            import java.util.EnumMap;
            import java.util.List;
            import java.util.Map;

            /** Entities, an annotation with elements, an enum with bodies, a checked exception. */
            public final class Pets {
                private Pets() {}

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.METHOD})
                public @interface Column {
                    String value() default "";

                    int length() default 255;

                    boolean nullable() default true;
                }

                public enum Kind {
                    CAT("meow") {
                        @Override
                        int lives() {
                            return 9;
                        }
                    },
                    DOG("woof"),
                    BIRD("tweet");

                    private final String sound;

                    Kind(String sound) {
                        this.sound = sound;
                    }

                    int lives() {
                        return 1;
                    }

                    String sound() {
                        return sound;
                    }
                }

                public static class NotFound extends Exception {
                    public NotFound(String what, Throwable cause) {
                        super(what, cause);
                    }
                }

                public static class Pet implements Comparable<Pet> {
                    @Column(value = "name", length = 30, nullable = false)
                    private final String name;

                    @Column
                    private final LocalDate born;

                    private final Kind kind;

                    public Pet(String name, LocalDate born, Kind kind) {
                        this.name = name;
                        this.born = born;
                        this.kind = kind;
                    }

                    public String name() {
                        return name;
                    }

                    public Kind kind() {
                        return kind;
                    }

                    public LocalDate born() {
                        return born;
                    }

                    @Override
                    public int compareTo(Pet o) {
                        int c = born.compareTo(o.born);
                        return c != 0 ? c : name.compareTo(o.name);
                    }

                    @Override
                    public String toString() {
                        return name + " (" + kind.name().toLowerCase() + ", " + born + ")";
                    }
                }

                public static Map<Kind, Integer> census(List<Pet> pets) {
                    Map<Kind, Integer> out = new EnumMap<>(Kind.class);
                    pets.forEach(p -> out.merge(p.kind(), 1, Integer::sum));
                    return out;
                }

                public static Pet byName(Repo<String, Pet> repo, String name) throws NotFound {
                    try {
                        return repo.find(name).orElseThrow(() -> new IllegalStateException(name));
                    } catch (IllegalStateException e) {
                        throw new NotFound("no pet named " + name, e);
                    }
                }
            }
            """, "Main.java", """
            package demo.train;

            import java.io.BufferedReader;
            import java.io.IOException;
            import java.io.StringReader;
            import java.time.LocalDate;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.atomic.AtomicInteger;
            import java.util.stream.IntStream;

            public class Main {
                static final String CSV = \"""
                        name,born,kind
                        Leo,2020-01-04,CAT
                        Basil,2019-08-06,DOG
                        Rosy,2021-03-12,BIRD
                        \""";

                public static void main(String[] args) throws Exception {
                    Repo<String, Pets.Pet> repo = new Repo<>(Pets.Pet::name);
                    for (Pets.Pet p : parse(CSV)) repo.save(p);
                    var byKind = repo.groupBy(Pets.Pet::kind, p -> p.born().isAfter(LocalDate.of(2000, 1, 1)));
                    List<Shape> shapes = List.of(
                            new Shape.Circle(1), new Shape.Rect(2, 3),
                            new Shape.Poly(List.of(new Shape.Point(0, 0), new Shape.Point(1, 0), new Shape.Point(0, 1))));
                    double total = shapes.stream().mapToDouble(Shape::area).sum();
                    AtomicInteger n = new AtomicInteger();
                    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> IntStream.rangeClosed(1, 10)
                                    .map(i -> i * i)
                                    .filter(i -> (i & 1) == 0)
                                    .peek(i -> n.incrementAndGet())
                                    .boxed()
                                    .map(String::valueOf)
                                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b))
                            .thenApply(s -> s + " (" + n.get() + ")");
                    StringBuilder out = new StringBuilder();
                    for (Shape s : shapes) out.append(s.describe()).append('\\n');
                    out.append(byKind).append(' ').append(total).append(' ').append(f.get());
                    out.append(Pets.census(repo.sorted(Pets.Pet::born)));
                    System.out.println(out.length() > 0 ? "trained" : out);
                }

                static List<Pets.Pet> parse(String csv) throws IOException {
                    List<Pets.Pet> pets = new ArrayList<>();
                    try (BufferedReader r = new BufferedReader(new StringReader(csv))) {
                        r.readLine();
                        for (String line; (line = r.readLine()) != null; ) {
                            String[] f = line.split(",");
                            pets.add(new Pets.Pet(f[0], LocalDate.parse(f[1]), Pets.Kind.valueOf(f[2])));
                        }
                    }
                    return pets;
                }
            }
            """);

    /** Write the training module under {@code dir}: the source files, in a stable order. */
    static List<Path> write(Path dir) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("demo/train"));
        List<Path> out = new ArrayList<>();
        for (String name : FILES.keySet().stream().sorted().toList()) {
            Path f = pkg.resolve(name);
            Files.writeString(f, FILES.get(name), StandardCharsets.UTF_8);
            out.add(f);
        }
        return out;
    }
}
