package org.springframework.samples.petclinic.owner;

/** How an owner is named in a list: the display name, then the city. */
final class OwnerLabels {

	private OwnerLabels() {
	}

	static String label(Owner owner) {
		return owner.getDisplayName() + " (" + owner.getCity() + ")";
	}

}
