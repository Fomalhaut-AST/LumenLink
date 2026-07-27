package io.lumenlink.ui;

import io.lumenlink.auth.AccountClient;
import io.lumenlink.auth.WindowsTokenStore;
import io.lumenlink.config.AppConfig;
import io.lumenlink.control.ControlPermissionGate;
import io.lumenlink.control.WindowsInputInjector;
import io.lumenlink.device.DeviceIdentity;
import io.lumenlink.device.RemoteDevice;
import io.lumenlink.network.DirectPeerPolicy;
import io.lumenlink.network.SignalClient;
import io.lumenlink.network.SignalMessage;
import io.lumenlink.logging.SafeLog;
import io.lumenlink.session.ControlSession;
import io.lumenlink.session.SessionQuality;
import io.lumenlink.webrtc.DirectPeerSession;
import io.lumenlink.windows.SecureDesktopBridge;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
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
import javafx.animation.PauseTransition;
import javafx.util.Duration;

public final class MainWindow {
    private final Stage stage;
    private final Label status = new Label("Go online, then double-click a device to open remote control.");
    private final Label selfLabel = new Label();
    private final ObservableList<RemoteDevice> onlineDevices = FXCollections.observableArrayList();
    private final ListView<RemoteDevice> deviceList = new ListView<>(onlineDevices);
    private final WindowsTokenStore tokenStore = new WindowsTokenStore();
    private final PauseTransition reconnectTimer = new PauseTransition(Duration.seconds(5));
    private final SecureDesktopBridge secureDesktop = new SecureDesktopBridge();

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
    private String accountToken;
    private boolean desiredOnline;
    private boolean connecting;

    private Button goOnline;
    private Button registerAccount;
    private Button logOut;
    private Button goOffline;
    private Button accountSettings;
    private TextField signalUrlField;
    private TextField stunUrlField;
    private TextField displayNameField;
    private TextField usernameField;
    private PasswordField passwordField;
    private ComboBox<SessionQuality.Resolution> resolutionBox;
    private ComboBox<SessionQuality.PerformancePreset> performanceBox;
    private ComboBox<Integer> fpsBox;
    private ComboBox<Integer> bitrateBox;
    private boolean applyingPerformancePreset;

    public MainWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        AppConfig defaults = AppConfig.defaults();
        signalUrlField = new TextField(defaults.signalingUrl().toString());
        stunUrlField = new TextField(defaults.stunUrl());
        displayNameField = new TextField(identity.displayName());
        usernameField = new TextField();
        passwordField = new PasswordField();
        performanceBox = new ComboBox<>(FXCollections.observableArrayList(SessionQuality.PerformancePreset.values()));
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
        performanceBox.setOnAction(event -> applyPerformancePreset(performanceBox.getValue()));
        resolutionBox.setOnAction(event -> markCustomPerformance());
        fpsBox.setOnAction(event -> markCustomPerformance());
        bitrateBox.setOnAction(event -> markCustomPerformance());
        performanceBox.getSelectionModel().select(SessionQuality.PerformancePreset.BALANCED);

        selfLabel.setText(formatSelf());
        selfLabel.setWrapText(true);
        signalUrlField.setPrefColumnCount(28);
        stunUrlField.setPrefColumnCount(28);
        displayNameField.setPrefColumnCount(28);
        usernameField.setPrefColumnCount(28);
        passwordField.setPrefColumnCount(28);

        GridPane settings = new GridPane();
        settings.setHgap(10);
        settings.setVgap(8);
        settings.addRow(0, new Label("Signaling URL"), signalUrlField);
        settings.addRow(1, new Label("STUN URL"), stunUrlField);
        settings.addRow(2, new Label("Display name"), displayNameField);
        settings.addRow(3, new Label("Username"), usernameField);
        settings.addRow(4, new Label("Password"), passwordField);
        settings.addRow(5, new Label("Performance"), performanceBox);
        settings.addRow(6, new Label("Resolution"), resolutionBox);
        settings.addRow(7, new Label("Frame rate"), fpsBox);
        settings.addRow(8, new Label("Max bitrate"), bitrateBox);
        settings.addRow(9, new Label("This device"), selfLabel);

