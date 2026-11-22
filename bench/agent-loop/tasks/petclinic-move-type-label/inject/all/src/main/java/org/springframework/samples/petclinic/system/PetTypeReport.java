package org.springframework.samples.petclinic.system;

import org.springframework.samples.petclinic.owner.Pet;

/** One pet as a report line, labelled by {@link Pet#typeLabel()}. */
public final class PetTypeReport {

	private PetTypeReport() {
	}

	public static String line(Pet pet) {
		return pet.getType() == null ? "unknown" : "- " + pet.typeLabel();
	}

}
