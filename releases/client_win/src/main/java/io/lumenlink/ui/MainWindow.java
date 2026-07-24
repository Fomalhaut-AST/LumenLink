package io.lumenlink.ui;

import io.lumenlink.config.AppConfig;
import io.lumenlink.control.ControlPermissionGate;
import io.lumenlink.control.WindowsInputInjector;
import io.lumenlink.device.DeviceIdentity;
import io.lumenlink.device.RemoteDevice;
import io.lumenlink.network.DirectPeerPolicy;
import io.lumenlink.network.SignalClient;
import io.lumenlink.network.SignalMessage;
import io.lumenlink.session.ControlSession;
import io.lumenlink.session.SessionQuality;
import io.lumenlink.webrtc.DirectPeerSession;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class MainWindow {
    private final Stage stage;
    private final Label status = new Label("Go online, then double-click a device to open remote control.");
    private final Label selfLabel = new Label();
    private final ObservableList<RemoteDevice> onlineDevices = FXCollections.observableArrayList();
    private final ListView<RemoteDevice> deviceList = new ListView<>(onlineDevices);

    private DeviceIdentity identity = DeviceIdentity.loadOrCreate();
    private final ControlPermissionGate permissionGate = new ControlPermissionGate();
    private WindowsInputInjector inputInjector;
    private SignalClient signalClient;
    private DirectPeerSession peerSession;
    private RemoteSessionWindow remoteWindow;
    private AppConfig config;
    private ControlSession activeSession;
    private SessionQuality negotiatedQuality = SessionQuality.defaults();
    private String pendingRequestSessionId;

    private Button goOnline;
    private Button goOffline;
    private TextField signalUrlField;
    private TextField networkCodeField;
    private TextField stunUrlField;
    private TextField displayNameField;
    private PasswordField roomPasswordField;
    private ComboBox<SessionQuality.Resolution> resolutionBox;
    private ComboBox<Integer> fpsBox;
    private ComboBox<Integer> bitrateBox;

    public MainWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        AppConfig defaults = AppConfig.defaults();
        signalUrlField = new TextField(defaults.signalingUrl().toString());
        networkCodeField = new TextField(defaults.roomCode());
        stunUrlField = new TextField(defaults.stunUrl());
        displayNameField = new TextField(identity.displayName());
        roomPasswordField = new PasswordField();
        roomPasswordField.setText(identity.roomPassword());
        resolutionBox = new ComboBox<>(FXCollections.observableArrayList(SessionQuality.Resolution.values()));
        fpsBox = new ComboBox<>();
        for (int fps : SessionQuality.FPS_PRESETS) {
            fpsBox.getItems().add(fps);
        }
        bitrateBox = new ComboBox<>();
        for (int kbps : SessionQuality.BITRATE_PRESETS_KBPS) {
            bitrateBox.getItems().add(kbps);
        }
        SessionQuality qualityDefaults = SessionQuality.defaults();
        resolutionBox.getSelectionModel().select(qualityDefaults.resolution());
        fpsBox.getSelectionModel().select(Integer.valueOf(qualityDefaults.fps()));
        bitrateBox.getSelectionModel().select(Integer.valueOf(qualityDefaults.maxBitrateKbps()));
        bitrateBox.setConverter(kbpsConverter());
        fpsBox.setConverter(fpsConverter());

        selfLabel.setText(formatSelf());
        selfLabel.setWrapText(true);
        signalUrlField.setPrefColumnCount(28);
        networkCodeField.setPrefColumnCount(28);
        stunUrlField.setPrefColumnCount(28);
        displayNameField.setPrefColumnCount(28);
        roomPasswordField.setPrefColumnCount(28);

        GridPane settings = new GridPane();
        settings.setHgap(10);
        settings.setVgap(8);
        settings.addRow(0, new Label("Signaling URL"), signalUrlField);
        settings.addRow(1, new Label("Network code"), networkCodeField);
        settings.addRow(2, new Label("STUN URL"), stunUrlField);
        settings.addRow(3, new Label("Display name"), displayNameField);
        settings.addRow(4, new Label("Room password"), roomPasswordField);
        settings.addRow(5, new Label("Resolution"), resolutionBox);
        settings.addRow(6, new Label("Frame rate"), fpsBox);
        settings.addRow(7, new Label("Max bitrate"), bitrateBox);
        settings.addRow(8, new Label("This device"), selfLabel);

        goOnline = new Button("Go online");
        goOffline = new Button("Go offline");
        goOffline.setDisable(true);
        goOnline.setOnAction(event -> goOnline());
        goOffline.setOnAction(event -> goOffline());
        HBox connectionActions = new HBox(8, goOnline, goOffline);

        VBox connectionPane = new VBox(10, new Label("Connection & quality"), settings, connectionActions);
        connectionPane.setPadding(new Insets(0, 12, 0, 0));
        HBox.setHgrow(connectionPane, Priority.ALWAYS);

        deviceList.setPlaceholder(new Label("No other devices online. Double-click a device to control it."));
        deviceList.setPrefWidth(320);
        deviceList.setMinWidth(260);
        VBox.setVgrow(deviceList, Priority.ALWAYS);
        deviceList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                requestControlOfSelected();
            }
        });

        Label deviceHint = new Label("Double-click a device to request control.\nClosing the remote window ends the session.");
        deviceHint.setWrapText(true);
        deviceHint.setStyle("-fx-text-fill: #6b7280;");
        VBox devicesPane = new VBox(8, new Label("Online devices"), deviceList, deviceHint);
        devicesPane.setMinWidth(280);
        devicesPane.setPrefWidth(340);
        HBox.setHgrow(devicesPane, Priority.ALWAYS);

        HBox body = new HBox(16, connectionPane, devicesPane);
        HBox.setHgrow(connectionPane, Priority.ALWAYS);
        status.setWrapText(true);

        VBox root = new VBox(12, body, status);
        root.setPadding(new Insets(14));
        VBox.setVgrow(body, Priority.ALWAYS);

        stage.setTitle("LumenLink");
        stage.setMinWidth(860);
        stage.setMinHeight(480);
        stage.setScene(new Scene(root, 960, 520));
        stage.setOnCloseRequest(event -> goOffline());
        stage.show();
        if (!identity.roomPassword().isBlank()) {
            Platform.runLater(this::goOnline);
        }
    }

    private static javafx.util.StringConverter<Integer> kbpsConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : value + " kbps";
            }

            @Override
            public Integer fromString(String string) {
                return Integer.parseInt(string.replace(" kbps", "").trim());
            }
        };
    }

    private static javafx.util.StringConverter<Integer> fpsConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : value + " FPS";
            }

            @Override
            public Integer fromString(String string) {
                return Integer.parseInt(string.replace(" FPS", "").trim());
            }
        };
    }

    private SessionQuality selectedQuality() {
        SessionQuality.Resolution resolution = resolutionBox.getValue();
        Integer fps = fpsBox.getValue();
        Integer bitrate = bitrateBox.getValue();
        if (resolution == null) resolution = SessionQuality.Resolution.P1080;
        if (fps == null) fps = 30;
        if (bitrate == null) bitrate = 8000;
        return new SessionQuality(resolution, fps, bitrate);
    }

    private void goOnline() {
        try {
            String roomPassword = roomPasswordField.getText() == null ? "" : roomPasswordField.getText();
            if (roomPassword.isBlank()) {
                status.setText("Enter the room password before going online.");
                return;
            }
            identity = identity.withDisplayName(displayNameField.getText()).withRoomPassword(roomPassword);
            identity.save();
            selfLabel.setText(formatSelf());
            config = new AppConfig(
                    URI.create(signalUrlField.getText().trim()),
                    networkCodeField.getText().trim(),
                    stunUrlField.getText().trim(),
                    true
            );
            closePeerSession(false);
            if (signalClient != null) signalClient.close();
            signalClient = new SignalClient(message -> Platform.runLater(() -> handleSignalMessage(message)));
            status.setText("Connecting to signaling server...");
            Map<String, Object> registerPayload = new LinkedHashMap<>(identity.toPayload());
            registerPayload.put("roomPassword", identity.roomPassword());
            signalClient.connect(config.signalingUrl())
                    .thenCompose(socket -> signalClient.send(new SignalMessage(
                            SignalMessage.Type.REGISTER,
                            config.roomCode(),
                            registerPayload
                    )))
                    .thenRun(() -> Platform.runLater(() -> {
                        status.setText("Online. Double-click a device to open remote control.");
                        setOnlineUi(true);
                    }))
                    .exceptionally(error -> {
                        Platform.runLater(() -> {
                            status.setText("Signaling error: " + error.getMessage());
                            setOnlineUi(false);
                        });
                        return null;
                    });
        } catch (IllegalArgumentException error) {
            status.setText("Configuration error: " + error.getMessage());
        }
    }

    private void goOffline() {
        endActiveSession(true);
        onlineDevices.clear();
        if (signalClient != null) {
            signalClient.close();
            signalClient = null;
        }
        closePeerSession(false);
        setOnlineUi(false);
        status.setText("Offline.");
    }

    private void requestControlOfSelected() {
        RemoteDevice selected = deviceList.getSelectionModel().getSelectedItem();
        if (selected == null || signalClient == null || config == null) {
            status.setText("Select an online device first.");
            return;
        }
        if (activeSession != null) {
            status.setText("A session is already active. Close the remote window first.");
            return;
        }
        negotiatedQuality = selectedQuality();
        String sessionId = UUID.randomUUID().toString();
        pendingRequestSessionId = sessionId;
        Map<String, Object> payload = baseRoute(selected.deviceId());
        payload.put("sessionId", sessionId);
        payload.put("fromDisplayName", identity.displayName());
        payload.put("quality", negotiatedQuality.toPayload());
        send(SignalMessage.Type.SESSION_REQUEST, payload);
        activeSession = new ControlSession(
                sessionId,
                selected.deviceId(),
                selected.displayName(),
                ControlSession.Role.CONTROLLER,
                ControlSession.State.REQUESTING
        );
        openRemoteWindow(selected.displayName());
        status.setText("Requesting control of " + selected.displayName() + " (" + negotiatedQuality + ")...");
    }

    private void handleSignalMessage(SignalMessage message) {
        switch (message.type()) {
            case DEVICE_LIST -> updateDeviceList(message.payload());
            case DEVICE_OFFLINE -> removeDevice(stringField(message.payload(), "deviceId"));
            case SESSION_REQUEST -> onSessionRequest(message.payload());
            case SESSION_ACCEPT -> onSessionAccept(message.payload());
            case SESSION_REJECT -> onSessionReject(message.payload());
            case SESSION_END -> onSessionEnd(message.payload());
            case OFFER, ANSWER, ICE_CANDIDATE -> {
                if (peerSession != null) peerSession.handleSignal(message);
            }
            case ERROR -> status.setText("Server error: " + message.payload().getOrDefault("message", "unknown error"));
            default -> { }
        }
    }

    private void updateDeviceList(Map<String, Object> payload) {
        String selfId = stringField(payload, "selfDeviceId");
        Object raw = payload.get("devices");
        List<RemoteDevice> next = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    RemoteDevice device = RemoteDevice.fromMap(map);
                    if (!device.deviceId().isBlank() && !device.deviceId().equals(selfId)
                            && !device.deviceId().equals(identity.deviceId())) {
                        next.add(device);
                    }
                }
            }
        }
        onlineDevices.setAll(next);
    }

    private void removeDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return;
        onlineDevices.removeIf(device -> device.deviceId().equals(deviceId));
        if (activeSession != null && deviceId.equals(activeSession.peerDeviceId())) {
            status.setText("Peer went offline. Session ended.");
            clearSessionLocally();
        }
    }

    @SuppressWarnings("unchecked")
    private void onSessionRequest(Map<String, Object> payload) {
        String fromId = stringField(payload, "fromDeviceId");
        String sessionId = stringField(payload, "sessionId");
        String fromName = stringField(payload, "fromDisplayName");
        if (fromName == null || fromName.isBlank()) fromName = fromId;
        Object qualityRaw = payload.get("quality");
        SessionQuality requestQuality = qualityRaw instanceof Map<?, ?> map
                ? SessionQuality.fromPayload((Map<String, Object>) map)
                : SessionQuality.defaults();
        if (activeSession != null) {
            Map<String, Object> reject = baseRoute(fromId);
            reject.put("sessionId", sessionId);
            reject.put("reason", "busy");
            send(SignalMessage.Type.SESSION_REJECT, reject);
            return;
        }
        negotiatedQuality = requestQuality;
        permissionGate.grant();
        ensureInputInjector();
        activeSession = new ControlSession(
                sessionId,
                fromId,
                fromName,
                ControlSession.Role.HOST,
                ControlSession.State.CONNECTING
        );
        Map<String, Object> accept = baseRoute(fromId);
        accept.put("sessionId", sessionId);
        send(SignalMessage.Type.SESSION_ACCEPT, accept);
        openPeerSession();
        status.setText("Accepted control request from " + fromName + ". Sharing screen (" + negotiatedQuality + ").");
    }

    private void onSessionAccept(Map<String, Object> payload) {
        String sessionId = stringField(payload, "sessionId");
        String fromId = stringField(payload, "fromDeviceId");
        if (activeSession == null || !activeSession.isController()) return;
        if (sessionId != null && !sessionId.equals(activeSession.sessionId())
                && !sessionId.equals(pendingRequestSessionId)) {
            return;
        }
        if (fromId != null && !fromId.equals(activeSession.peerDeviceId())) return;
        activeSession.setState(ControlSession.State.CONNECTING);
        pendingRequestSessionId = null;
        openPeerSession();
        peerSession.createOffer();
        if (remoteWindow != null) {
            remoteWindow.setStatus("Request accepted. Establishing direct connection...");
        }
        status.setText("Remote window open for " + activeSession.peerDisplayName() + ".");
    }

    private void onSessionReject(Map<String, Object> payload) {
        String sessionId = stringField(payload, "sessionId");
        if (activeSession == null) return;
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(activeSession.sessionId())) return;
        String reason = stringField(payload, "reason");
        status.setText("Control request rejected" + (reason == null || reason.isBlank() ? "." : ": " + reason));
        clearSessionLocally();
    }

    private void onSessionEnd(Map<String, Object> payload) {
        String sessionId = stringField(payload, "sessionId");
        if (activeSession == null) return;
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(activeSession.sessionId())) return;
        status.setText("Peer ended the session.");
        clearSessionLocally();
    }

    private void endActiveSession(boolean notifyPeer) {
        if (activeSession != null && notifyPeer && signalClient != null) {
            Map<String, Object> payload = baseRoute(activeSession.peerDeviceId());
            payload.put("sessionId", activeSession.sessionId());
            send(SignalMessage.Type.SESSION_END, payload);
        }
        clearSessionLocally();
        if (signalClient != null) {
            status.setText("Session ended. Still online.");
        }
    }

    private void clearSessionLocally() {
        closePeerSession(true);
        permissionGate.revoke();
        activeSession = null;
        pendingRequestSessionId = null;
    }

    private void openRemoteWindow(String peerName) {
        if (remoteWindow != null) {
            remoteWindow.closeQuietly();
            remoteWindow = null;
        }
        remoteWindow = new RemoteSessionWindow(peerName, () -> Platform.runLater(() -> endActiveSession(true)));
        remoteWindow.show();
        remoteWindow.setStatus("Waiting for peer to accept...");
    }

    private void openPeerSession() {
        closePeerSession(false);
        if (config == null || activeSession == null) return;
        DirectPeerPolicy.IcePlan icePlan = DirectPeerPolicy.createIcePlan(config);
        String peerId = activeSession.peerDeviceId();
        ControlSession.Role role = activeSession.role();
        peerSession = new DirectPeerSession(
                icePlan,
                role,
                negotiatedQuality,
                message -> {
                    Map<String, Object> payload = new LinkedHashMap<>(message.payload());
                    payload.put("toDeviceId", peerId);
                    if (activeSession != null) {
                        payload.put("sessionId", activeSession.sessionId());
                    }
                    send(message.type(), payload);
                },
                text -> Platform.runLater(() -> {
                    status.setText(text);
                    if (remoteWindow != null) {
                        remoteWindow.setStatus(text);
                    }
                    if (activeSession != null && text.contains("Direct P2P connection established")) {
                        activeSession.setState(ControlSession.State.CONNECTED);
                    }
                }),
                stats -> Platform.runLater(() -> {
                    if (remoteWindow != null) {
                        remoteWindow.setStats(stats);
                    }
                }),
                track -> Platform.runLater(() -> {
                    if (remoteWindow != null) {
                        remoteWindow.attachTrack(track);
                    }
                }),
                event -> {
                    if (role == ControlSession.Role.HOST) {
                        ensureInputInjector();
                        inputInjector.inject(event);
                    }
                }
        );
        if (remoteWindow != null && role == ControlSession.Role.CONTROLLER) {
            remoteWindow.bindSession(peerSession);
        }
    }

    private void ensureInputInjector() {
        if (inputInjector == null) {
            inputInjector = new WindowsInputInjector(permissionGate);
        }
    }

    private void closePeerSession(boolean closeRemoteWindow) {
        if (peerSession != null) {
            peerSession.close();
            peerSession = null;
        }
        if (closeRemoteWindow && remoteWindow != null) {
            remoteWindow.closeQuietly();
            remoteWindow = null;
        }
    }

    private void send(SignalMessage.Type type, Map<String, Object> payload) {
        if (signalClient == null || config == null) return;
        signalClient.send(new SignalMessage(type, config.roomCode(), payload))
                .exceptionally(error -> {
                    Platform.runLater(() -> status.setText("Could not send signaling: " + error.getMessage()));
                    return null;
                });
    }

    private Map<String, Object> baseRoute(String toDeviceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toDeviceId", toDeviceId);
        payload.put("fromDeviceId", identity.deviceId());
        return payload;
    }

    private void setOnlineUi(boolean online) {
        goOnline.setDisable(online);
        goOffline.setDisable(!online);
        signalUrlField.setDisable(online);
        networkCodeField.setDisable(online);
        stunUrlField.setDisable(online);
        displayNameField.setDisable(online);
        roomPasswordField.setDisable(online);
        boolean lockQuality = online && activeSession != null;
        resolutionBox.setDisable(lockQuality);
        fpsBox.setDisable(lockQuality);
        bitrateBox.setDisable(lockQuality);
    }

    private String formatSelf() {
        return identity.displayName() + " · " + identity.platform() + " · " + shortId(identity.deviceId());
    }

    private static String shortId(String id) {
        if (id == null || id.length() < 8) return id == null ? "" : id;
        return id.substring(0, 8);
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
