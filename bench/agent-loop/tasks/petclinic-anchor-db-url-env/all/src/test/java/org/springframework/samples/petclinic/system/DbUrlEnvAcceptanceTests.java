package org.springframework.samples.petclinic.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The application's JDBC URL variable is PETCLINIC_DB_URL wherever it is read or set; the container's own keep theirs. */
class DbUrlEnvAcceptanceTests {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void thePostgresProfileReadsTheNewName() throws Exception {
        assertThat(read("src/main/resources/application-postgres.properties"))
                .contains("${PETCLINIC_DB_URL")
                .doesNotContain("POSTGRES_URL");
    }

    @Test
    void everyFileThatSetsItSetsTheNewName() throws Exception {
        for (String f : new String[] {"docker-compose.yml", ".github/workflows/postgres-smoke.yml"}) {
            assertThat(read(f)).as(f).contains("PETCLINIC_DB_URL").doesNotContain("POSTGRES_URL");
        }
    }

    @Test
    void theContainersOwnVariablesKeepTheirNames() throws Exception {
        for (String f : new String[] {"docker-compose.yml", ".github/workflows/postgres-smoke.yml"}) {
            assertThat(read(f)).as(f).contains("POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_DB");
        }
    }
}
