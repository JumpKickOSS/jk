// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An extracted interface declares the chosen public methods of a class as written, with the imports
 * their types need; the class implements it with {@code @Override} on each, then it compiles. The
 * variables of the class's type that call only those methods are listed as places that could take
 * the interface. What an interface cannot declare is refused with nothing written, and an edit that
 * does not compile is undone whole, the new file included.
 */
class ExtractInterfaceTest {

    static final String SHOP = "src/main/java/org/acme/shop/";
    static final String API = "src/main/java/org/acme/api/";

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
    void theChosenMethodsBecomeAnInterfaceTheClassImplementsAndTheVariablesThatCouldTakeItAreListed(@TempDir Path dir)
            throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.extract("Book", "Priced", "price", "total");

        assertThat(answer).isEqualTo("""
                Extracted org.acme.shop.Priced from Book: 3 methods (price, total); Book implements it, 3 @Override.
                  src/main/java/org/acme/shop/Priced.java  new
                  src/main/java/org/acme/shop/Book.java:7,16,21,26
                Could take Priced instead of Book, calling only its methods (4 lines):
                  src/main/java/org/acme/shop/Cart.java:6  private final Book book;
                  src/main/java/org/acme/shop/Cart.java:8  Cart(Book book) {
                  src/main/java/org/acme/shop/Cart.java:16  BigDecimal sum(Book first, Book second) {
                  src/main/java/org/acme/shop/Shelf.java:4  static void put(Book book) {}""");
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
    void aGenericClassIsRefused(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, RenameFixture.files(SHOP + "Box.java", """
                        package org.acme.shop;

                        public class Box<T> {
                            public T get() {
                                return null;
                            }
                        }
                        """));

        assertThat(f.extract("Box", "Holder"))
                .isEqualTo("Not extracted: Box declares type parameters; extract its interface by hand.");
        assertThat(f.exists(SHOP + "Holder.java")).isFalse();
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
