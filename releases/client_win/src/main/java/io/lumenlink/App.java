package io.lumenlink;

import io.lumenlink.ui.MainWindow;
import io.lumenlink.logging.SafeLog;
import javafx.application.Application;
import javafx.stage.Stage;

public final class App extends Application {
    @Override
    public void start(Stage stage) {
        SafeLog.info("client.started");
        new MainWindow(stage).show();
    }

    @Override
    public void stop() {
        SafeLog.info("client.stopped");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
