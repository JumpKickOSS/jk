package com.acme;

import com.acme.events.Role;
import com.acme.events.UserCreated;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        UserCreated event = UserCreated.newBuilder()
                .setId(7L)
                .setEmail("ada@acme.com")
                .setRole(Role.ADMIN)
                .build();
        System.out.println(event.getEmail() + " is " + event.getRole());
    }
}
