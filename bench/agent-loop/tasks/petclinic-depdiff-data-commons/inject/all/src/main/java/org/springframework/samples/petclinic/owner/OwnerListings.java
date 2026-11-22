package org.springframework.samples.petclinic.owner;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** How the owner listings page and sort. */
final class OwnerListings {

	private OwnerListings() {
	}

	static Pageable everyOwner() {
		return Pageable.unpaged(Sort.by("lastName"));
	}

	static Pageable firstPage(int size) {
		return PageRequest.of(0, size, Sort.by(Sort.Direction.ASC, "lastName", "firstName"));
	}

	static Sort newestFirst(Sort sort) {
		return sort.reverse();
	}

	static int cap(int max) {
		return Limit.of(max).max();
	}

	static List<Sort.Order> flipped(Sort sort) {
		return sort.stream().map(Sort.Order::reverse).toList();
	}

	static Sort byCity() {
		return Sort.by(Sort.Order.asc("city"), Sort.Order.desc("lastName"));
	}

}
