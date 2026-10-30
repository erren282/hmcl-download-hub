/*
 * 下载中心插件 —— 入口
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import javafx.application.Platform;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginMenuService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Adds a download hub to the launcher.
///
/// <p>Downloads started through this plugin run as ordinary launcher tasks, so they keep going
/// while the user moves around the launcher. The title bar gains a button that opens the download
/// page, and every download's progress and speed stay visible there.</p>
///
/// <p>Records are written to disk by default, which is what lets the page still make sense after a
/// restart; a clear button removes them on demand.</p>
public final class DownloadHubPlugin extends HmclPlugin {

    /// Classpath location of the bundled style sheet.
    private static final String STYLE_RESOURCE = "/org/dsh/downloadhub/download.css";

    /// File name of the extracted style sheet.
    private static final String STYLE_FILE = "download.css";

    private String styleUrl;

    private DownloadManager manager;
    private DownloadPage page;
    private TitleBarButton titleBarButton;

    @Override
    public void onEnable() {
        // Each step is wrapped so one failure cannot leave the plugin half-initialised without a
        // trace; the launcher swallows exceptions from onEnable, which made a silent failure
        // impossible to diagnose.
        try {
            installStyles();
        } catch (Throwable e) {
            getContext().logWarning("下载中心：样式加载失败 " + e);
        }

        try {
            manager = new DownloadManager(getContext());
            manager.setStartListener(this::onDownloadStarted);
            manager.start();
            manager.addListener(this::updateTitleBar);
        } catch (Throwable e) {
            getContext().logWarning("下载中心：下载管理器启动失败 " + e);
        }

        try {
            titleBarButton = new TitleBarButton(getContext(), this::openPage);
            updateTitleBar();
        } catch (Throwable e) {
            getContext().logWarning("下载中心：标题栏按钮创建失败 " + e);
        }

        try {
            // While the game starts, opening this page would compete for the window with the
            // launching view, so the entry point is disabled for the duration.
            PluginMenuService.registerLaunchListener(this::onLaunchState);
        } catch (Throwable e) {
            getContext().logWarning("下载中心：启动监听注册失败 " + e);
        }

        getContext().logInfo("下载中心已启用：标题栏按钮");
    }

    /// Enables or disables the title bar entry while a game launch is in flight.
    ///
    /// @param event the launch state change
    private void onLaunchState(org.jackhuang.hmcl.event.GameLaunchStateEvent event) {
        boolean launching =
                event.getPhase() == org.jackhuang.hmcl.event.GameLaunchStateEvent.Phase.STARTING;
        if (titleBarButton != null)
            titleBarButton.setEnabled(!launching);
    }

    @Override
    public void onDisable() {
        removeStyles();
        getContext().logInfo("下载中心已停用");
    }

    /// Signals a new download with a dot on the title bar button.
    ///
    /// <p>Navigation used to happen here, but pulling the window away from whatever the user was
    /// doing is disruptive — a download starting is worth an unobtrusive hint, not a page change.
    /// The dot slides in and fades, and the page is one click away.</p>
    private void onDownloadStarted() {
        try {
            if (titleBarButton != null)
                titleBarButton.showDot();
        } catch (Throwable e) {
            getContext().logWarning("无法显示下载提示：" + e);
        }
    }

    /// Updates the title bar tooltip to reflect the current downloads.
    private void updateTitleBar() {
        if (titleBarButton == null || manager == null)
            return;

        int running = 0;
        for (DownloadEntry entry : manager.entries()) {
            if (entry.isRunning())
                running++;
        }

        long speed = manager.aggregateSpeed();
        String text = running == 0
                ? "下载中心"
                : "下载中心 · " + running + " 个进行中"
                + (speed > 0 ? " · " + DownloadPage.formatSpeed(speed) : "");
        titleBarButton.refresh(text);
    }

    /// Brings the download page to the front.
    ///
    /// <p>Navigates straight to the page instead of routing through the settings page first. The
    /// navigator keeps a stack, so pushing settings in between made the back button land on the
    /// settings page rather than wherever the user actually was.</p>
    private void openPage() {
        try {
            if (page == null)
                page = new DownloadPage(manager);

            // Opening the hub is what the dot was asking for, so it has served its purpose.
            if (titleBarButton != null)
                titleBarButton.clearDot();

            // The page is reused, so pushing it again would leave two stack entries pointing at it
            // and back would seem to do nothing. `onPageShown` tracks the real state.
            if (page.isShown())
                return;

            org.jackhuang.hmcl.ui.Controllers.navigate(page);
        } catch (Throwable e) {
            getContext().logWarning("无法打开下载页面：" + e);
        }
    }

    // ----------------------------------------------------------------- styles

    /// Extracts the bundled style sheet and attaches it to the scene root.
    private void installStyles() {
        PluginContext context = getContext();
        Path target = context.getDataFile(STYLE_FILE);

        try (InputStream input = DownloadHubPlugin.class.getResourceAsStream(STYLE_RESOURCE)) {
            if (input == null) {
                context.logWarning("样式表资源缺失：" + STYLE_RESOURCE);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            styleUrl = target.toUri().toString();
        } catch (IOException e) {
            context.logWarning("无法释放样式表：" + e);
            return;
        }

        Runnable apply = () -> {
            var scene = org.jackhuang.hmcl.ui.Controllers.getScene();
            if (scene == null || styleUrl == null)
                return;
            var root = scene.getRoot();
            if (!root.getStylesheets().contains(styleUrl))
                root.getStylesheets().add(styleUrl);
        };

        if (Platform.isFxApplicationThread())
            apply.run();
        else
            Platform.runLater(apply);
    }

    /// Removes the style sheet from the scene root.
    private void removeStyles() {
        Runnable remove = () -> {
            var scene = org.jackhuang.hmcl.ui.Controllers.getScene();
            if (scene != null && styleUrl != null)
                scene.getRoot().getStylesheets().remove(styleUrl);
        };

        if (Platform.isFxApplicationThread())
            remove.run();
        else
            Platform.runLater(remove);
    }
}
