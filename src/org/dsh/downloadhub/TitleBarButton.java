/*
 * 下载中心插件 —— 标题栏按钮
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.ui.Controllers;

/// Puts a download button in the title bar, left of the minimise button.
///
/// <p>Downloads run in the background, so the user needs a way to reach them from anywhere. Rather
/// than navigating on every start — which yanks the window away from whatever the user was doing —
/// the button shows a red dot that slides in and fades away, in the style of the launcher's own
/// hints.</p>
final class TitleBarButton {

    /// Style class used to recognise the injected button.
    private static final String STYLE = "download-hub-button";

    /// Style class of the dot overlay.
    private static final String DOT_STYLE = "download-hub-dot";

    private final PluginContext context;
    private final Runnable onClick;

    /// The dot currently shown, or `null`.
    private Node dot;

    /// Whether an unseen download is waiting.
    ///
    /// <p>Cleared when the page is opened, so opening the hub does not leave the dot behind.</p>
    private boolean pending;

    TitleBarButton(PluginContext context, Runnable onClick) {
        this.context = context;
        this.onClick = onClick;
    }

    /// Adds the button, or refreshes its tooltip when it is already present.
    ///
    /// @param badgeText text shown as a tooltip, or `null` for the idle state
    void refresh(String badgeText) {
        Platform.runLater(() -> {
            try {
                if (!(findWindowButtons() instanceof HBox buttons))
                    return;

                Node existing = null;
                for (Node child : buttons.getChildren()) {
                    if (child.getStyleClass().contains(STYLE)) {
                        existing = child;
                        break;
                    }
                }

                if (existing != null) {
                    updateTooltip(existing, badgeText);
                    return;
                }

                // Index 0 puts it immediately left of the minimise button.
                buttons.getChildren().add(0, createButton(badgeText));
            } catch (Throwable e) {
                context.logWarning("无法添加下载按钮：" + e);
            }
        });
    }

    /// Marks a new download as unseen and plays the dot animation.
    ///
    /// <p>Repeating the call while a dot is already showing does nothing, so a batch of a hundred
    /// files does not retrigger the animation a hundred times.</p>
    void showDot() {
        Platform.runLater(() -> {
            if (pending)
                return;
            pending = true;
            animateDotIn();
        });
    }

    /// Clears the unseen marker, used when the download page is opened.
    void clearDot() {
        Platform.runLater(() -> {
            pending = false;
            Node current = dot;
            dot = null;
            if (current != null && current.getParent() instanceof StackPane parent)
                parent.getChildren().remove(current);
        });
    }

    /// Enables or disables the title bar entry.
    ///
    /// <p>Used while a game is launching: the launch takes over the window, so letting the user
    /// open the download page at that moment only gets in the way.</p>
    ///
    /// @param enabled whether the entry should respond to clicks
    void setEnabled(boolean enabled) {
        Platform.runLater(() -> {
            try {
                Node wrapper = findButton();
                if (wrapper == null)
                    return;

                // The wrapper is the click target, so the disabled look goes on it and the whole
                // button greys out.
                wrapper.setDisable(!enabled);
                wrapper.setOpacity(enabled ? 1.0 : 0.45);
            } catch (Throwable e) {
                context.logWarning("无法切换下载按钮状态：" + e);
            }
        });
    }

    /// Animates the dot sliding in from the launcher's edge and fading out.
    ///
    /// <p>The dot exists only for the duration of the animation: leaving it in the title bar
    /// permanently would look like a stuck notification.</p>
    private void animateDotIn() {
        animateDotIn(0);
    }

    /// Animates the dot, retrying a few times while the button is still being installed.
    ///
    /// @param attempt how many retries have already happened
    private void animateDotIn(int attempt) {
        try {
            Node button = findButton();
            if (!(button instanceof StackPane container)) {
                // The title bar may not have been built yet. Retry briefly, but give up rather than
                // rescheduling forever if the button never appears.
                if (attempt < 10)
                    Platform.runLater(() -> animateDotIn(attempt + 1));
                return;
            }

            if (dot != null)
                return;

            Region circle = new Region();
            circle.getStyleClass().add(DOT_STYLE);
            circle.setMouseTransparent(true);

            // 6 px was easy to miss, especially on a dark title bar where red sits close to the
            // background. 11 px with a light ring reads as a notification at a glance.
            double size = 11;
            circle.setPrefSize(size, size);
            circle.setMinSize(size, size);
            circle.setMaxSize(size, size);

            StackPane.setAlignment(circle, Pos.TOP_RIGHT);
            circle.setTranslateX(-3);
            circle.setTranslateY(3);
            circle.setOpacity(0);

            dot = circle;
            container.getChildren().add(circle);

            // Slide in, pulse once to draw the eye, hold long enough to notice, then fade out.
            TranslateTransition slide = new TranslateTransition(Duration.millis(280), circle);
            slide.setFromX(-16);
            slide.setToX(-3);
            slide.setInterpolator(Interpolator.EASE_OUT);

            FadeTransition appear = new FadeTransition(Duration.millis(160), circle);
            appear.setFromValue(0);
            appear.setToValue(1);

            ScaleTransition pulse = new ScaleTransition(Duration.millis(420), circle);
            pulse.setFromX(0.7);
            pulse.setFromY(0.7);
            pulse.setToX(1.0);
            pulse.setToY(1.0);
            pulse.setInterpolator(Interpolator.EASE_BOTH);

            FadeTransition vanish = new FadeTransition(Duration.millis(700), circle);
            vanish.setFromValue(1);
            vanish.setToValue(0);
            vanish.setDelay(Duration.millis(1600));

            SequentialTransition sequence = new SequentialTransition(appear, pulse, vanish);
            sequence.setOnFinished(event -> {
                if (dot == circle && container.getChildren().contains(circle))
                    container.getChildren().remove(circle);
                if (dot == circle)
                    dot = null;
            });

            slide.play();
            sequence.play();
        } catch (Throwable e) {
            context.logWarning("无法播放下载提示动画：" + e);
        }
    }

    /// Finds the injected button.
    ///
    /// @return the button, or `null` when it has not been added yet
    private Node findButton() {
        if (!(findWindowButtons() instanceof HBox buttons))
            return null;
        for (Node child : buttons.getChildren()) {
            if (child.getStyleClass().contains(STYLE))
                return child;
        }
        return null;
    }

    /// Replaces a button's tooltip.
    ///
    /// <p>The title bar holds the wrapper, while the tooltip lives on the inner button, so this
    /// looks one level down.</p>
    ///
    /// @param node the wrapper or the button
    /// @param text the new tooltip, or `null` to clear it
    private static void updateTooltip(Node node, String text) {
        if (text == null || text.isBlank())
            return;

        Node target = node;
        if (node instanceof StackPane wrapper && !wrapper.getChildren().isEmpty())
            target = wrapper.getChildren().get(0);

        try {
            org.jackhuang.hmcl.ui.FXUtils.installFastTooltip(target, text);
        } catch (Throwable ignored) {
            // A tooltip is cosmetic.
        }
    }

    /// Builds the download button.
    ///
    /// @param badgeText tooltip text, or `null`
    /// @return the button
    private Node createButton(String badgeText) {
        com.jfoenix.controls.JFXButton button = new com.jfoenix.controls.JFXButton();
        button.setFocusTraversable(false);
        button.setGraphic(org.jackhuang.hmcl.ui.SVG.DOWNLOAD
                .createIcon(org.jackhuang.hmcl.theme.Themes.titleFillProperty()));
        button.getStyleClass().add("jfx-decorator-button");
        org.jackhuang.hmcl.ui.FXUtils.installFastTooltip(button,
                badgeText == null || badgeText.isBlank() ? "下载中心" : badgeText);
        button.setOnAction(event -> onClick.run());

        // The dot is positioned relative to the button, so it needs a StackPane.
        //
        // The marker style goes on the container, not the inner button: the container is what gets
        // added to the title bar, so a marker on the button was invisible to the duplicate check.
        // That made every refresh insert another copy, and the buttons multiplied.
        StackPane container = new StackPane(button);
        container.getStyleClass().addAll("download-hub-button-container", STYLE);
        container.setPickOnBounds(false);
        return container;
    }

    /// Finds the title bar's window-button container.
    ///
    /// @return the container, or `null`
    private static Node findWindowButtons() {
        var scene = Controllers.getScene();
        if (scene == null)
            return null;
        return findButtonsIn(scene.getRoot());
    }

    /// Searches a subtree for the window-button row.
    ///
    /// <p>Identified by structure: every child carries the decorator button style and the row sits
    /// inside an `AnchorPane`, which distinguishes it from the navigation bar.</p>
    ///
    /// @param root the node to search
    /// @return the container, or `null`
    private static Node findButtonsIn(Node root) {
        if (root instanceof HBox box && isWindowButtonRow(box))
            return box;

        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findButtonsIn(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /// Returns whether a row is the window-control group.
    ///
    /// @param box the row
    /// @return whether it holds the title bar's window buttons
    private static boolean isWindowButtonRow(HBox box) {
        if (box.getChildren().size() < 2)
            return false;

        for (Node child : box.getChildren()) {
            // Skip the wrappers plugin buttons themselves bring, which are not decorator buttons.
            // The plugin store's restart button sits to the left of this one, so both sides have to
            // tolerate each other's wrapper or the row stops being recognised once either is added.
            if (child.getStyleClass().contains("download-hub-button-container")
                    || child.getStyleClass().contains("store-restart-button-container"))
                continue;
            if (!child.getStyleClass().contains("jfx-decorator-button"))
                return false;
        }

        return box.getParent() instanceof javafx.scene.layout.AnchorPane;
    }
}
