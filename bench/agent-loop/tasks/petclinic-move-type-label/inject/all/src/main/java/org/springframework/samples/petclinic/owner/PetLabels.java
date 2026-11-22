package org.springframework.samples.petclinic.owner;

import java.util.List;

/** How a pet is shown in a list: its name and its type. */
final class PetLabels {

	private PetLabels() {
	}

	static String label(Pet pet) {
		return pet.typeLabel();
	}

	static List<String> all(Owner owner) {
		return owner.getPets().stream().map(pet -> pet.typeLabel()).toList();
	}

}
