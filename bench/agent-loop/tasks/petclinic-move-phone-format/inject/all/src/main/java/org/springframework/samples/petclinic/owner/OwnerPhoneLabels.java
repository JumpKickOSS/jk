package org.springframework.samples.petclinic.owner;

import java.util.List;

/** How an owner's telephone is shown in a list. */
final class OwnerPhoneLabels {

	private OwnerPhoneLabels() {
	}

	static String label(Owner owner) {
		return owner.getLastName() + ": " + Owner.formatTelephone(owner.getTelephone());
	}

	static List<String> all(List<String> telephones) {
		return telephones.stream().map(Owner::formatTelephone).toList();
	}

}