        goOnline = new Button("Log in");
        registerAccount = new Button("Create account");
        logOut = new Button("Log out");
        goOffline = new Button("Go offline");
        accountSettings = new Button("Account");
        logOut.setDisable(true);
        accountSettings.setDisable(true);
        goOffline.setDisable(true);
        goOnline.setOnAction(event -> authenticate(false));
        registerAccount.setOnAction(event -> authenticate(true));
        logOut.setOnAction(event -> logOut());
        accountSettings.setOnAction(event -> openAccountManagement());
        goOffline.setOnAction(event -> goOffline());
        HBox connectionActions = new HBox(8, goOnline, registerAccount, goOffline, accountSettings, logOut);

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
        stage.setOnCloseRequest(event -> {
            goOffline();
            secureDesktop.close();
        });
        stage.show();
        reconnectTimer.setOnFinished(event -> {
            if (desiredOnline && accountToken != null && !connecting) connectWithToken(accountToken);
        });
        tokenStore.load().ifPresent(token -> Platform.runLater(() -> connectWithToken(token)));
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

    private void applyPerformancePreset(SessionQuality.PerformancePreset preset) {
        if (preset == null || preset.quality() == null) return;
        applyingPerformancePreset = true;
        try {
            SessionQuality quality = preset.quality();
            resolutionBox.getSelectionModel().select(quality.resolution());
            fpsBox.getSelectionModel().select(Integer.valueOf(quality.fps()));
            bitrateBox.getSelectionModel().select(Integer.valueOf(quality.maxBitrateKbps()));
        } finally {
            applyingPerformancePreset = false;
        }
    }

    private void markCustomPerformance() {
        if (!applyingPerformancePreset && performanceBox != null) {
            performanceBox.getSelectionModel().select(SessionQuality.PerformancePreset.CUSTOM);
        }
    }

    private void authenticate(boolean registration) {
        try {
            String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
            String password = passwordField.getText() == null ? "" : passwordField.getText();
            if (username.isBlank() || password.isBlank()) {
                status.setText("Enter a username and password.");
                return;
            }
            config = readConfig();
            identity = identity.withDisplayName(displayNameField.getText());
            identity.save();
            selfLabel.setText(formatSelf());
            status.setText(registration ? "Creating account..." : "Logging in...");
            SafeLog.info(registration ? "account.register.requested" : "account.login.requested");
            AccountClient accounts = new AccountClient(config.signalingUrl());
            var request = registration
                    ? accounts.register(username, password, identity)
                    : accounts.login(username, password, identity);
            setAuthenticationUiDisabled(true);
            request.thenAccept(token -> Platform.runLater(() -> {
                        try {
                            passwordField.clear();
                            tokenStore.save(token);
                            SafeLog.info(registration ? "account.register.succeeded" : "account.login.succeeded");
                            connectWithToken(token);
                        } catch (RuntimeException error) {
                            SafeLog.warn("account.token.save_failed", error);
                            status.setText("Could not protect login token: " + errorMessage(error));
                            setAuthenticationUiDisabled(false);
                        }
                    }))
                    .exceptionally(error -> {
                        SafeLog.warn(registration ? "account.register.failed" : "account.login.failed", error);
                        Platform.runLater(() -> {
                            status.setText("Account error: " + errorMessage(error));
                            setAuthenticationUiDisabled(false);
                        });
                        return null;
                    });
        } catch (IllegalArgumentException error) {
            status.setText("Configuration error: " + error.getMessage());
        }
    }

    private void connectWithToken(String token) {
        if (connecting) return;
        try {
            desiredOnline = true;
            connecting = true;
            config = readConfig();
            identity = identity.withDisplayName(displayNameField.getText());
            identity.save();
            selfLabel.setText(formatSelf());
            accountToken = token;
            closePeerSession(false);
            if (signalClient != null) {
                SignalClient previous = signalClient;
                signalClient = null;
                previous.close();
            }
            signalClient = new SignalClient(
                    message -> Platform.runLater(() -> handleSignalMessage(message)),
                    error -> Platform.runLater(() -> onSignalingDisconnected(error)));
            status.setText("Connecting to signaling server...");
            SafeLog.info("signaling.connecting");
            signalClient.connect(config.signalingUrl(), token)
                    .thenCompose(socket -> signalClient.send(new SignalMessage(
                            SignalMessage.Type.REGISTER, "", identity.toPayload())))
                    .thenRun(() -> Platform.runLater(() -> {
                        connecting = false;
                        reconnectTimer.stop();
                        SafeLog.info("signaling.connected");
                        status.setText("Online. Double-click a device to open remote control.");
                        setOnlineUi(true);
                    }))
                    .exceptionally(error -> {
                        SafeLog.warn("signaling.connect_failed", error);
                        Platform.runLater(() -> {
                            connecting = false;
                            signalClient = null;
                            status.setText("Signaling unavailable. Retrying in 5 seconds.");
                            setOnlineUi(false);
                            scheduleReconnect();
                        });
                        return null;
                    });
        } catch (IllegalArgumentException error) {
            connecting = false;
            desiredOnline = false;
            status.setText("Configuration error: " + error.getMessage());
            setAuthenticationUiDisabled(false);
        }
    }

