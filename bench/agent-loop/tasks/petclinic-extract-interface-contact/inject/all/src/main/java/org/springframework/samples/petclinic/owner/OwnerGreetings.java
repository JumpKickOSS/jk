package org.springframework.samples.petclinic.owner;

/** How a letter to an owner opens. */
final class OwnerGreetings {

	private OwnerGreetings() {
	}

	static String greet(Owner owner) {
		return "Dear " + owner.getFirstName() + " of " + owner.getCity();
	}

}
