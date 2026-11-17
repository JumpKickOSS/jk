package org.springframework.samples.petclinic.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

/**
 * application.properties sets petclinic.visits.daily-limit, and that value is the limit the
 * application reads, however its Java property is named; every variable that sets the limit sets it.
 */
@SpringBootTest
class VisitLimitKeyAcceptanceTests {

    private static final Pattern VAR = Pattern.compile("\\b(PETCLINIC_VISITS_[A-Z0-9_]+)\\s*[:=]\\s*(\\d+)");

    @Autowired
    VisitLimits limits;

    /** The limit as the application reads it: VisitLimits' one int getter. */
    private static int limit(VisitLimits l) throws Exception {
        Method getter = Arrays.stream(VisitLimits.class.getMethods())
                .filter(m ->
                        m.getName().startsWith("get") && m.getReturnType() == int.class && m.getParameterCount() == 0)
                .findFirst()
                .orElseThrow();
        return (int) getter.invoke(l);
    }

    @Test
    void thePropertiesFileSetsTheNewKeyAndTheApplicationReadsIt() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
                .contains("petclinic.visits.daily-limit=20")
                .doesNotContain("max-per-day");
        assertThat(limit(limits)).as("the configured limit, not the default").isEqualTo(20);
    }

    @Test
    void everyVariableThatSetsTheLimitStillSetsIt() throws Exception {
        for (String file : new String[] {"docker-compose.yml", ".github/workflows/load-test.yml"}) {
            Matcher m = VAR.matcher(Files.readString(Path.of(file)));
            assertThat(m.find())
                    .as(file + " still sets the limit through the environment")
                    .isTrue();
            do {
                var source = new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(m.group(1), m.group(2)));
                VisitLimits bound = new Binder(ConfigurationPropertySources.from(source))
                        .bind("petclinic.visits", VisitLimits.class)
                        .orElseGet(VisitLimits::new);
                assertThat(limit(bound)).as(file + ": " + m.group(1)).isEqualTo(Integer.parseInt(m.group(2)));
            } while (m.find());
        }
    }
}
