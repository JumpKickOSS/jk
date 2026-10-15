package com.acme;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

public final class HelloApp extends Application {
    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(new Label(new Greeting().greet("JavaFX")), 320, 120));
        stage.show();
    }
}
