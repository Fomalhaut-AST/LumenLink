package io.lumenlink.ui;

import dev.onvoid.webrtc.media.video.VideoTrack;
import io.lumenlink.control.RemoteControlEvent;
import io.lumenlink.media.VideoFrameView;
import io.lumenlink.session.SessionStats;
import io.lumenlink.webrtc.DirectPeerSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Standalone remote-control viewer. Closing the window ends the session. */
public final class RemoteSessionWindow {
    private final Stage stage = new Stage();
    private final Label status = new Label("Connecting...");
    private final Label stats = new Label(SessionStats.empty().summary());
    private final ImageView remoteView = new ImageView();
    private final StackPane viewer = new StackPane();
    private final Label viewerHint = new Label("Waiting for remote screen...");
    private final VideoFrameView videoFrameView = new VideoFrameView(remoteView);
    private final Runnable onClose;
    private DirectPeerSession peerSession;
    private boolean connected;
    private boolean closing;

    public RemoteSessionWindow(String peerName, Runnable onClose) {
        this.onClose = onClose == null ? () -> { } : onClose;

        viewerHint.setStyle("-fx-text-fill: #9aa3ad;");
        remoteView.setPreserveRatio(true);
        remoteView.setSmooth(true);
        remoteView.fitWidthProperty().bind(viewer.widthProperty().subtract(4));
        remoteView.fitHeightProperty().bind(viewer.heightProperty().subtract(4));
        viewer.getChildren().setAll(viewerHint, remoteView);
        StackPane.setAlignment(viewerHint, Pos.CENTER);
        viewer.setStyle("-fx-background-color: #0e1216;");
        viewer.setFocusTraversable(true);
        viewer.setMinSize(800, 500);
        installInputHandlers();

        status.setPadding(new Insets(8, 10, 8, 10));
        status.setWrapText(true);
        status.setStyle("-fx-background-color: #1a1f24; -fx-text-fill: #d7dde5;");
        stats.setPadding(new Insets(6, 10, 6, 10));
        stats.setWrapText(true);
        stats.setStyle("-fx-background-color: #101418; -fx-text-fill: #aeb7c2;");

        BorderPane root = new BorderPane(viewer);
        root.setBottom(new VBox(status, stats));
        stage.setTitle("Remote — " + peerName);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(new Scene(root, 1280, 800));
        stage.setOnCloseRequest(event -> closeSession());
    }

    public void show() {
        stage.show();
        stage.toFront();
        viewer.requestFocus();
    }

    public void bindSession(DirectPeerSession session) {
        this.peerSession = session;
    }

    public void attachTrack(VideoTrack track) {
        videoFrameView.attach(track);
        viewerHint.setVisible(false);
        setStatus("Remote screen connected. Close this window to end the session.");
        viewer.requestFocus();
    }

    public void setStatus(String text) {
        status.setText(text == null ? "" : text);
        if (text != null && text.contains("Direct P2P connection established")) {
            connected = true;
        }
    }

    public void setStats(SessionStats next) {
        stats.setText(next == null ? SessionStats.empty().summary() : next.summary());
    }

    public void closeQuietly() {
        closing = true;
        disposeUi();
        if (stage.isShowing()) {
            stage.close();
        }
    }

    private void closeSession() {
        if (closing) return;
        closing = true;
        disposeUi();
        onClose.run();
    }

    private void disposeUi() {
        videoFrameView.close();
        peerSession = null;
        connected = false;
    }

    private void installInputHandlers() {
        viewer.addEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMove);
        viewer.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseMove);
        viewer.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            onMouseButton(event, RemoteControlEvent.Action.PRESS);
            viewer.requestFocus();
        });
        viewer.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> onMouseButton(event, RemoteControlEvent.Action.RELEASE));
        viewer.addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        viewer.addEventFilter(KeyEvent.KEY_PRESSED, event -> onKey(event, RemoteControlEvent.Action.PRESS));
        viewer.addEventFilter(KeyEvent.KEY_RELEASED, event -> onKey(event, RemoteControlEvent.Action.RELEASE));
    }

    private void onMouseMove(MouseEvent event) {
        if (!canControl()) return;
        double[] xy = normalizedPointer(event.getX(), event.getY());
        if (xy == null) return;
        peerSession.sendControlEvent(RemoteControlEvent.mouseMove(xy[0], xy[1]));
        event.consume();
    }

    private void onMouseButton(MouseEvent event, RemoteControlEvent.Action action) {
        if (!canControl()) return;
        double[] xy = normalizedPointer(event.getX(), event.getY());
        if (xy == null) return;
        peerSession.sendControlEvent(RemoteControlEvent.mouseButton(action, xy[0], xy[1], mouseButtonName(event.getButton())));
        event.consume();
    }

    private void onScroll(ScrollEvent event) {
        if (!canControl()) return;
        double[] xy = normalizedPointer(event.getX(), event.getY());
        if (xy == null) return;
        double delta = event.getDeltaY() / 40.0;
        if (delta == 0) return;
        peerSession.sendControlEvent(RemoteControlEvent.mouseScroll(xy[0], xy[1], delta));
        event.consume();
    }

    private void onKey(KeyEvent event, RemoteControlEvent.Action action) {
        if (!canControl()) return;
        String key = mapKey(event);
        if (key == null) return;
        peerSession.sendControlEvent(RemoteControlEvent.key(action, key));
        event.consume();
    }

    private boolean canControl() {
        return connected && peerSession != null && !closing;
    }

    private double[] normalizedPointer(double localX, double localY) {
        double viewW = remoteView.getBoundsInParent().getWidth();
        double viewH = remoteView.getBoundsInParent().getHeight();
        if (viewW <= 1 || viewH <= 1) {
            viewW = viewer.getWidth();
            viewH = viewer.getHeight();
        }
        if (viewW <= 1 || viewH <= 1) return null;
        double offsetX = (viewer.getWidth() - viewW) / 2.0;
        double offsetY = (viewer.getHeight() - viewH) / 2.0;
        double x = (localX - offsetX) / viewW;
        double y = (localY - offsetY) / viewH;
        if (x < 0 || x > 1 || y < 0 || y > 1) return null;
        return new double[] {x, y};
    }

    private static String mouseButtonName(MouseButton button) {
        if (button == MouseButton.SECONDARY) return "RIGHT";
        if (button == MouseButton.MIDDLE) return "MIDDLE";
        return "LEFT";
    }

    private static String mapKey(KeyEvent event) {
        return switch (event.getCode()) {
            case ENTER -> "ENTER";
            case TAB -> "TAB";
            case ESCAPE -> "ESCAPE";
            case BACK_SPACE -> "BACK_SPACE";
            case DELETE -> "DELETE";
            case SPACE -> "SPACE";
            case UP -> "UP";
            case DOWN -> "DOWN";
            case LEFT -> "LEFT";
            case RIGHT -> "RIGHT";
            case HOME -> "HOME";
            case END -> "END";
            case PAGE_UP -> "PAGE_UP";
            case PAGE_DOWN -> "PAGE_DOWN";
            case SHIFT -> "SHIFT";
            case CONTROL -> "CONTROL";
            case ALT -> "ALT";
            case WINDOWS -> "WINDOWS";
            case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 -> event.getCode().getName().toUpperCase();
            default -> {
                String text = event.getText();
                if (text != null && text.length() == 1) yield text.toUpperCase();
                String name = event.getCode().getName();
                yield name == null || name.isBlank() ? null : name.toUpperCase();
            }
        };
    }
}
