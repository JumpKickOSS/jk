package com.acme;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** The app's model: a JavaFX property, usable without a display. */
public final class Greeting {
    private final StringProperty text = new SimpleStringProperty("Hello");

    public StringProperty textProperty() {
        return text;
    }

    public String greet(String name) {
        return text.get() + ", " + name;
    }
}
