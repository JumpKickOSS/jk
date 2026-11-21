// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A move of an instance member to the class its type extends pulls it up: the code lands in the
 * superclass with the imports it needs, every use binds to it there unedited, another subclass that
 * declares the method gets {@code @Override}, then it compiles and the pulled-up symbols are proven
 * to have the references the old ones had. What would change without a compile error (state the
 * superclass does not have, a {@code super} call, an override every subclass would inherit, an
 * overload a call may now pick) is listed and nothing is written.
 */
class PullUpTest {

    static final String BASE = "src/main/java/org/acme/base/";
    static final String SHOP = "src/main/java/org/acme/shop/";

    static final String ITEM = """
            package org.acme.base;

            /** Something on sale. */
            public abstract class Item {

                protected final String name;

                protected Item(String name) {
                    this.name = name;
                }

                public String name() {
                    return name;
                }
            }
            """;

    static final String BOOK = """
            package org.acme.shop;

            import java.util.Locale;
            import org.acme.base.Item;

            public class Book extends Item {

                private final int pages;

                public String shelf = "A";

            protected int stock = 1;

                public Book(String name, int pages) {
                    super(name);
                    this.pages = pages;
                }

                /** The name, upper-cased. */
                public String label() {
                    return name().toUpperCase(Locale.ROOT);
                }

                public int pages() {
                    return pages;
                }

                public String summary() {
                    return label() + " (" + pages + ")";
                }

                public String plain() {
                    return super.name();
                }

                @Override
                public String toString() {
                    return name;
                }

                private String code() {
                    return "b";
                }
            }
            """;

    static final String TOY = """
            package org.acme.shop;

            import org.acme.base.Item;

            public class Toy extends Item {

                public Toy(String name) {
                    super(name);
                }

                public String label() {
                    return "toy " + name;
                }
            }
            """;

    static Map<String, String> shop() {
        return RenameFixture.files(
                BASE + "Item.java", ITEM, SHOP + "Book.java", BOOK, SHOP + "Toy.java", TOY, SHOP + "Shelf.java", """
                package org.acme.shop;

                import java.util.List;

                class Shelf {
                    List<String> labels(List<Book> books) {
                        return books.stream().map(Book::label).toList();
                    }

                    String first(Book book) {
                        return book.label() + book.shelf + book.stock;
                    }
                }
                """);
    }

    @Test
    void aMethodMovesUpWithItsImportsAndAnotherSubclassOverridesItThenProves(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.move("Book.label", "Item");

        assertThat(answer)
                .startsWith("Pulled up Book.label() to org.acme.base.Item: 3 uses and 1 import, 1 @Override in"
                        + " 3 files; each use javac bound.");
        assertThat(f.read(BASE + "Item.java")).isEqualTo("""
                package org.acme.base;

                import java.util.Locale;

                /** Something on sale. */
                public abstract class Item {

                    protected final String name;

                    protected Item(String name) {
                        this.name = name;
                    }

                    public String name() {
                        return name;
                    }

                    /** The name, upper-cased. */
                    public String label() {
                        return name().toUpperCase(Locale.ROOT);
                    }
                }
                """);
        assertThat(f.read(SHOP + "Book.java"))
                .doesNotContain("import java.util.Locale;")
                .doesNotContain("label() {")
                .contains("return label() + \" (\" + pages + \")\";");
        assertThat(f.read(SHOP + "Toy.java")).contains("""
                    @Override
                    public String label() {
                """);
        assertThat(f.checkMove())
                .isEqualTo("Proven: it compiles, and Item.label has the 4 references Book.label had, where the move"
                        + " put them; nothing else binds to the new symbol.");
    }

    @Test
    void aFieldMovesUpAndItsUsesBindThere(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Book.shelf", "org.acme.base.Item"))
                .startsWith("Pulled up Book.shelf to org.acme.base.Item: ");

        assertThat(f.read(BASE + "Item.java")).contains("""
                    public String shelf = "A";
                }
                """);
        assertThat(f.read(SHOP + "Book.java")).doesNotContain("shelf");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Item.shelf has the 2 references Book.shelf had");
    }

    @Test
    void stateTheSuperclassDoesNotHaveIsListedAndNothingWritten(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Book.summary", "Item"))
                .startsWith("Not moved, nothing is written: pulling up Book.summary() to org.acme.base.Item would"
                        + " break or change:")
                .contains("uses Book.label, which org.acme.base.Item does not have  " + SHOP + "Book.java:29")
                .contains("uses Book.pages, which org.acme.base.Item does not have  " + SHOP + "Book.java:29");
        assertThat(f.move("Book.code", "Item")).contains("Book.code is private; its own type would not see it in");
        assertThat(f.move("Book.stock", "Item"))
                .contains("Book.stock is protected; " + SHOP + "Shelf.java uses it and would not see it in"
                        + " org.acme.base.Item");
        assertThat(f.read(SHOP + "Book.java")).isEqualTo(BOOK);
        assertThat(f.read(BASE + "Item.java")).isEqualTo(ITEM);
    }

    @Test
    void aSuperCallAndAnOverrideEverySubclassWouldInheritAreHazards(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Book.plain", "Item"))
                .contains("calls super, which in org.acme.base.Item is its own superclass  " + SHOP + "Book.java:33");
        assertThat(f.move("Book.toString", "Item"))
                .contains("toString is a method of Object; in org.acme.base.Item it would override it for every"
                        + " subclass")
                .contains("overrides a supertype's method; in org.acme.base.Item it would override it for every"
                        + " subclass");
        assertThat(f.read(SHOP + "Book.java")).isEqualTo(BOOK);
    }

    @Test
    void anOverloadInAnotherSubclassIsAHazard(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Toy.java", TOY.replace("public String label() {", "public String label(String prefix) {"));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Book.label", "Item"))
                .contains("Toy.label takes other parameters; a call may pick the pulled-up method instead  " + SHOP
                        + "Toy.java:11");
        assertThat(f.read(SHOP + "Book.java")).isEqualTo(BOOK);
    }

    @Test
    void anInstanceMemberToATypeItDoesNotExtendIsNotMoved(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Book.label", "Toy"))
                .startsWith("Not moved, nothing is written: Book.label() is an instance method; a move takes static"
                        + " members, or an instance member up to the class its type extends.");
        assertThat(f.read(SHOP + "Book.java")).isEqualTo(BOOK);
    }
}
