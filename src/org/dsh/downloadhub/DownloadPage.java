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
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;

import java.util.List;
import java.util.Locale;

/// The download hub page.
///
/// <p>Built from the launcher's own widgets — [ComponentList], [LineButton] and [LineComponent] —
/// so it reads as another launcher page rather than a third-party panel: same row heights, same
/// hover and ripple behaviour, same typography and theme colours, and it follows the light/dark
/// theme automatically because nothing here paints its own palette.</p>
///
/// <p>Records are kept on disk by default; the header offers a clear button that removes finished
/// entries without touching whatever is still transferring.</p>
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
        rebuild();
    }

    @Override
    public void onPageHidden() {
        shown = false;
    }

    private final DownloadManager manager;
    private final VBox list = new VBox(0);
    private final Label summary = new Label();

    public DownloadPage(DownloadManager manager) {
        this.manager = manager;

        getStyleClass().add("download-page");
        setSpacing(0);

        // The launcher's own pages are `DecoratorAnimatedPage`s, whose constructor adds the
        // "gray-background" style class — that is where the familiar translucent surface comes
        // from, not from any paint this page does. Plugins cannot extend that class (it belongs to
        // the launcher's page machinery, not the plugin API), so the very same style class is
        // applied directly: the page then inherits the launcher's surface colour and follows the
        // light/dark theme exactly like every other page.
        getStyleClass().add("gray-background");

        VBox content = new VBox(0, buildHeader(), buildScroll());
        content.setFillWidth(true);
        content.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().add(content);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        manager.addListener(this::rebuild);
        rebuild();
    }

    /// Builds the page header in the launcher's own style: a title with a muted summary line and
    /// flat text actions on the right.
    ///
    /// @return the header node
    private Node buildHeader() {
        Label title = new Label("下载中心");
        title.getStyleClass().add("title-label");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        summary.getStyleClass().add("subtitle-label");
        summary.setStyle("-fx-font-size: 12px;");

        VBox texts = new VBox(2, title, summary);
        texts.setAlignment(Pos.CENTER_LEFT);

        Node refresh = nativeFlatButton("刷新", SVG.REFRESH, this::rebuild);
        Node clear = nativeFlatButton("清空记录", SVG.DELETE_FOREVER, this::clearHistory);

        HBox actions = new HBox(8, refresh, clear);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, texts, spacer(), actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 16, 10, 16));
        return row;
    }

    /// Builds a flat, icon + text action button matching the launcher's inline actions.
    ///
    /// @param text   the label
    /// @param icon   the icon
    /// @param action the click action
    /// @return the button
    private static Node nativeFlatButton(String text, SVG icon, Runnable action) {
        com.jfoenix.controls.JFXButton button = FXUtils.newRaisedButton(text);
        button.setGraphic(icon.createIcon(button.textFillProperty()));
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Builds the list scroller.
    ///
    /// @return the scroll pane
    private Node buildScroll() {
        list.setFillWidth(true);
        list.setPadding(new Insets(0, 16, 16, 16));

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("download-scroll");
        scroll.setBackground(null);
        FXUtils.smoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /// Rebuilds the list from the current records.
    ///
    /// <p>Rows are rebuilt wholesale rather than patched in place: a download's row changes shape
    /// (its cancel button appears and disappears, its bar switches between determinate and
    /// indeterminate), and rebuilding keeps the code honest at a cost that is irrelevant for a
    /// list this size.</p>
    private void rebuild() {
        List<DownloadEntry> entries = manager.entries();

        list.getChildren().clear();

        if (entries.isEmpty()) {
            Label empty = new Label("还没有下载记录。");
            empty.getStyleClass().add("subtitle-label");
            VBox box = new VBox(empty);
            box.setPadding(new Insets(16, 0, 0, 0));
            list.getChildren().add(box);
        } else {
            ComponentList componentList = new ComponentList();
            for (DownloadEntry entry : entries)
                componentList.getContent().add(buildRow(entry));
            list.getChildren().add(componentList);
        }

        long running = entries.stream().filter(DownloadEntry::isRunning).count();
        long speed = manager.aggregateSpeed();

        StringBuilder text = new StringBuilder();
        text.append(entries.isEmpty() ? "暂无记录" : "共 " + entries.size() + " 条");
        if (running > 0) {
            text.append("　·　").append(running).append(" 个进行中");
            if (speed > 0)
                text.append("　·　").append(formatSpeed(speed));
        }
        summary.setText(text.toString());
    }

    /// Builds one row as a native [LineComponent].
    ///
    /// <p>The launcher's rows are title + subtitle pairs; progress is attached as the subtitle's
    /// trailing content so a download looks like any other settings row with an action, instead of
    /// a bespoke card.</p>
    ///
    /// @param entry the record
    /// @return the row
    private Node buildRow(DownloadEntry entry) {
        // A `LineButton` rather than a bare `LineComponent`: it is the launcher's native clickable
        // row, so hover, ripple and the trailing area all behave exactly like the rest of the UI.
        LineButton row = new LineButton();
        row.setTitle(entry.name);
        row.setSubtitle(detailText(entry));
        row.setLeading(iconFor(entry));

        // The trailing text is where the launcher puts a row's summary value. For a download that
        // is its state plus, while it runs, how far along it is.
        row.setTrailingText(trailingText(entry));

        // Cancel is offered only while it can actually do something.
        if (entry.isRunning()) {
            Node cancel = nativeFlatButton("取消", SVG.CLOSE, () -> manager.cancel(entry));
            row.setTitleTrailing(cancel);
        }

        // A finished download reveals its file on click, which is the likely follow-up action.
        if (entry.state == DownloadEntry.State.DONE && entry.children.isEmpty()) {
            row.setOnAction(event -> {
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

        // A batch carries its individual files. They are folded underneath the summary row so a
        // modpack's mods are visible on demand without turning the page into hundreds of rows.
        if (!entry.children.isEmpty())
            return buildBatchBlock(entry, row);

        return row;
    }

    /// Wraps a batch row together with its folded list of files.
    ///
    /// <p>Collapsed by default: the summary row already carries progress and counts, and expanding
    /// a thousand-file install by default would bury every other download.</p>
    ///
    /// @param entry the batch record
    /// @param row   the summary row
    /// @return the block
    private Node buildBatchBlock(DownloadEntry entry, LineButton row) {
        VBox files = new VBox(0);
        files.getStyleClass().add("download-children");
        files.setVisible(false);
        files.setManaged(false);

        for (DownloadEntry.Child child : entry.children)
            files.getChildren().add(buildChildRow(child));

        // A disclosure marker in the trailing area, so the row itself says it can be expanded.
        Label toggle = new Label("▸ " + entry.children.size());
        toggle.getStyleClass().add("download-related");
        row.setTitleTrailing(toggle);

        // Cancel sits on the summary row too: expanding and cancelling are separate concerns, and
        // hiding cancel behind a fold would make a running install hard to stop.
        if (entry.isRunning()) {
            Node cancel = nativeFlatButton("取消", SVG.CLOSE, () -> manager.cancel(entry));
            row.setTrailingText("");
            row.setTrailingIcon(cancel);
        }

        row.setOnAction(event -> {
            boolean show = !files.isVisible();
            files.setVisible(show);
            files.setManaged(show);
            toggle.setText((show ? "▾ " : "▸ ") + entry.children.size());
        });

        VBox block = new VBox(0, row, files);
        block.setFillWidth(true);
        return block;
    }

    /// Builds one folded file row.
    ///
    /// @param child the file record
    /// @return the row
    private Node buildChildRow(DownloadEntry.Child child) {
        Label name = new Label(child.name);
        name.getStyleClass().add("download-related-name");

        Label state = new Label(childStatus(child));
        state.getStyleClass().add("download-related");

        HBox row = new HBox(10, name, spacer(), state);
        row.getStyleClass().add("download-related-row");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    /// Describes a file's state in one short phrase.
    ///
    /// @param child the file record
    /// @return the text
    private static String childStatus(DownloadEntry.Child child) {
        if (child.failed)
            return "失败";
        if (child.done)
            return "完成";

        double fraction = child.fraction();
        if (fraction >= 0)
            return Math.round(fraction * 100) + "%";
        if (child.received > 0)
            return XWidgets.formatBytes(child.received);
        return "等待中";
    }

    /// Builds the right-aligned summary value for a row.
    ///
    /// @param entry the record
    /// @return the text
    private static String trailingText(DownloadEntry entry) {
        String state = stateText(entry);
        double fraction = entry.fraction();
        if (entry.isRunning() && fraction >= 0)
            return state + " " + Math.round(fraction * 100) + "%";
        return state;
    }

    /// Returns the leading icon for a record's state.
    ///
    /// @param entry the record
    /// @return the icon node
    private static Node iconFor(DownloadEntry entry) {
        SVG icon = switch (entry.state) {
            case RUNNING -> SVG.DOWNLOAD;
            case DONE -> SVG.CHECK_CIRCLE;
            case CANCELLED -> SVG.CLOSE;
            case FAILED -> SVG.ERROR;
        };
        return icon.createIcon(20);
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

    /// Clears finished records.
    private void clearHistory() {
        manager.clearHistory();
        rebuild();
    }

    /// Creates a flexible spacer.
    ///
    /// @return the spacer
    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
