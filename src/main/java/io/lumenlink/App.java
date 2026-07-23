package io.lumenlink;

import io.lumenlink.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

public final class App extends Application {
    @Override
    public void start(Stage stage) {
        new MainWindow(stage).show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
