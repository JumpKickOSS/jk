package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

/** Hidden: the class is Customer, it still maps the owners table, and stored owners still load. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CustomerRenameTests {

    @Autowired
    OwnerRepository owners;

    @Test
    void theRenamedClassKeepsItsTableAndItsData() {
        assertThat(Customer.class.getAnnotation(Table.class).name()).isEqualTo("owners");
        Customer george = this.owners.findById(1).orElseThrow();
        assertThat(george.getLastName()).isEqualTo("Franklin");
    }
}
