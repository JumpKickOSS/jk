// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An extracted interface declares the chosen public methods of a class as written, with the imports
 * their types need; the class implements it with {@code @Override} on each, and each variable,
 * parameter, field and return type outside it that uses only those methods takes the interface
 * instead, then it compiles. Every other use stays, listed with its reason. What an interface cannot declare is refused with nothing written, and an edit that
 * does not compile is undone whole, the new file included.
 */
class ExtractInterfaceTest {

    static final String SHOP = "src/main/java/org/acme/shop/";
    static final String API = "src/main/java/org/acme/api/";
    static final String STOCK = "src/main/java/org/acme/stock/";

    static final String BOOK = """
            package org.acme.shop;

            import java.io.Serializable;
            import java.math.BigDecimal;
            import java.util.List;

            public class Book implements Serializable {

                private final BigDecimal price;

                public Book(BigDecimal price) {
                    this.price = price;
                }

                /** The price before tax. */
                public BigDecimal price() {
                    return price;
                }

                public BigDecimal total(int copies) {
                    return price.multiply(BigDecimal.valueOf(copies));
                }

                public BigDecimal total(int copies, BigDecimal discount) {
                    return total(copies).subtract(discount);
                }

                public synchronized List<String> tags() {
                    return List.of("book");
                }

                public static Book free() {
                    return new Book(BigDecimal.ZERO);
                }

                int pages() {
                    return 1;
                }

                @Override
                public String toString() {
                    return "book " + price;
                }
            }
            """;

    static final String CART = """
            package org.acme.shop;

            import java.math.BigDecimal;

            class Cart {
                private final Book book;

                Cart(Book book) {
                    this.book = book;
                }

                BigDecimal due() {
                    return book.total(2);
                }

                BigDecimal sum(Book first, Book second) {
                    return first.price().add(second.total(1));
                }

                int size(Book counted) {
                    return counted.pages();
                }

                void keep(Book kept) {
                    Shelf.put(kept);
                }

                Book make() {
                    return Book.free();
                }
            }
            """;

    static final String SHELF = """
            package org.acme.shop;

            class Shelf {
                static void put(Book book) {}
            }
            """;

    static Map<String, String> shop() {
        return RenameFixture.files(SHOP + "Book.java", BOOK, SHOP + "Cart.java", CART, SHOP + "Shelf.java", SHELF);
    }

