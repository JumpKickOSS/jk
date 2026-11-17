package org.springframework.samples.petclinic.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The configured daily limit is 25; the variables that override it elsewhere are left as they were. */
@SpringBootTest
class VisitLimitValueAcceptanceTests {

    @Autowired
    VisitLimits limits;

    @Test
    void theConfiguredLimitIs25() throws Exception {
        assertThat(limits.getMaxPerDay()).isEqualTo(25);
        assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
                .contains("petclinic.visits.max-per-day=25");
    }

    @Test
    void theOverridesAreUnchanged() throws Exception {
        assertThat(Files.readString(Path.of("docker-compose.yml"))).contains("      - PETCLINIC_VISITS_MAXPERDAY=40\n");
        assertThat(Files.readString(Path.of(".github/workflows/load-test.yml")))
                .contains("      PETCLINIC_VISITS_MAXPERDAY: 500\n");
    }
}
