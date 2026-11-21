package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.samples.petclinic.model.Person;
import org.springframework.samples.petclinic.vet.Vet;

/** Hidden acceptance test for the change task: getDisplayName is declared in Person alone and works on a Vet. */
class PersonDisplayNameTests {

	@Test
	void personDeclaresItAndOwnerDoesNot() throws NoSuchMethodException {
		assertThat(Person.class.getDeclaredMethod("getDisplayName").getReturnType()).isEqualTo(String.class);
		assertThat(Arrays.stream(Owner.class.getDeclaredMethods()).map(Method::getName))
			.doesNotContain("getDisplayName");
	}

	@Test
	void aVetAndAnOwnerHaveIt() {
		Vet vet = new Vet();
		vet.setFirstName("James");
		vet.setLastName("Carter");
		assertThat(vet.getDisplayName()).isEqualTo("James Carter");
		Owner owner = new Owner();
		owner.setFirstName("George");
		owner.setLastName("Franklin");
		owner.setCity("Madison");
		assertThat(OwnerLabels.label(owner)).isEqualTo("George Franklin (Madison)");
	}

}
