// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.prices.PriceTable.CacheWindow;
import cc.jumpkick.prices.PriceTable.ModelPrice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PriceTableTest {

    private static PriceTable parse(String toml) throws IOException {
        return PriceTable.parse(toml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void the_built_in_table_parses_and_prices_every_model_it_lists() {
        PriceTable table = PriceTable.builtIn();

        assertThat(table.models()).isNotEmpty().allSatisfy((id, price) -> {
            assertThat(table.price(id)).contains(price);
            assertThat(price.input()).isPositive();
            assertThat(price.output()).isPositive();
        });
    }

    @Test
    void a_model_id_is_priced_by_the_longest_key_it_starts_with() throws IOException {
        PriceTable table = parse("""
                published = 2026-11-20T00:00:00Z
                [models."grok-4"]
                input = 1
                output = 2
                retrieved = "2026-11-20"
                [models."grok-4.7"]
                input = 3
                output = 4
                retrieved = "2026-11-20"
                """);

        assertThat(table.price("grok-4.7-high")).map(ModelPrice::input).contains(3.0);
        assertThat(table.price("grok-4")).map(ModelPrice::input).contains(1.0);
        assertThat(table.price("grok-4-fast")).map(ModelPrice::input).contains(1.0);
        assertThat(table.price("grok-45"))
                .as("a prefix that is not a whole segment")
                .isEmpty();
        assertThat(table.price("unknown-model")).isEmpty();
    }

    @Test
    void absent_cache_rates_fall_back_to_input_and_the_one_hour_write_to_the_five_minute_one() throws IOException {
        PriceTable table = parse("""
                published = 2026-11-20T00:00:00Z
                [models."plain"]
                input = 2.0
                output = 10.0
                retrieved = "2026-11-17"
                [models."cached"]
                input = 2.0
                cached_input = 0.1
                cache_write = 2.5
                output = 10.0
                retrieved = "2026-11-17"
                """);

        ModelPrice plain = table.price("plain").orElseThrow();
        assertThat(plain.cachedInput()).isEqualTo(2.0);
        assertThat(plain.cacheWrite()).isEqualTo(2.0);
        assertThat(plain.cacheWrite1h()).isEqualTo(2.0);
        assertThat(plain.retrieved()).isEqualTo(LocalDate.of(2026, 11, 17));
        ModelPrice cached = table.price("cached").orElseThrow();
        assertThat(cached.cacheWrite1h()).isEqualTo(2.5);
        assertThat(table.published()).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
    }

    @Test
    void tokens_are_priced_per_million_by_kind() {
        ModelPrice price = new ModelPrice(2.0, 10.0, 0.1, 2.5, 4.0, LocalDate.of(2026, 11, 17));

        assertThat(price.usd(1_000_000, 100_000, 2_000_000, 400_000, CacheWindow.FIVE_MINUTES))
                .isCloseTo(2.0 + 1.0 + 0.2 + 1.0, within(1e-9));
        assertThat(price.usd(0, 0, 0, 1_000_000, CacheWindow.ONE_HOUR)).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void keys_the_reader_does_not_know_are_skipped() throws IOException {
        PriceTable table = parse("""
                published = 2026-11-20T00:00:00Z
                format-note = "a later field"
                [models."m"]
                input = 1
                output = 2
                retrieved = "2026-11-20"
                batch_input = 0.5
                vendor = "acme"
                """);

        assertThat(table.price("m")).isPresent();
    }

    @Test
    void a_table_that_is_not_a_price_table_is_refused() {
        String row = "\n[models.\"m\"]\ninput = 1\noutput = 2\nretrieved = \"2026-11-20\"\n";
        String published = "published = 2026-11-20T00:00:00Z\n";

        assertThatThrownBy(() -> parse("not = [toml")).hasMessageContaining("not valid TOML");
        assertThatThrownBy(() -> parse(row)).hasMessageContaining("published");
        assertThatThrownBy(() -> parse(published)).hasMessageContaining("no models");
        assertThatThrownBy(() -> parse(published + row.replace("input = 1", "input = -1")))
                .hasMessageContaining("valid input");
        assertThatThrownBy(() -> parse(published + row.replace("output = 2\n", "")))
                .hasMessageContaining("valid output");
        assertThatThrownBy(() -> parse(published + row.replace("retrieved = \"2026-11-20\"\n", "")))
                .hasMessageContaining("retrieved");
        assertThatThrownBy(() -> parse(published + row.replace("models.\"m\"", "models.\"Bad Id\"")))
                .hasMessageContaining("malformed model id");
        assertThatThrownBy(() -> PriceTable.parse(new byte[] {(byte) 0xff, (byte) 0xfe}))
                .hasMessageContaining("UTF-8");
    }
}
