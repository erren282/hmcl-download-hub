/*
 * 下载中心插件 —— 小控件
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import java.util.Locale;

/// Small helpers shared by the download page.
///
/// <p>Formatting only: the page is built from the launcher's own widgets, so there are no
/// third-party controls left to style here.</p>
final class XWidgets {

    private XWidgets() {
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
