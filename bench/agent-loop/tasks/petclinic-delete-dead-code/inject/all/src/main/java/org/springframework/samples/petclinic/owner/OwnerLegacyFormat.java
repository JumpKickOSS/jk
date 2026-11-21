package org.springframework.samples.petclinic.owner;

/** The owner name as the old listing printed it. */
final class OwnerLegacyFormat {

	private OwnerLegacyFormat() {
	}

	static String format(Owner owner) {
		return owner.formatLegacyName();
	}

}
