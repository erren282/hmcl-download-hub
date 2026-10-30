/*
 * 下载中心插件 —— 下载页面
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;

import java.util.List;
import java.util.Locale;

/// The download page shown as a tab on the launcher's settings page.
///
/// <p>Lists every download the hub knows about with its progress bar and live speed. Records are
/// kept on disk by default; the header offers a clear button that removes finished entries without
/// touching whatever is still transferring.</p>
public final class DownloadPage extends VBox
        implements org.jackhuang.hmcl.ui.construct.PageAware {

    /// Whether the navigator is currently showing this page.
    ///
    /// <p>Used to avoid pushing the page twice: it is reused across visits, and a second push would
    /// leave two stack entries pointing at it, so back would appear to do nothing.</p>
    private volatile boolean shown;

    /// Returns whether the page is on screen.
    ///
    /// @return whether it is currently shown
    boolean isShown() {
        return shown;
    }

    @Override
    public void onPageShown() {
        shown = true;
        refresh();
    }

    @Override
    public void onPageHidden() {
        shown = false;
    }

    private final DownloadManager manager;
    private final VBox list = new VBox(8);
    private final Label summary = new Label();
    private final Label speedLabel = new Label();

    public DownloadPage(DownloadManager manager) {
        this.manager = manager;

        getStyleClass().add("download-page");

        // The launcher already draws the wallpaper behind the page; a translucent panel with a blur
        // reads as frosted glass instead of a flat block, matching the store page.
        Region backdrop = new Region();
        backdrop.getStyleClass().add("download-backdrop");
        backdrop.setEffect(backgroundBlur());
        backdrop.setMouseTransparent(true);

        VBox content = new VBox(10, buildHeader(), buildScroll());
        content.setPadding(new Insets(12, 16, 16, 16));

        StackPane layered = new StackPane(backdrop, content);
        layered.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        getChildren().add(layered);

        manager.addListener(this::refresh);
        refresh();
    }

    /// Builds the blur used behind the page.
    ///
    /// @return the effect
    private static javafx.scene.effect.BoxBlur backgroundBlur() {
        javafx.scene.effect.BoxBlur blur = new javafx.scene.effect.BoxBlur();
        blur.setWidth(20);
        blur.setHeight(20);
        blur.setIterations(3);
        return blur;
    }

    /// Builds the header: a title, the combined speed and the clear button.
    ///
    /// @return the header node
    private Node buildHeader() {
        Label title = new Label("下载");
        title.getStyleClass().add("download-title");

        summary.getStyleClass().add("download-summary");
        speedLabel.getStyleClass().add("download-speed");

        VBox texts = new VBox(3, title, summary);

        Node clear = XWidgets.button("清空记录", this::clearHistory);
        Node refresh = XWidgets.button("刷新", this::refresh);

        HBox actions = new HBox(8, refresh, clear);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, texts, XWidgets.spacer(), speedLabel, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /// Builds the scrollable list area.
    ///
    /// @return the scroll pane
    private Node buildScroll() {
        list.setFillWidth(true);
        list.setPadding(new Insets(4, 2, 8, 2));

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("download-scroll");
        FXUtils.smoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /// Rebuilds the list from the current records.
    private void refresh() {
        List<DownloadEntry> entries = manager.entries();

        list.getChildren().clear();
        if (entries.isEmpty()) {
            Label empty = new Label("还没有下载记录。");
            empty.getStyleClass().add("download-empty");
            list.getChildren().add(empty);
        } else {
            for (DownloadEntry entry : entries)
                list.getChildren().add(buildRow(entry));
        }

        long running = entries.stream().filter(DownloadEntry::isRunning).count();
        summary.setText(entries.isEmpty()
                ? "暂无记录"
                : "共 " + entries.size() + " 条" + (running > 0 ? "，" + running + " 个进行中" : ""));

        long speed = manager.aggregateSpeed();
        speedLabel.setText(running > 0 && speed > 0
                ? formatSpeed(speed)
                : "");
    }

    /// Builds one row: name, state, progress bar and speed.
    ///
    /// @param entry the record
    /// @return the row
    private Node buildRow(DownloadEntry entry) {
        Label name = new Label(entry.name);
        name.getStyleClass().add("download-name");

        Label state = new Label(stateText(entry));
        state.getStyleClass().addAll("download-state", stateClass(entry));

        Node cancel = null;
        if (entry.isRunning()) {
            // The row below also reacts to clicks (to reveal a finished file), so the button stops
            // the event from bubbling; consuming it in a separate handler would run first and kill
            // the action.
            cancel = XWidgets.button("取消", () -> manager.cancel(entry), true);
            cancel.getStyleClass().add("download-cancel");
        }

        HBox titleRow = cancel == null
                ? new HBox(8, name, XWidgets.spacer(), state)
                : new HBox(8, name, XWidgets.spacer(), state, cancel);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("download-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(6);
        bar.setMinHeight(6);

        double fraction = entry.fraction();
        if (entry.state == DownloadEntry.State.DONE) {
            bar.setProgress(1);
        } else if (fraction >= 0) {
            bar.setProgress(fraction);
        } else if (entry.isRunning()) {
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        } else {
            bar.setProgress(0);
        }

        Label detail = new Label(detailText(entry));
        detail.getStyleClass().add("download-detail");

        VBox row = new VBox(6, titleRow, bar, detail);
        row.getStyleClass().add("download-row");
        row.setMaxWidth(Double.MAX_VALUE);

        // Reveal the finished file in place, which is the most likely follow-up action.
        if (entry.state == DownloadEntry.State.DONE) {
            row.setOnMouseClicked(event -> {
                try {
                    java.nio.file.Path path = java.nio.file.Path.of(entry.target);
                    java.nio.file.Path parent = path.getParent();
                    if (parent != null)
                        FXUtils.openFolder(parent);
                } catch (Throwable ignored) {
                    // A missing file needs no handling.
                }
            });
        }
        return row;
    }

    /// Returns the label describing an entry's state.
    ///
    /// @param entry the record
    /// @return the text
    private static String stateText(DownloadEntry entry) {
        return switch (entry.state) {
            case RUNNING -> "进行中";
            case DONE -> "已完成";
            case CANCELLED -> "已取消";
            case FAILED -> "失败";
        };
    }

    /// Returns the style class matching an entry's state.
    ///
    /// @param entry the record
    /// @return the class name
    private static String stateClass(DownloadEntry entry) {
        return switch (entry.state) {
            case RUNNING -> "running";
            case DONE -> "done";
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
        };
    }

    /// Builds the second line of a row: file count for a batch, size and speed otherwise.
    ///
    /// @param entry the record
    /// @return the text
    private static String detailText(DownloadEntry entry) {
        if (entry.state == DownloadEntry.State.FAILED && !entry.message.isBlank())
            return entry.message;

        StringBuilder text = new StringBuilder();

        if (!entry.batch.isEmpty()) {
            // The launcher never announces how many files an install will pull, so the file count is
            // the only exact figure; the byte total is the sum of what has been fetched so far.
            if (entry.batchDone > 0) {
                text.append(entry.batchDone).append(" 个文件完成");
                if (entry.received > 0)
                    text.append("　·　").append(XWidgets.formatBytes(entry.received));
            } else if (entry.received > 0) {
                text.append(XWidgets.formatBytes(entry.received));
            } else {
                text.append("准备中…");
            }
        } else if (entry.total > 0) {
            text.append(XWidgets.formatBytes(entry.received))
                    .append(" / ")
                    .append(XWidgets.formatBytes(entry.total));
        } else if (entry.received > 0) {
            text.append(XWidgets.formatBytes(entry.received));
        }

        if (entry.isRunning() && entry.speed > 0) {
            if (text.length() > 0)
                text.append("　·　");
            text.append(formatSpeed(entry.speed));
        }

        if (text.length() == 0)
            text.append(entry.target);
        return text.toString();
    }

    /// Formats a transfer rate.
    ///
    /// @param bytesPerSecond the rate
    /// @return the formatted text
    static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond <= 0)
            return "";
        if (bytesPerSecond < 1024)
            return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024 * 1024)
            return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
    }

    /// Clears finished records after asking for confirmation.
    private void clearHistory() {
        manager.clearHistory();
        refresh();
    }
}
