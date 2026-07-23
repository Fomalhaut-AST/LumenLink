package io.lumenlink.ui;

import io.lumenlink.config.AppConfig;
import io.lumenlink.network.DirectPeerPolicy;
import io.lumenlink.network.SignalClient;
import io.lumenlink.network.SignalMessage;
import io.lumenlink.webrtc.DirectPeerSession;
import java.net.URI;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class MainWindow {
    private final Stage stage;
    private final Label status = new Label("Ready. Direct peer-to-peer mode is enabled.");
    private SignalClient signalClient;
    private DirectPeerSession peerSession;
    private String role;

    public MainWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        AppConfig defaults = AppConfig.defaults();
        TextField signalUrl = new TextField(defaults.signalingUrl().toString());
        TextField roomCode = new TextField(defaults.roomCode());
        TextField stunUrl = new TextField(defaults.stunUrl());

        GridPane settings = new GridPane();
        settings.setHgap(10);
        settings.setVgap(8);
        settings.addRow(0, new Label("Signaling URL"), signalUrl);
        settings.addRow(1, new Label("Session code"), roomCode);
        settings.addRow(2, new Label("STUN URL"), stunUrl);

        Button host = new Button("Share this screen");
        Button connect = new Button("Connect to peer");
        host.setOnAction(event -> connectToSignal(signalUrl, roomCode, stunUrl, "HOST"));
        connect.setOnAction(event -> connectToSignal(signalUrl, roomCode, stunUrl, "CONTROLLER"));
        HBox actions = new HBox(8, host, connect);

        Label viewerHint = new Label("Remote desktop stream will appear here after direct ICE connectivity succeeds.");
        StackPane viewer = new StackPane(viewerHint);
        viewer.setMinSize(760, 440);
        viewer.setStyle("-fx-background-color: #1c2024; -fx-text-fill: #d7dde5;");

        VBox footer = new VBox(8, actions, status);
        footer.setPadding(new Insets(10, 0, 0, 0));
        BorderPane root = new BorderPane(viewer, settings, null, footer, null);
        root.setPadding(new Insets(12));

        stage.setTitle("LumenLink");
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setScene(new Scene(root));
        stage.show();
    }

    private void connectToSignal(TextField signalUrl, TextField roomCode, TextField stunUrl, String role) {
        try {
            AppConfig config = new AppConfig(URI.create(signalUrl.getText().trim()), roomCode.getText().trim(), stunUrl.getText().trim(), true);
            DirectPeerPolicy.IcePlan icePlan = DirectPeerPolicy.createIcePlan(config);
            if (signalClient != null) signalClient.close();
            if (peerSession != null) peerSession.close();
            this.role = role;
            signalClient = new SignalClient(message -> Platform.runLater(() -> handleSignalMessage(message)));
            peerSession = new DirectPeerSession(
                    icePlan,
                    message -> signalClient.send(new SignalMessage(message.type(), config.roomCode(), message.payload()))
                            .exceptionally(error -> {
                                Platform.runLater(() -> status.setText("Could not forward signaling: " + error.getMessage()));
                                return null;
                            }),
                    message -> Platform.runLater(() -> status.setText(message))
            );
            status.setText("Connecting to signaling server...");
            signalClient.connect(config.signalingUrl())
                    .thenCompose(socket -> signalClient.send(new SignalMessage(SignalMessage.Type.JOIN, config.roomCode(), Map.of("role", role))))
                    .thenRun(() -> Platform.runLater(() -> status.setText("Joined session. Waiting for the other device...")))
                    .exceptionally(error -> {
                        Platform.runLater(() -> status.setText("Signaling error: " + error.getMessage()));
                        return null;
                    });
        } catch (IllegalArgumentException error) {
            status.setText("Configuration error: " + error.getMessage());
        }
    }

    private void handleSignalMessage(SignalMessage message) {
        switch (message.type()) {
            case PEER_READY -> {
                if ("CONTROLLER".equals(role)) peerSession.createOffer();
                else status.setText("Peer found. Waiting for controller offer...");
            }
            case OFFER, ANSWER, ICE_CANDIDATE -> peerSession.handleSignal(message);
            case PEER_LEFT -> status.setText("The other device left the session.");
            case ERROR -> status.setText("Server error: " + message.payload().getOrDefault("message", "unknown error"));
            default -> { }
        }
    }
}
