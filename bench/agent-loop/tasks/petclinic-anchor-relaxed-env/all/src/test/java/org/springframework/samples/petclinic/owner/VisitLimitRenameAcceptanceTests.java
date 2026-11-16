package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.samples.petclinic.system.VisitLimits;

/**
 * petclinic.visits.max-per-day is petclinic.visits.daily-limit (Java property dailyLimit) wherever it
 * is set: the properties file, and every environment variable a compose service or workflow sets for
 * it, as Spring's relaxed binding reads them.
 */
@SpringBootTest
class VisitLimitRenameAcceptanceTests {

    private static final Pattern VAR = Pattern.compile("\\b(PETCLINIC_VISITS_[A-Z0-9_]+)\\s*[:=]\\s*(\\d+)");

    @Autowired
    VisitLimits limits;

    private static int dailyLimit(VisitLimits l) throws Exception {
        return (int) VisitLimits.class.getMethod("getDailyLimit").invoke(l);
    }

    @Test
    void thePropertiesFileSetsTheNewKey() throws Exception {
        assertThat(dailyLimit(limits)).isEqualTo(20);
        assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
                .contains("petclinic.visits.daily-limit")
                .doesNotContain("max-per-day");
    }

    @Test
    void everyVariableThatSetsItBindsToTheNewProperty() throws Exception {
        for (String file : new String[] {"docker-compose.yml", ".github/workflows/load-test.yml"}) {
            String text = Files.readString(Path.of(file));
            Matcher m = VAR.matcher(text);
            assertThat(m.find())
                    .as(file + " still sets the limit through the environment")
                    .isTrue();
            do {
                var source = new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(m.group(1), m.group(2)));
                VisitLimits bound = new Binder(ConfigurationPropertySources.from(source))
                        .bind("petclinic.visits", VisitLimits.class)
                        .orElseGet(VisitLimits::new);
                assertThat(dailyLimit(bound)).as(file + ": " + m.group(1)).isEqualTo(Integer.parseInt(m.group(2)));
            } while (m.find());
        }
    }
}
