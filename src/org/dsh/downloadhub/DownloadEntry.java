/*
 * 下载中心插件 —— 下载记录
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/// One download the hub knows about.
///
/// <p>Instances are mutable while the download runs and are snapshotted into the history file when
/// it settles, so the list survives a restart.</p>
public final class DownloadEntry {

    /// Where a download stands.
    public enum State {
        /// Queued or transferring.
        RUNNING,
        /// Finished successfully.
        DONE,
        /// Stopped by the user.
        CANCELLED,
        /// Stopped by an error.
        FAILED
    }

    /// File name shown in the list.
    public String name = "";

    /// Source URL, kept for display and for retrying.
    public String url = "";

    /// Destination path.
    public String target = "";

    /// Batch this download belongs to, or `null` for a standalone file.
    ///
    /// <p>A game install or modpack is hundreds of files sharing one batch name. The page shows a
    /// single row for the batch instead of flooding the list with individual libraries.</p>
    public String batch = "";

    /// Files finished in this batch, when `batch` is set.
    public int batchDone;

    /// Files started in this batch, when `batch` is set.
    public int batchTotal;

    /// Bytes accounted for by files that already completed, for a batch.
    ///
    /// <p>Progress events report the size of the file being fetched, not the batch, so those bytes
    /// have to be accumulated as files finish; otherwise the bar restarts at zero for every file.</p>
    public long batchBytesDone;

    /// Individual files inside a batch, newest first, capped so a thousand-file install does not
    /// grow without bound.
    ///
    /// <p>The page shows these folded under the batch row, which is what makes a modpack's mods
    /// visible without turning the list into hundreds of rows.</p>
    public final java.util.List<Child> children = new java.util.ArrayList<>();

    /// One file inside a batch.
    public static final class Child {

        /// File name, as reported by the download.
        public String name = "";

        /// Bytes received for this file.
        public long received;

        /// Total bytes for this file, or `0` when unknown.
        public long total;

        /// Whether this file has finished.
        public boolean done;

        /// Whether this file failed.
        public boolean failed;

        /// Returns the completion fraction, or `-1` when the total is unknown.
        ///
        /// @return a value in `[0, 1]`, or `-1`
        public double fraction() {
            return total > 0 ? Math.min(1.0, (double) received / total) : -1;
        }
    }

    /// Current state.
    public State state = State.RUNNING;

    /// Bytes received so far.
    public long received;

    /// Total bytes, or `0` when the server did not say.
    public long total;

    /// Bytes per second, updated while running.
    public long speed;

    /// Epoch millis when the entry was created.
    public long startedAt = Instant.now().toEpochMilli();

    /// Error message when `state` is `FAILED`.
    public String message = "";

    /// Stops the download, or `null` when it cannot be stopped.
    ///
    /// <p>Supplied by the launcher with the start event; not persisted, since it is only meaningful
    /// for a transfer that is still running.</p>
    public transient Runnable cancelAction;

    /// Returns the completion fraction, or `-1` when the total is unknown.
    ///
    /// @return a value in `[0, 1]`, or `-1`
    public double fraction() {
        return total > 0 ? Math.min(1.0, (double) received / total) : -1;
    }

    /// Returns whether the entry is still active.
    ///
    /// @return whether it is running
    public boolean isRunning() {
        return state == State.RUNNING;
    }
}
