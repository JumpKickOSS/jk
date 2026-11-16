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

/** The owners table's telephone column is phone everywhere: every schema, the mapping, every report. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OwnerPhoneColumnAcceptanceTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	OwnerRepository owners;

	@Test
	void theColumnIsPhoneAndTheMappingReadsIt() {
		assertThat(jdbc.queryForObject("SELECT phone FROM owners WHERE id = 1", String.class)).isEqualTo("6085551023");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'OWNERS' AND COLUMN_NAME = 'TELEPHONE'",
				Integer.class))
			.isZero();
		assertThat(owners.findById(1)).hasValueSatisfying(o -> assertThat(o.getTelephone()).isEqualTo("6085551023"));
	}

	@Test
	void everyReportQueryRuns() throws Exception {
		Resource[] reports = new PathMatchingResourcePatternResolver().getResources("classpath*:db/reports/*.sql");
		assertThat(reports).isNotEmpty();
		for (Resource r : reports) {
			String sql = r.getContentAsString(StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "").replace(";", "");
			assertThat(jdbc.queryForList(sql)).as(r.getFilename()).isNotEmpty();
		}
	}

	@Test
	void theOtherDatabasesSchemasNameItPhone() throws Exception {
		for (String db : new String[] { "mysql", "postgres" }) {
			String schema = new PathMatchingResourcePatternResolver().getResource("classpath:db/" + db + "/schema.sql")
				.getContentAsString(StandardCharsets.UTF_8)
				.toLowerCase();
			assertThat(schema).as(db).contains("phone").doesNotContain("telephone");
		}
	}

}
