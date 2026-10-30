/*
 * 下载中心插件 —— 记录存储
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import org.jackhuang.hmcl.plugin.PluginContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Manages the on-disk footprint of the download list.
///
/// <p>The list itself is a view of what is transferring right now, and those transfers die with
/// the launcher process — nothing is resumed on the next start. The only file involved is therefore
/// the leftover history of the previous session, which is removed once at start-up.</p>
final class DownloadStore {

    /// File that used to hold the history between sessions.
    private static final String FILE = "downloads.json";

    private final PluginContext context;

    DownloadStore(PluginContext context) {
        this.context = context;
    }

    /// Drops everything the previous launcher session left behind.
    ///
    /// <p>A download record only describes work that was in flight while the launcher was running.
    /// Once the launcher is gone those transfers are dead — they are not resumed, and the page used
    /// to show them as cancelled rows forever. Starting with an empty list each launch keeps the
    /// page meaning "what is happening now" rather than accumulating a log of every past session.</p>
    ///
    /// <p>Only the history file is removed; files already written to disk stay exactly where they
    /// are.</p>
    void clearLastSession() {
        Path file = context.getDataFile(FILE);
        if (!Files.isRegularFile(file))
            return;
        try {
            Files.deleteIfExists(file);
            context.logInfo("已清理上次启动器会话遗留的下载记录");
        } catch (IOException e) {
            context.logWarning("无法清理上次的下载记录：" + e.getMessage());
        }
    }
}
