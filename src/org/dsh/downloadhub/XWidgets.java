/*
 * 下载中心插件 —— 小控件
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.Locale;

/// Small helpers shared by the download page.
final class XWidgets {

    private XWidgets() {
    }

    /// Builds a flat button in the launcher's palette.
    ///
    /// @param text   the label
    /// @param action the click action
    /// @return the button
    static StackPane button(String text, Runnable action) {
        return button(text, action, false);
    }

    /// Builds a flat button, optionally stopping the click from reaching its container.
    ///
    /// <p>A handler added with `addEventHandler` runs before the one set with
    /// `setOnMouseClicked`, so consuming the event in a separate handler swallowed the click and the
    /// action never ran. Consuming inside the action handler itself is what lets a row react to
    /// clicks while a button inside it still works.</p>
    ///
    /// @param text      the label
    /// @param action    the click action
    /// @param stopEvent whether to consume the click
    /// @return the button
    static StackPane button(String text, Runnable action, boolean stopEvent) {
        Label label = new Label(text);
        label.getStyleClass().add("download-button-text");
        label.setMouseTransparent(true);

        StackPane button = new StackPane(label);
        button.getStyleClass().add("download-button");
        button.setOnMouseClicked(event -> {
            if (stopEvent)
                event.consume();
            action.run();
        });
        return button;
    }

    /// Creates a flexible spacer.
    ///
    /// @return the spacer
    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /// Formats a byte count.
    ///
    /// @param bytes the count
    /// @return the formatted text
    static String formatBytes(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
