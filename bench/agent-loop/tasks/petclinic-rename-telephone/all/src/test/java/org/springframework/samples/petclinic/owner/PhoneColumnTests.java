package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.jdbc.core.JdbcTemplate;

/** Hidden: the property is phone, and the data stored in the telephone column still loads into it. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PhoneColumnTests {

    @Autowired
    OwnerRepository owners;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theStoredTelephoneLoadsAsThePhone() {
        String stored = this.jdbc.queryForObject("SELECT telephone FROM owners WHERE id = 1", String.class);
        Owner george = this.owners.findById(1).orElseThrow();
        assertThat(george.getPhone()).isEqualTo(stored).isEqualTo("6085551023");
    }
}
