package org.springframework.samples.petclinic.owner;

/** An owner's contact details, one line each, for printed cards. */
final class ContactCards {

	private ContactCards() {
	}

	static String card(Owner owner) {
		return owner.getAddress() + ", " + owner.getCity() + " · " + owner.getTelephone();
	}

}
