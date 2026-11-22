package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Hidden acceptance test for the change task: OwnerContact declares the three contact getters, Owner
 * implements it, the card takes it and the greeting, which reads the first name too, keeps Owner.
 */
class OwnerContactTests {

	@Test
	void theInterfaceDeclaresTheContactGettersAndOwnerImplementsIt() {
		assertThat(OwnerContact.class.isInterface()).isTrue();
		assertThat(Arrays.stream(OwnerContact.class.getDeclaredMethods()).map(Method::getName))
			.containsExactlyInAnyOrder("getAddress", "getCity", "getTelephone");
		assertThat(OwnerContact.class.isAssignableFrom(Owner.class)).isTrue();
	}

	@Test
	void theCardTakesTheInterfaceAndTheGreetingKeepsOwner() throws NoSuchMethodException {
		ContactCards.class.getDeclaredMethod("card", OwnerContact.class);
		OwnerGreetings.class.getDeclaredMethod("greet", Owner.class);
		Owner owner = new Owner();
		owner.setFirstName("George");
		owner.setAddress("110 W. Liberty St.");
		owner.setCity("Madison");
		owner.setTelephone("6085551023");
		assertThat(ContactCards.card(owner)).isEqualTo("110 W. Liberty St., Madison · 6085551023");
		assertThat(OwnerGreetings.greet(owner)).isEqualTo("Dear George of Madison");
	}

}
