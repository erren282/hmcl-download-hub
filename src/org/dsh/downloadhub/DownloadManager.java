/*
 * 下载中心插件 —— 下载管理
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import javafx.application.Platform;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.task.FetchTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// Tracks the launcher's downloads and exposes them to the page.
///
/// <p>Rather than running its own transfers, this listens to the launcher's download events, so
/// files the user starts from the launcher's own pages appear here too. The launcher keeps those
/// tasks on its IO scheduler, which is what makes them continue while the user is elsewhere.</p>
public final class DownloadManager {

    /// Notified on the JavaFX thread whenever the list or a progress value changes.
    public interface Listener {

        /// Called after the list or any entry changed.
        void onChanged();
    }

    /// Notified when a brand-new download appears.
    ///
    /// <p>Used to bring the download page to the front: starting an install in the launcher
    /// otherwise leaves the user looking at the page they started from, with no sign that anything
    /// is happening.</p>
    public interface StartListener {

        /// Called once per newly started download, on a background thread.
        void onDownloadStarted();
    }

    /// Upper bound on stored records, so the history cannot grow without limit.
    private static final int MAX_ENTRIES = 200;

    private final PluginContext context;
    private final DownloadStore store;
    private final List<DownloadEntry> entries = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /// Called when a new download begins, so the host can show the page.
    private volatile StartListener startListener;

    /// Registers the listener that reacts to new downloads.
    ///
    /// @param listener the listener
    void setStartListener(StartListener listener) {
        this.startListener = listener;
    }

    /// Total speed reported by the launcher, in bytes per second.
    private volatile long aggregateSpeed;

    DownloadManager(PluginContext context) {
        this.context = context;
        this.store = new DownloadStore(context);
    }

    /// Starts following the launcher's download events.
    ///
    /// <p>Records from the previous session are discarded first: those transfers are gone with the
    /// process, so showing them (as cancelled rows) would be a log of history rather than a view of
    /// what is downloading now.</p>
    void start() {
        store.clearLastSession();
        entries.clear();

        // The launcher broadcasts every download it starts, including the ones it begins from its
        // own pages. That is what lets this page list downloads the user started elsewhere.
        PluginMenuService.registerDownloadListener(this::onDownloadEvent);

        // `FetchTask` also publishes an aggregate speed; keep it for the header readout.
        FetchTask.SPEED_EVENT.register(event -> {
            aggregateSpeed = event.getSpeed();
            spreadSpeed();
            // A batch ends when the enclosing task stops; this is the tick that notices it.
            if (aggregateSpeed == 0)
                settleFinishedBatches();
            notifyListeners();
        });

        notifyListeners();
    }

    /// Applies one download event to the record list.
    ///
    /// <p>Runs on the downloading thread, so the list is only mutated here and the UI is refreshed
    /// through [notifyListeners], which marshals to the JavaFX thread.</p>
    ///
    /// <p>Downloads that share a batch — a game install, a modpack — are folded into one row keyed
    /// by the batch name. Otherwise installing a game would add a row for every library it pulls,
    /// burying everything else.</p>
    ///
    /// @param event the event
    private void onDownloadEvent(org.jackhuang.hmcl.event.DownloadEvent event) {
        try {
            String batch = event.getBatch();
            DownloadEntry entry = batch != null && !batch.isBlank()
                    ? findBatch(batch)
                    : findEntry(event.getUrl(), event.getTarget());

            switch (event.getPhase()) {
                case STARTED -> {
                    if (entry == null) {
                        // A bare `FetchTask` is how the launcher fetches images too — skins, avatars,
                        // addon icons — and those arrive constantly just from browsing. Only an
                        // install batch or a download with a real destination file belongs in the
                        // list, otherwise the page jumps to the foreground while the user is only
                        // looking at pictures.
                        if ((batch == null || batch.isBlank()) && !isFileDownload(event))
                            return;

                        entry = new DownloadEntry();
                        entry.name = batch != null && !batch.isBlank()
                                ? batchName(batch)
                                : event.getName();
                        entry.batch = batch == null ? "" : batch;
                        entry.url = String.valueOf(event.getUrl());
                        entry.target = String.valueOf(event.getTarget());
                        entry.total = event.getTotal();
                        entry.cancelAction = event.getCancelAction();
                        entries.add(0, entry);
                        trimHistory();

                        // Only a genuinely new row pulls the page forward; the hundreds of files in
                        // one batch must not keep re-triggering the jump.
                        StartListener listener = startListener;
                        if (listener != null) {
                            try {
                                listener.onDownloadStarted();
                            } catch (Throwable ignored) {
                                // Navigation is best-effort.
                            }
                        }
                    }
                    entry.state = DownloadEntry.State.RUNNING;
                    entry.message = "";
                    if (!entry.batch.isEmpty())
                        entry.batchTotal++;
                }
                case PROGRESS -> {
                    if (entry == null)
                        return;

                    if (entry.batch.isEmpty()) {
                        // A single file: the event carries its own totals.
                        entry.received = event.getReceived();
                        if (event.getTotal() > 0)
                            entry.total = event.getTotal();
                    } else {
                        // A batch: the launcher downloads several files at once, so each event only
                        // describes one of them. The row counts bytes already finished plus this
                        // file's progress — assigning the per-file value directly made the bar drop
                        // back to zero at every file, which is what made it disagree with reality.
                        entry.received = Math.max(entry.received,
                                entry.batchBytesDone + event.getReceived());
                        entry.total = Math.max(entry.total,
                                entry.batchBytesDone + Math.max(0, event.getTotal()));
                    }
                }
                case FINISHED -> {
                    if (entry == null)
                        return;
                    if (!entry.batch.isEmpty()) {
                        // A batch stays running until its last file lands. The finished file's bytes
                        // move into the completed total, so the bar keeps climbing instead of
                        // dropping back to whatever the next file starts at.
                        entry.batchDone++;
                        entry.batchBytesDone += Math.max(0, event.getReceived());
                        entry.received = Math.max(entry.received, entry.batchBytesDone);
                        entry.state = DownloadEntry.State.RUNNING;
                        break;
                    }
                    entry.received = entry.total > 0 ? entry.total : event.getReceived();
                    entry.speed = 0;
                    entry.state = DownloadEntry.State.DONE;
                }
                case FAILED -> {
                    if (entry == null)
                        return;
                    entry.speed = 0;
                    entry.state = DownloadEntry.State.FAILED;
                    entry.message = event.getMessage() == null ? "" : event.getMessage();
                }
            }

            notifyListeners();
        } catch (Throwable e) {
            context.logWarning("处理下载事件失败：" + e);
        }
    }

    /// Returns whether an event describes a real file download rather than a fetched image.
    ///
    /// <p>Image loading goes through the same task type as a download, so the event alone cannot
    /// tell them apart. Two signals separate them: a download always has a destination file, and
    /// images are not among the extensions worth listing.</p>
    ///
    /// @param event the event
    /// @return whether it is a file download
    private static boolean isFileDownload(org.jackhuang.hmcl.event.DownloadEvent event) {
        String target = event.getTarget();
        if (target == null || target.isBlank())
            return false;

        String lower = target.toLowerCase(java.util.Locale.ROOT);
        return !(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".ico") || lower.endsWith(".svg"));
    }

    /// Finds the row representing a batch.
    ///
    /// @param batch the batch name
    /// @return the record, or `null`
    private DownloadEntry findBatch(String batch) {
        for (DownloadEntry entry : entries) {
            if (batch.equals(entry.batch))
                return entry;
        }
        return null;
    }

    /// Turns a batch identifier into a readable title.
    ///
    /// <p>The launcher reports the version being installed — `1.20.1`, `1.20.1-forge-47.2.0` — so
    /// the row reads as that version rather than as a bare install step. Older stages without a
    /// version fall back to a generic title.</p>
    ///
    /// @param batch the batch identifier
    /// @return the display name
    static String batchName(String batch) {
        if (batch == null || batch.isBlank())
            return "安装";
        if (batch.startsWith("hmcl.install"))
            return "安装";
        return "安装 " + batch;
    }

    /// Marks batches whose files have all arrived as finished.
    ///
    /// <p>Individual files report `FINISHED`, but a batch has no such event of its own — the
    /// enclosing task simply stops. Running this on the aggregate-speed tick lets the row settle
    /// once traffic stops and every known file is accounted for.</p>
    private void settleFinishedBatches() {
        for (DownloadEntry entry : entries) {
            if (entry.batch.isEmpty() || !entry.isRunning())
                continue;
            if (entry.batchTotal > 0 && entry.batchDone >= entry.batchTotal) {
                entry.state = DownloadEntry.State.DONE;
                entry.speed = 0;
            }
        }
    }

    /// Finds the record matching a download.
    ///
    /// @param url    the source URL
    /// @param target the destination path
    /// @return the record, or `null`
    private DownloadEntry findEntry(String url, String target) {
        boolean hasTarget = target != null && !target.isBlank();
        for (DownloadEntry entry : entries) {
            if (!entry.batch.isEmpty())
                continue;
            if (hasTarget && target.equals(entry.target))
                return entry;
            if (!hasTarget && url != null && !url.isBlank() && url.equals(entry.url))
                return entry;
        }
        return null;
    }

    /// Stops a running download and removes whatever it had written.
    ///
    /// <p>The action comes from the launcher with the start event; a plugin has no other way to
    /// reach the task. It stops the whole run, so the files queued behind this one never start.</p>
    ///
    /// <p>The launcher writes to a temporary file and only moves it into place once the checksum
    /// matches, so a cancelled transfer leaves nothing at the destination — but it may leave a
    /// partial file behind, and a user who cancelled does not want half a mod on disk. Removal is
    /// retried briefly because the download thread may still hold the handle closed just after the
    /// cancel.</p>
    ///
    /// @param entry the record to stop
    void cancel(DownloadEntry entry) {
        if (entry == null || !entry.isRunning())
            return;

        Runnable action = entry.cancelAction;
        entry.cancelAction = null;
        entry.state = DownloadEntry.State.CANCELLED;
        entry.speed = 0;

        if (action != null) {
            try {
                action.run();
            } catch (Throwable e) {
                context.logWarning("取消下载失败：" + e);
            }
        }

        removePartialFile(entry.target);
        notifyListeners();
    }

    /// Deletes a partially downloaded file, retrying while it is still open.
    ///
    /// @param target the destination path, or an empty string
    private void removePartialFile(String target) {
        if (target == null || target.isBlank())
            return;

        Thread cleaner = new Thread(() -> {
            try {
                java.nio.file.Path path = java.nio.file.Path.of(target);

                // The writing thread needs a moment to notice the cancel and close the file.
                for (int attempt = 0; attempt < 20; attempt++) {
                    Thread.sleep(150);
                    try {
                        if (!java.nio.file.Files.exists(path))
                            return;
                        java.nio.file.Files.delete(path);
                        context.logInfo("已删除未完成的下载文件：" + path.getFileName());
                        return;
                    } catch (java.io.IOException stillOpen) {
                        // Retry: the handle is likely still closing.
                    }
                }
                context.logWarning("未能删除未完成的下载文件：" + target);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                context.logWarning("删除未完成的下载文件失败：" + e);
            }
        }, "download-hub-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    /// Divides the aggregate speed across the running entries.
    ///
    /// <p>The launcher reports one number for the whole process, so the per-row rate is an
    /// approximation by design; the header shows the exact total.</p>
    private void spreadSpeed() {
        List<DownloadEntry> running = runningEntries();
        if (running.isEmpty())
            return;
        long share = aggregateSpeed / Math.max(1, running.size());
        for (DownloadEntry entry : running)
            entry.speed = share;
    }

    /// Drops the oldest records when the list grows too long.
    private void trimHistory() {
        while (entries.size() > MAX_ENTRIES)
            entries.remove(entries.size() - 1);
    }

    /// Returns the current records, newest first.
    ///
    /// @return the records
    List<DownloadEntry> entries() {
        return entries;
    }

    /// Registers a listener.
    ///
    /// @param listener the listener
    void addListener(Listener listener) {
        listeners.add(listener);
    }

    /// Returns the combined speed of all running downloads.
    ///
    /// @return bytes per second
    long aggregateSpeed() {
        return aggregateSpeed;
    }

    /// Clears finished records, leaving running downloads alone.
    void clearHistory() {
        List<DownloadEntry> running = runningEntries();
        entries.clear();
        entries.addAll(running);
        notifyListeners();
    }

    /// Returns the entries still running.
    ///
    /// @return the running entries
    private List<DownloadEntry> runningEntries() {
        List<DownloadEntry> running = new ArrayList<>();
        for (DownloadEntry entry : entries) {
            if (entry.isRunning())
                running.add(entry);
        }
        return running;
    }

    /// Notifies listeners on the JavaFX thread.
    private void notifyListeners() {
        Platform.runLater(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onChanged();
                } catch (Throwable ignored) {
                    // A failing listener must not stop the others.
                }
            }
        });
    }
}