    @Test
    void theChosenMethodsBecomeAnInterfaceAndEveryPlaceThatCanTakeItIsSwitched(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.extract("Book", "Priced", "price", "total");

        assertThat(answer).isEqualTo("""
                Extracted org.acme.shop.Priced from Book: 3 methods (price, total); Book implements it, 3 @Override.
                  src/main/java/org/acme/shop/Priced.java  new
                  src/main/java/org/acme/shop/Book.java:7,16,21,26
                Switched to Priced, calling only its methods (5 lines):
                  src/main/java/org/acme/shop/Cart.java:6  private final Priced book;
                  src/main/java/org/acme/shop/Cart.java:8  Cart(Priced book) {
                  src/main/java/org/acme/shop/Cart.java:16  BigDecimal sum(Priced first, Priced second) {
                  src/main/java/org/acme/shop/Cart.java:28  Priced make() {
                  src/main/java/org/acme/shop/Shelf.java:4  static void put(Priced book) {}
                Left as Book (2 lines):
                  src/main/java/org/acme/shop/Cart.java:20  int size(Book counted) {  (calls pages())
                  src/main/java/org/acme/shop/Cart.java:24  void keep(Book kept) {  (passed on)
                Also left as Book, where only the class will do (1 line):
                  src/main/java/org/acme/shop/Cart.java  1 use of the class name""");
        assertThat(f.read(SHOP + "Priced.java")).isEqualTo("""
                package org.acme.shop;

                import java.math.BigDecimal;

                public interface Priced {

                    BigDecimal price();

                    BigDecimal total(int copies);

                    BigDecimal total(int copies, BigDecimal discount);
                }
                """);
        assertThat(f.read(SHOP + "Book.java"))
                .contains("public class Book implements Serializable, Priced {")
                .contains("""
                            /** The price before tax. */
                            @Override
                            public BigDecimal price() {
                        """)
                .contains("""
                            @Override
                            public BigDecimal total(int copies, BigDecimal discount) {
                        """);
        assertThat(f.checkExtract()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void noSwitchCreatesTheInterfaceAloneAndListsThePlacesThatCouldTakeIt(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.extractOnly("Book", "Priced", "price", "total")).endsWith("""
                        Could take Priced instead of Book, calling only its methods (5 lines):
                          src/main/java/org/acme/shop/Cart.java:6  private final Book book;
                          src/main/java/org/acme/shop/Cart.java:8  Cart(Book book) {
                          src/main/java/org/acme/shop/Cart.java:16  BigDecimal sum(Book first, Book second) {
                          src/main/java/org/acme/shop/Cart.java:28  Book make() {
                          src/main/java/org/acme/shop/Shelf.java:4  static void put(Book book) {}""");
        assertThat(f.read(SHOP + "Cart.java")).isEqualTo(CART);
        assertThat(f.read(SHOP + "Shelf.java")).isEqualTo(SHELF);
        assertThat(f.checkExtract()).isEqualTo("Proven: it compiles.");
    }

    static final String TILL = """
            package org.acme.api;

            import java.math.BigDecimal;
            import java.util.List;
            import java.util.function.Consumer;
            import org.acme.shop.Book;

            public class Till {
                Book last;
                final Consumer<Book> printer = (Book b) -> b.price();

                public BigDecimal ring(Book book) {
                    return book.price();
                }

                Book find() {
                    return last;
                }

                Book any() {
                    return last;
                }

                BigDecimal due() {
                    Book found = find();
                    return found.total(1).add(any().price());
                }

                int count() {
                    Book kept = any();
                    return kept.tags().size();
                }

                void all(List<Book> books) {
                    books.forEach(this::see);
                }

                void see(Book seen) {
                    seen.price();
                }
            }
            """;

    @Test
    void aPlaceWhoseTypeAnOverrideALambdaOrAnotherFileFixesIsLeftWithTheReason(@TempDir Path dir) throws IOException {
        Map<String, String> files = new LinkedHashMap<>(shop());
        files.putAll(RenameFixture.files(
                API + "Till.java",
                TILL,
                API + "Reader.java",
                """
                package org.acme.api;

                class Reader {
                    /** What {@link Till#find()} found last. */
                    Object read(Till till) {
                        return till.last;
                    }
                }
                """,
                API + "Base.java",
                """
                package org.acme.api;

                import org.acme.shop.Book;

                public class Base {
                    public void show(Book shown) {
                        shown.price();
                    }
                }
                """,
                API + "Sub.java",
                """
                package org.acme.api;

                import org.acme.shop.Book;

                class Sub extends Base {
                    @Override
                    public void show(Book shown) {
                        shown.total(1);
                    }
                }
                """,
                API + "Pricer.java",
                """
                package org.acme.api;

                import java.math.BigDecimal;
                import org.acme.shop.Book;

                final class Pricer {
                    static BigDecimal of(Book book) {
                        return book == null ? BigDecimal.ZERO : book.price();
                    }
                }
                """));
        RenameFixture f = new RenameFixture(dir, files);

        String answer = f.extract("Book", "Priced", "price", "total");

        assertThat(answer)
                .contains("""
                          src/main/java/org/acme/api/Pricer.java:7  static BigDecimal of(Priced book) {
                          src/main/java/org/acme/api/Till.java:13  public BigDecimal ring(Priced book) {
                          src/main/java/org/acme/api/Till.java:17  Priced find() {
                        """)
                .contains("  src/main/java/org/acme/api/Till.java:26  Priced found = find();\n")
                .contains(
                        "  src/main/java/org/acme/api/Base.java:6  public void show(Book shown) {  (show() may be"
                                + " overridden in src/main/java/org/acme/api/Sub.java)",
                        "  src/main/java/org/acme/api/Sub.java:7  public void show(Book shown) {  (show() overrides"
                                + " a method)",
                        "  src/main/java/org/acme/api/Till.java:10  Book last;  (a field src/main/java/org/acme/api/"
                                + "Reader.java uses)",
                        "(a lambda parameter: its target type fixes it)",
                        "  src/main/java/org/acme/api/Till.java:21  Book any() {  (its result is used as Book at"
                                + " src/main/java/org/acme/api/Till.java:31)",
                        "  src/main/java/org/acme/api/Till.java:31  Book kept = any();  (calls tags())",
                        "  src/main/java/org/acme/api/Till.java:39  void see(Book seen) {  (a method reference to"
                                + " see() in src/main/java/org/acme/api/Till.java fixes its types)")
                .contains("\n  src/main/java/org/acme/api/Till.java  2 type arguments\n");
        assertThat(f.read(API + "Pricer.java"))
                .contains("import java.math.BigDecimal;\nimport org.acme.shop.Priced;\n\nfinal class Pricer {");
        assertThat(f.read(API + "Till.java")).contains("import org.acme.shop.Book;\nimport org.acme.shop.Priced;\n");
        assertThat(f.checkExtract()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void byDefaultEveryPublicInstanceMethodThatOverridesNothingGoesToAnotherPackage(@TempDir Path dir)
            throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.extract("org.acme.shop.Book", "org.acme.api.Goods"))
                .startsWith("Extracted org.acme.api.Goods from Book: 4 methods (price, total, tags); Book implements"
                        + " it, 4 @Override.");
        assertThat(f.read(API + "Goods.java")).isEqualTo("""
                package org.acme.api;

                import java.math.BigDecimal;
                import java.util.List;

                public interface Goods {

                    BigDecimal price();

                    BigDecimal total(int copies);

                    BigDecimal total(int copies, BigDecimal discount);

                    List<String> tags();
                }
                """);
        assertThat(f.read(SHOP + "Book.java"))
                .contains("import java.util.List;\nimport org.acme.api.Goods;\n")
                .contains("public class Book implements Serializable, Goods {");
        assertThat(f.checkExtract()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void whatAnInterfaceCannotDeclareIsRefusedAndNothingWritten(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.extract("Book", "Priced", "pages"))
                .isEqualTo("Not extracted: Book.pages is not public; an interface's methods are, so make it public"
                        + " first.");
        assertThat(f.extract("Book", "Priced", "free"))
                .isEqualTo("Not extracted: Book.free is static; an interface declares instance methods.");
        assertThat(f.extract("Book", "Priced", "weight")).isEqualTo("Not extracted: Book declares no method 'weight'.");
        assertThat(f.extract("Book", "Cart"))
                .startsWith("Not extracted: org.acme.shop.Cart already exists (" + SHOP + "Cart.java)");
        assertThat(f.extract("Book", "9lives")).isEqualTo("Not extracted: '9lives' is not a Java type name.");
        assertThat(f.read(SHOP + "Book.java")).isEqualTo(BOOK);
        assertThat(f.exists(SHOP + "Priced.java")).isFalse();
    }

    @Test
    void aGenericClassPassesItsTypeParametersToTheInterface(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, RenameFixture.files(SHOP + "Box.java", """
                        package org.acme.shop;

                        import java.io.Serializable;
                        import java.util.List;

                        public class Box<K extends Comparable<K> & Serializable, V> {
                            private V value;

                            public V get(K key) {
                                return value;
                            }

                            public <R> List<R> map(java.util.function.Function<V, R> f) {
                                return List.of(f.apply(value));
                            }
                        }
                        """, SHOP + "Shop.java", """
                        package org.acme.shop;

                        class Shop {
                            String name(Box<String, String> box) {
                                return box.get("a");
                            }
                        }
                        """));

        assertThat(f.extract("Box", "org.acme.api.Holder"))
                .startsWith("Extracted org.acme.api.Holder from Box: 2 methods (get, map); Box implements it, 2"
                        + " @Override.")
                .contains("  src/main/java/org/acme/shop/Shop.java:6  String name(Holder<String, String> box) {");
        assertThat(f.read(API + "Holder.java")).isEqualTo("""
                package org.acme.api;

                import java.io.Serializable;
                import java.util.List;

                public interface Holder<K extends Comparable<K> & Serializable, V> {

                    V get(K key);

                    <R> List<R> map(java.util.function.Function<V, R> f);
                }
                """);
        assertThat(f.read(SHOP + "Box.java"))
                .contains("public class Box<K extends Comparable<K> & Serializable, V> implements Holder<K, V> {");
        assertThat(f.checkExtract()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void typeArgumentsNameEachParameterWithoutItsBoundOrAnnotations() {
        assertThat(ExtractInterface.typeArgs("")).isEmpty();
        assertThat(ExtractInterface.typeArgs("<T>")).isEqualTo("<T>");
        assertThat(ExtractInterface.typeArgs("<K extends Map<K, V>, @A(x = 1) V extends Comparable<? super V>>"))
                .isEqualTo("<K, V>");
        assertThat(ExtractInterface.typeParams("Box /* c */ <T extends List<T>> {", 3))
                .isEqualTo("<T extends List<T>>");
        assertThat(ExtractInterface.typeParams("Box {", 3)).isEmpty();
    }

    static final String CARD = "src/main/java/org/acme/card/";

    /**
     * A parameter named like the last part of its file's package still takes the interface: the
     * package and import lines are no use of it. A method a framework annotation exposes keeps
     * returning the class, though no source calls it: the framework reads the result as the class.
     */
    @Test
    void thePackageNameIsNoUseAndAFrameworkMethodKeepsItsReturnType(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(
                dir,
                RenameFixture.files(CARD + "Card.java", """
                package org.acme.card;

                public class Card {
                    public String owner() {
                        return "ann";
                    }

                    public int number() {
                        return 1;
                    }
                }
                """, CARD + "Exposed.java", """
                package org.acme.card;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                @interface Exposed {
                    String value();
                }
                """, CARD + "Labels.java", """
                package org.acme.card;

                final class Labels {
                    static String label(Card card) {
                        return card.owner();
                    }

                    @Exposed("card")
                    public Card find() {
                        return new Card();
                    }
                }
                """));

        assertThat(f.extract("Card", "Owned", "owner")).endsWith("""
                Switched to Owned, calling only its methods (1 line):
                  src/main/java/org/acme/card/Labels.java:4  static String label(Owned card) {
                Also left as Card, where only the class will do (2 lines):
                  src/main/java/org/acme/card/Labels.java  1 in a method a framework calls, 1 constructor call""");
        assertThat(f.checkExtract()).isEqualTo("Proven: it compiles.");
    }

    /**
     * Where only the class will do (a constructor call, a type argument, a cast), the places are
     * counted per file, so a class used widely still answers inside the MCP budget with every place
     * an agent could change listed at its line.
     */
    @Test
    void routinePlacesAreCountedPerFileSoAWidelyUsedClassAnswersInTheMcpBudget(@TempDir Path dir) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(STOCK + "Item.java", """
                package org.acme.stock;

                public class Item {
                    public int price() {
                        return 1;
                    }

                    public String name() {
                        return "pen";
                    }
                }
                """);
        for (int i = 0; i < 10; i++) {
            files.put(STOCK + "Shelf" + i + ".java", """
                    package org.acme.stock;

                    import java.util.List;

                    final class Shelf%1$d {
                        static final List<Item> FRONT_ROW_OF_SHELF_%1$d = List.of(new Item());
                        static final List<Item> BACK_ROW_OF_SHELF_%1$d = List.of(new Item());
                        static final List<Item> TOP_ROW_OF_SHELF_%1$d = List.of(new Item());
                        static final Object LABEL_OF_SHELF_%1$d = (Item) FRONT_ROW_OF_SHELF_%1$d.getFirst();

                        static int label%1$d(Item item) {
                            return item.name().length();
                        }
                    }
                    """.formatted(i));
        }
        RenameFixture f = new RenameFixture(dir, files);

        String answer = f.extract(2000, "Item", "Priced", "price");

        assertThat(answer).doesNotContain("TRUNCATED").contains("""
                Nothing outside Item could take Priced as it stands.
                Left as Item (10 lines):
                  src/main/java/org/acme/stock/Shelf0.java:11  static int label0(Item item) {  (calls name())
                """);
        for (int i = 0; i < 10; i++) {
            assertThat(answer)
                    .contains("  src/main/java/org/acme/stock/Shelf" + i + ".java:11  static int label" + i
                            + "(Item item) {  (calls name())")
                    .contains("  src/main/java/org/acme/stock/Shelf" + i
                            + ".java  3 type arguments, 3 constructor calls, 1 cast");
        }
        assertThat(answer).contains("Also left as Item, where only the class will do (70 lines):\n");
    }

    @Test
    void anInterfaceThatDoesNotCompileIsUndoneWithItsNewFile(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, RenameFixture.files(SHOP + "Crate.java", """
                        package org.acme.shop;

                        public class Crate {
                            private static class Lid {}

                            public Object lid(Lid lid) {
                                return lid;
                            }
                        }
                        """));
        String before = f.read(SHOP + "Crate.java");

        assertThat(f.extract("Crate", "org.acme.api.Boxed")).startsWith("Extracted org.acme.api.Boxed from Crate");
        assertThat(f.exists(API + "Boxed.java")).isTrue();

        assertThat(f.checkExtract())
                .startsWith("Rolled back, nothing is changed (the new interface removed, 1 file restored): after the"
                        + " edit the code does not compile (");
        assertThat(f.read(SHOP + "Crate.java")).isEqualTo(before);
        assertThat(f.exists(API + "Boxed.java")).isFalse();
    }
}
