package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * owners.telephone is owners.phone in every database's schema and seed, the property keeps its name, and the
 * changelog keeps its history (the push's judgment count must not talk the agent into rewriting it).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PhoneColumnEverySchemaAcceptanceTests {

    private static final Pattern OWNERS = Pattern.compile(
            "(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?owners\\s*\\((.*?)\\)\\s*(?:engine[^;]*)?;");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OwnerRepository owners;

    @Test
    void theColumnIsPhoneAndThePropertyReadsIt() {
        assertThat(jdbc.queryForObject("SELECT COUNT(phone) FROM owners", Integer.class))
                .isPositive();
        assertThat(owners.findById(1))
                .hasValueSatisfying(o -> assertThat(o.getTelephone()).isNotBlank());
    }

    @Test
    void everyDatabasesSchemaNamesTheColumnPhone() throws Exception {
        Resource[] schemas = new PathMatchingResourcePatternResolver().getResources("classpath*:db/*/schema.sql");
        assertThat(schemas).hasSizeGreaterThanOrEqualTo(3);
        for (Resource r : schemas) {
            Matcher m = OWNERS.matcher(r.getContentAsString(StandardCharsets.UTF_8));
            assertThat(m.find()).as(r.getURL() + " creates owners").isTrue();
            assertThat(m.group(1))
                    .as(r.getURL().toString())
                    .containsPattern("(?i)\\bphone\\b")
                    .doesNotContainPattern("(?i)\\btelephone\\b");
        }
    }

    @Test
    void noSqlFileNamesTheOldColumn() throws Exception {
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:db/**/*.sql")) {
            assertThat(r.getContentAsString(StandardCharsets.UTF_8))
                    .as(r.getURL().toString())
                    .doesNotContainPattern("(?i)\\btelephone\\b");
        }
    }

    @Test
    void theChangelogKeepsItsHistory() throws Exception {
        assertThat(Files.readString(Path.of("docs/CHANGELOG.md")))
                .contains("- Owners gained a `telephone` column (`VARCHAR(20)`), ten digits, validated in the form.");
    }
}
