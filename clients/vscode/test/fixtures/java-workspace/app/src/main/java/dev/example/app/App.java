package dev.example.app;

import com.google.common.collect.ImmutableList;
import dev.example.lib.Greeter;

public final class App {
    public static void main(String[] args) {
        System.out.println(ImmutableList.of(new Greeter().greet("jk")));
    }
}
