package io.lumenlink.ui;

import io.lumenlink.auth.AccountClient;
import io.lumenlink.logging.SafeLog;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

public final class AccountManagementWindow {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Stage stage = new Stage();
    private final AccountClient accounts;
    private final String token;
    private final Runnable credentialsInvalidated;
    private final ObservableList<AccountClient.AccountDevice> devices = FXCollections.observableArrayList();
    private final ListView<AccountClient.AccountDevice> deviceList = new ListView<>(devices);
    private final Label status = new Label();
    private final PasswordField currentPassword = new PasswordField();
    private final PasswordField newPassword = new PasswordField();
    private final PasswordField confirmPassword = new PasswordField();
    private final PasswordField deletePassword = new PasswordField();
    private final Button refresh = new Button("Refresh");
    private final Button revoke = new Button("Revoke device");
    private final Button changePassword = new Button("Change password");
    private final Button deleteAccount = new Button("Delete account");

    public AccountManagementWindow(Stage owner, URI signalingUrl, String token, Runnable credentialsInvalidated) {
        this.accounts = new AccountClient(signalingUrl);
        this.token = token;
        this.credentialsInvalidated = credentialsInvalidated;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        buildUi();
    }

    public void show() {
        stage.show();
        stage.toFront();
        refreshDevices();
    }

    private void buildUi() {
        deviceList.setPrefHeight(240);
        deviceList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(AccountClient.AccountDevice device, boolean empty) {
                super.updateItem(device, empty);
                if (empty || device == null) {
                    setText(null);
                    return;
                }
                String state = device.online() ? "Online" : device.loggedIn() ? "Logged in" : "Signed out";
                String current = device.current() ? " | This device" : "";
                setText(device.displayName() + " | " + device.platform() + " | " + state + current
                        + " | Last seen " + TIME_FORMAT.format(Instant.ofEpochSecond(device.lastSeenAt())));
            }
        });

        refresh.setOnAction(event -> refreshDevices());
        revoke.setOnAction(event -> revokeSelectedDevice());
        HBox deviceActions = new HBox(8, refresh, revoke);

        GridPane passwordGrid = new GridPane();
        passwordGrid.setHgap(10);
        passwordGrid.setVgap(8);
        passwordGrid.addRow(0, new Label("Current password"), currentPassword);
        passwordGrid.addRow(1, new Label("New password"), newPassword);
        passwordGrid.addRow(2, new Label("Confirm password"), confirmPassword);
        passwordGrid.add(changePassword, 1, 3);
        changePassword.setOnAction(event -> changePassword());

        GridPane deleteGrid = new GridPane();
        deleteGrid.setHgap(10);
        deleteGrid.setVgap(8);
        deleteGrid.addRow(0, new Label("Current password"), deletePassword);
        deleteGrid.add(deleteAccount, 1, 1);
        deleteAccount.setOnAction(event -> deleteAccount());
        deleteAccount.setStyle("-fx-text-fill: #b91c1c;");

        status.setWrapText(true);
        VBox root = new VBox(12,
                new Label("Logged-in devices"), deviceList, deviceActions,
                new Label("Change password"), passwordGrid,
                new Label("Delete account"), deleteGrid, status);
        root.setPadding(new Insets(14));
        VBox.setVgrow(deviceList, Priority.ALWAYS);
        stage.setTitle("Account management");
        stage.setMinWidth(720);
        stage.setMinHeight(620);
        stage.setScene(new Scene(root, 780, 660));
    }

    private void refreshDevices() {
        setBusy(true, "Loading devices...");
        accounts.devices(token).thenAccept(result -> Platform.runLater(() -> {
            SafeLog.info("account.devices.loaded");
            devices.setAll(result);
            setBusy(false, "");
        })).exceptionally(error -> {
            SafeLog.warn("account.devices.load_failed", error);
            Platform.runLater(() -> setBusy(false, "Could not load devices: " + errorMessage(error)));
            return null;
        });
    }

    private void revokeSelectedDevice() {
        AccountClient.AccountDevice selected = deviceList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select a device first.");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Revoke account access for " + selected.displayName() + "?", ButtonType.CANCEL, ButtonType.OK);
        confirmation.initOwner(stage);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        setBusy(true, "Revoking device...");
        accounts.revokeDevice(token, selected.deviceId()).thenRun(() -> Platform.runLater(() -> {
            SafeLog.info("account.device.revoked");
            if (selected.current()) {
                stage.close();
                credentialsInvalidated.run();
            } else {
                refreshDevices();
            }
        })).exceptionally(error -> {
            SafeLog.warn("account.device.revoke_failed", error);
            Platform.runLater(() -> setBusy(false, "Could not revoke device: " + errorMessage(error)));
            return null;
        });
    }

    private void changePassword() {
        String current = currentPassword.getText();
        String replacement = newPassword.getText();
        if (replacement == null || !replacement.equals(confirmPassword.getText())) {
            status.setText("New passwords do not match.");
            return;
        }
        setBusy(true, "Changing password...");
        accounts.changePassword(token, current, replacement).thenRun(() -> Platform.runLater(() -> {
            SafeLog.info("account.password.changed");
            currentPassword.clear();
            newPassword.clear();
            confirmPassword.clear();
            setBusy(false, "Password changed. Other devices were signed out.");
            refreshDevices();
        })).exceptionally(error -> {
            SafeLog.warn("account.password.change_failed", error);
            Platform.runLater(() -> setBusy(false, "Could not change password: " + errorMessage(error)));
            return null;
        });
    }

    private void deleteAccount() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete this account and revoke every device?", ButtonType.CANCEL, ButtonType.OK);
        confirmation.initOwner(stage);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        setBusy(true, "Deleting account...");
        accounts.deleteAccount(token, deletePassword.getText()).thenRun(() -> Platform.runLater(() -> {
            SafeLog.info("account.deleted");
            deletePassword.clear();
            stage.close();
            credentialsInvalidated.run();
        })).exceptionally(error -> {
            SafeLog.warn("account.delete_failed", error);
            Platform.runLater(() -> setBusy(false, "Could not delete account: " + errorMessage(error)));
            return null;
        });
    }

    private void setBusy(boolean busy, String text) {
        refresh.setDisable(busy);
        revoke.setDisable(busy);
        changePassword.setDisable(busy);
        deleteAccount.setDisable(busy);
        status.setText(text == null ? "" : text);
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
