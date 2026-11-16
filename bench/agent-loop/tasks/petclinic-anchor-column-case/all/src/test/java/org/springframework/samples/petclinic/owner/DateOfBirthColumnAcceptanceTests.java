package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/** pets.birth_date is date_of_birth everywhere: every schema, seed and report, and the mapping. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DateOfBirthColumnAcceptanceTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	OwnerRepository owners;

	@Test
	void theColumnIsDateOfBirthAndTheMappingReadsIt() {
		assertThat(jdbc.queryForObject("SELECT COUNT(date_of_birth) FROM pets", Integer.class)).isPositive();
		assertThat(owners.findById(1)).hasValueSatisfying(o -> assertThat(o.getPets().get(0).getBirthDate()).isNotNull());
	}

	@Test
	void everyReportQueryRuns() throws Exception {
		for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:db/reports/*.sql")) {
			String sql = r.getContentAsString(StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "").replace(";", "");
			assertThat(jdbc.queryForList(sql)).as(r.getFilename()).isNotEmpty();
		}
	}

	@Test
	void noSqlFileNamesTheOldColumn() throws Exception {
		for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:db/**/*.sql")) {
			assertThat(r.getContentAsString(StandardCharsets.UTF_8).toLowerCase())
				.as(r.getURL().toString())
				.doesNotContainPattern("\\bbirth_date\\b");
		}
	}

}
