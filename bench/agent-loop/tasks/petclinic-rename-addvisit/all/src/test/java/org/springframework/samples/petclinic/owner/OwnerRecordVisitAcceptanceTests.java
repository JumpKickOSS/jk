package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: Owner.addVisit is renamed recordVisit, every caller follows. */
class OwnerRecordVisitAcceptanceTests {

    @Test
    void addVisitIsGone() {
        assertThat(Arrays.stream(Owner.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("addVisit");
    }

    @Test
    void recordVisitAddsTheVisitToThePet() {
        Owner owner = new Owner();
        Pet pet = new Pet();
        pet.setName("Leo");
        pet.setId(7);
        owner.addPet(pet);
        Visit visit = new Visit();
        visit.setDescription("checkup");
        owner.recordVisit(7, visit);
        assertThat(pet.getVisits()).containsExactly(visit);
    }
}