    private AppConfig readConfig() {
        return new AppConfig(URI.create(signalUrlField.getText().trim()), stunUrlField.getText().trim(), true);
    }

    private void goOffline() {
        desiredOnline = false;
        connecting = false;
        reconnectTimer.stop();
        endActiveSession(true);
        onlineDevices.clear();
        if (signalClient != null) {
            signalClient.close();
            signalClient = null;
        }
        closePeerSession(false);
        setOnlineUi(false);
        status.setText("Offline.");
        SafeLog.info("signaling.offline_requested");
    }

    private void onSignalingDisconnected(Throwable error) {
        if (!desiredOnline) return;
        connecting = false;
        signalClient = null;
        onlineDevices.clear();
        clearSessionLocally();
        setOnlineUi(false);
        status.setText("Signaling disconnected. Retrying in 5 seconds.");
        SafeLog.warn("signaling.disconnected", error);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!desiredOnline || accountToken == null) return;
        reconnectTimer.stop();
        reconnectTimer.playFromStart();
    }

    private void logOut() {
        String token = accountToken;
        AppConfig currentConfig = config;
        goOffline();
        accountToken = null;
        tokenStore.clear();
        usernameField.clear();
        passwordField.clear();
        logOut.setDisable(true);
        status.setText("Logged out. This device no longer stores an account token.");
        SafeLog.info("account.logout");
        if (token != null && currentConfig != null) {
            new AccountClient(currentConfig.signalingUrl()).logout(token);
        }
    }

    private void openAccountManagement() {
        if (accountToken == null || config == null) return;
        new AccountManagementWindow(stage, config.signalingUrl(), accountToken, this::invalidateLocalCredentials).show();
    }

    private void invalidateLocalCredentials() {
        goOffline();
        accountToken = null;
        tokenStore.clear();
        setOnlineUi(false);
        status.setText("Account access was removed from this device.");
        SafeLog.info("account.access_removed");
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
            case ERROR -> {
                String detail = String.valueOf(message.payload().getOrDefault("message", "unknown error"));
                status.setText("Server error: " + detail);
                if (detail.toLowerCase().contains("authentication")) {
                    desiredOnline = false;
                    reconnectTimer.stop();
                    tokenStore.clear();
                    accountToken = null;
                    setOnlineUi(false);
                }
            }
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
        remoteWindow = new RemoteSessionWindow(peerName, negotiatedQuality,
                () -> Platform.runLater(() -> endActiveSession(true)));
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
                },
                secureDesktop
        );
        if (remoteWindow != null && role == ControlSession.Role.CONTROLLER) {
            remoteWindow.bindSession(peerSession);
        }
    }

    private void ensureInputInjector() {
        if (inputInjector == null) {
            inputInjector = new WindowsInputInjector(permissionGate, secureDesktop);
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
        signalClient.send(new SignalMessage(type, "", payload))
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
        registerAccount.setDisable(online);
        goOffline.setDisable(!online);
        logOut.setDisable(accountToken == null);
        accountSettings.setDisable(accountToken == null);
        signalUrlField.setDisable(online);
        stunUrlField.setDisable(online);
        displayNameField.setDisable(online);
        usernameField.setDisable(online);
        passwordField.setDisable(online);
        boolean lockQuality = online && activeSession != null;
        resolutionBox.setDisable(lockQuality);
        fpsBox.setDisable(lockQuality);
        bitrateBox.setDisable(lockQuality);
        performanceBox.setDisable(lockQuality);
    }

    private void setAuthenticationUiDisabled(boolean disabled) {
        goOnline.setDisable(disabled);
        registerAccount.setDisable(disabled);
        signalUrlField.setDisable(disabled);
        stunUrlField.setDisable(disabled);
        displayNameField.setDisable(disabled);
        usernameField.setDisable(disabled);
        passwordField.setDisable(disabled);
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current.getMessage() == null) && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
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
