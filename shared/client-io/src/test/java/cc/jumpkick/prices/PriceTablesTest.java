// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which table is in effect: the store copy when it is newer than the built-in one, read again when it changes. */
class PriceTablesTest {

    @TempDir
    Path dir;

    private static String table(Instant published, String model) {
        return "published = " + published + "\n[models.\"" + model + "\"]\ninput = 7\noutput = 9\n"
                + "retrieved = \"2026-11-20\"\n";
    }

    @Test
    void without_a_store_copy_the_built_in_table_applies() {
        assertThat(PriceTables.current(dir.resolve("prices.toml"))).isSameAs(PriceTable.builtIn());
    }

    @Test
    void a_model_the_table_does_not_know_is_priced_once_a_newer_copy_that_knows_it_lands() throws IOException {
        Path file = dir.resolve("prices.toml");
        assertThat(PriceTables.current(file).price("brand-new-model-1")).isEmpty();

        Instant later = PriceTable.builtIn().published().plusSeconds(86_400);
        Files.writeString(file, table(later, "brand-new-model"));

        assertThat(PriceTables.current(file).price("brand-new-model-1"))
                .hasValueSatisfying(p -> assertThat(p.input()).isEqualTo(7.0));
    }

    @Test
    void a_store_copy_published_no_later_than_the_built_in_one_is_not_used() throws IOException {
        Path file = dir.resolve("prices.toml");
        Files.writeString(file, table(PriceTable.builtIn().published(), "stale-model"));

        assertThat(PriceTables.current(file)).isSameAs(PriceTable.builtIn());
    }

    @Test
    void an_unreadable_store_copy_leaves_the_built_in_table() throws IOException {
        Path file = dir.resolve("prices.toml");
        Files.writeString(file, "published = garbage");

        assertThat(PriceTables.current(file)).isSameAs(PriceTable.builtIn());
    }

    @Test
    void a_replaced_store_copy_is_read_again() throws IOException {
        Path file = dir.resolve("prices.toml");
        Instant later = PriceTable.builtIn().published().plusSeconds(86_400);
        Files.writeString(file, table(later, "first-model"));
        assertThat(PriceTables.current(file).price("first-model")).isPresent();

        Path next = dir.resolve("next.toml");
        Files.writeString(next, table(later.plusSeconds(60), "second-model"));
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING);

        assertThat(PriceTables.current(file).price("second-model")).isPresent();
        assertThat(PriceTables.current(file).price("first-model")).isEmpty();
    }
}
