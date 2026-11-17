/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.owner;

import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owners API the front desk's scripts read.
 */
@RestController
@RequestMapping("/api/owners")
class OwnerApiController {

    /** One pet as the API shows it. */
    record PetView(String name, LocalDate birthDate) {}

    private final OwnerRepository owners;

    OwnerApiController(OwnerRepository owners) {
        this.owners = owners;
    }

    @GetMapping("/{ownerId}/pets")
    List<PetView> pets(@PathVariable("ownerId") int ownerId) {
        return this.owners
                .findById(ownerId)
                .map(owner -> owner.getPets().stream()
                        .map(p -> new PetView(p.getName(), p.getBirthDate()))
                        .toList())
                .orElse(List.of());
    }
}
