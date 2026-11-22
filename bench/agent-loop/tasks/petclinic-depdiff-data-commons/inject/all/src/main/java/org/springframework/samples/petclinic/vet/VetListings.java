package org.springframework.samples.petclinic.vet;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** How the vet listing pages and sorts. */
final class VetListings {

	private VetListings() {
	}

	static Pageable page(int page, int size) {
		return PageRequest.of(page, size, Sort.by("lastName").and(Sort.by("firstName")));
	}

	static PageRequest nextAfter(PageRequest request) {
		return request.next().withSort(Sort.Direction.DESC, "lastName");
	}

}
