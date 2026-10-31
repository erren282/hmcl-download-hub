package org.dsh.downloadhub;

import com.jfoenix.controls.JFXDialog;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.DialogUtils;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.JFXDialogPane;
import org.jackhuang.hmcl.ui.decorator.Decorator;

/**
 * 下载开始后的界面收尾：关掉弹窗、退回上一级页面、在底部提示。
 *
 * <h2>为什么下载开始要动界面</h2>
 * <p>用户点了「下载」之后，那个按钮所在的弹窗或页面就没用了——下载中心会接管进度显示。
 * 留着弹窗，用户会以为还要在这里等；留着页面，用户会以为下载还没开始。
 * 所以点下去之后立刻把界面收回来，用底部一条提示告诉他"已经开始下载了"。</p>
 *
 * <h2>顺序</h2>
 * <p>先关弹窗、再退页面、最后弹提示。反过来做的话，提示会被随后关闭的弹窗一起带走——
 * 提示挂在启动器底部，而弹窗的遮罩层盖在它上面。</p>
 */
final class DownloadUi {

    private DownloadUi() {
    }

    /**
     * 下载开始后收拾界面。
     *
     * @param message 底部提示的文字
     */
    static void afterDownloadStarted(String message) {
        Runnable action = () -> {
            closeAllDialogs();
            goBackOnePage();
            showToast(message);
        };

        if (Platform.isFxApplicationThread())
            action.run();
        else
            Platform.runLater(action);
    }

    /**
     * 底部提示的文案。
     *
     * @param name 下载名；为空时给一句通用说明
     */
    static String messageFor(String name) {
        if (name == null || name.isBlank())
            return "已开始下载，进度见下载中心";
        return "已开始下载：" + name;
    }

    /**
     * 关闭启动器里所有弹窗。
     *
     * <p>「所有」是指对话框栈里的每一层：用户可能先开了 A 弹窗，又从 A 里开了 B，
     * 只关最上面那层会留下 A 挡在前面。</p>
     */
    static void closeAllDialogs() {
        Decorator decorator = Controllers.getDecorator().getDecorator();
        StackPane wrapper = decorator.getDrawerWrapper();
        if (wrapper == null)
            return;

        Object pane = wrapper.getProperties().get(DialogUtils.PROPERTY_DIALOG_PANE_INSTANCE);
        Object dialog = wrapper.getProperties().get(DialogUtils.PROPERTY_DIALOG_INSTANCE);

        // 先收掉对话框本身：`JFXDialog.close()` 是异步的，它播完关闭动画才回调
        // `onDialogClosed`，而 `DialogUtils.close` 正是靠那个回调把栈里的层弹掉。
        // 反过来做的话，逐层关闭时第一次调用就触发了对话框关闭，后面几层还在栈里、
        // 却再也不会被弹掉——留最上面那层挡在启动器前面。
        if (dialog instanceof JFXDialog jfxDialog && jfxDialog.isVisible())
            jfxDialog.close();

        // 逐层通知：DialogUtils.close 会跑 onDialogClosed 回调，有的弹窗靠它释放资源
        // 或提交状态，跳过回调会漏掉那些清理。
        //
        // 但栈本身不能指望这些调用去清：`DialogUtils.close` 把 `pane.pop` 挂在对话框的
        // 关闭回调上，而关闭是异步的，循环里读到的 `peek()` 一直是同一层。所以先按层
        // 通知一遍，再直接把栈清空——这里的目标是"界面上不再有弹窗"，不是复用关闭动画。
        if (pane instanceof JFXDialogPane dialogPane) {
            for (int guard = 0; guard < 64 && !dialogPane.isEmpty(); guard++) {
                Node top = dialogPane.peek().orElse(null);
                if (top == null)
                    break;
                DialogUtils.close(top);
            }

            // 兜底：上面那轮可能因为异步而没能真正出栈，直接清干净。
            for (int guard = 0; guard < 64; guard++) {
                Node top = dialogPane.peek().orElse(null);
                if (top == null)
                    break;
                dialogPane.pop(top);
            }
        }

        // 栈已经清空，属性也该跟着清掉；留着会让下一次开弹窗以为对话框还在。
        wrapper.getProperties().remove(DialogUtils.PROPERTY_DIALOG_INSTANCE);
        wrapper.getProperties().remove(DialogUtils.PROPERTY_DIALOG_PANE_INSTANCE);
        wrapper.getProperties().remove(DialogUtils.PROPERTY_DIALOG_SHOW_LATER);
    }

    /**
     * 退回上一级页面。
     *
     * <p>只退一层，不是一路退到底：用户是从某个页面点进来的，回到那一页才是他期待的落点。
     * 已经在根页面时什么也不做。</p>
     */
    static void goBackOnePage() {
        try {
            var navigator = Controllers.getDecorator().getNavigator();
            if (navigator != null && navigator.canGoBack())
                navigator.close();
        } catch (Throwable ignored) {
            // 退不了就停在原地，不影响下载本身。
        }
    }

    /// 底部提示。
    static void showToast(String message) {
        try {
            Controllers.showToast(message);
        } catch (Throwable ignored) {
            // 提示是锦上添花，弹不出来也不能影响下载。
        }
    }
}
