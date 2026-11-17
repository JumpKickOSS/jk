package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Owner.getPet finds a pet by name whatever spaces surround either name, and still skips new ones on request. */
class GetPetIgnoresSpacesAcceptanceTests {

    private static Owner owner() {
        Owner owner = new Owner();
        Pet leo = new Pet();
        leo.setName("Leo");
        leo.setId(1);
        owner.addPet(leo);
        Pet basil = new Pet();
        basil.setName(" Basil ");
        owner.addPet(basil);
        return owner;
    }

    @Test
    void namesMatchWithoutTheirSurroundingSpaces() {
        assertThat(owner().getPet(" leo ")).isNotNull().extracting(Pet::getName).isEqualTo("Leo");
        assertThat(owner().getPet("basil")).isNotNull();
        assertThat(owner().getPet("basil", true))
                .as("a new pet is still skipped when asked")
                .isNull();
        assertThat(owner().getPet("Rosy")).isNull();
    }
}
