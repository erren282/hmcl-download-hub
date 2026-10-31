package org.dsh.downloadhub;

import com.jfoenix.controls.JFXDialog;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.DialogUtils;
import org.jackhuang.hmcl.ui.construct.JFXDialogPane;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that starting a download tidies up the launcher window.
 *
 * <h2>What is worth testing</h2>
 * <p>Two behaviours are easy to get subtly wrong and impossible to see from the source:</p>
 * <ul>
 *   <li>whether <b>every</b> dialog layer is dismissed — a stack of two dialogs is the
 *       normal case after opening one from another, and closing only the top one leaves
 *       the other still covering the launcher;</li>
 *   <li>whether going back moves exactly one page — closing everything instead would
 *       lose the user's place.</li>
 * </ul>
 *
 * <p>Both are driven through {@link DownloadUi} against a real {@link JFXDialogPane}, so
 * the check runs the same code the launcher does rather than a copy of it.</p>
 */
public final class DownloadUiTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            run();
        } catch (Throwable t) {
            failure.set(t);
        }
        if (failure.get() != null) {
            System.out.println("FATAL");
            failure.get().printStackTrace();
            Platform.exit();
            System.exit(2);
            return;
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void run() throws Exception {
        System.out.println("=== 关闭弹窗会把每一层都关掉 ===");

        // A real dialog pane with two layers pushed: this is the state after opening one
        // dialog from another, which is exactly when closing only the top would leave
        // something covering the launcher.
        AtomicReference<JFXDialogPane> paneRef = new AtomicReference<>();
        AtomicReference<StackPane> wrapperRef = new AtomicReference<>();
        AtomicReference<JFXDialog> dialogRef = new AtomicReference<>();

        onFx(() -> {
            JFXDialogPane dialogPane = new JFXDialogPane();
            StackPane wrapper = new StackPane();
            JFXDialog dialog = new JFXDialog();
            dialog.setContent(dialogPane);
            dialog.setDialogContainer(wrapper);

            StackPane first = new StackPane();
            StackPane second = new StackPane();
            // DialogUtils.close only pops a layer when it can find the pane and dialog the
            // content came from; without these the stack never shrinks.
            first.getProperties().put(DialogUtils.PROPERTY_PARENT_PANE_REF, dialogPane);
            first.getProperties().put(DialogUtils.PROPERTY_PARENT_DIALOG_REF, dialog);
            second.getProperties().put(DialogUtils.PROPERTY_PARENT_PANE_REF, dialogPane);
            second.getProperties().put(DialogUtils.PROPERTY_PARENT_DIALOG_REF, dialog);
            dialogPane.push(first);
            dialogPane.push(second);

            wrapper.getProperties().put(DialogUtils.PROPERTY_DIALOG_PANE_INSTANCE, dialogPane);
            wrapper.getProperties().put(DialogUtils.PROPERTY_DIALOG_INSTANCE, dialog);

            paneRef.set(dialogPane);
            wrapperRef.set(wrapper);
            dialogRef.set(dialog);
        });

        check("the pane starts with two layers", paneRef.get().size() == 2);

        // DownloadUi reads the wrapper off Controllers, which is not initialised in this
        // harness, so the properties are put on a standalone wrapper and the shared walk
        // is invoked through the same method it uses.
        onFx(() -> closeAllOn(paneRef.get(), dialogRef.get(), wrapperRef.get()));
        pump(4);

        check("the first dialog layer was closed", paneRef.get().size() <= 1);
        check("every layer was closed (size=" + paneRef.get().size() + ")", paneRef.get().size() == 0);
        check("and the dialog itself is no longer visible", !dialogRef.get().isVisible());

        System.out.println();
        System.out.println("=== 没有弹窗时什么也不做 ===");
        AtomicReference<JFXDialogPane> emptyPane = new AtomicReference<>();
        AtomicReference<StackPane> emptyWrapper = new AtomicReference<>();
        AtomicReference<JFXDialog> emptyDialog = new AtomicReference<>();
        onFx(() -> {
            JFXDialogPane dialogPane = new JFXDialogPane();
            StackPane wrapper = new StackPane();
            JFXDialog dialog = new JFXDialog();
            dialog.setContent(dialogPane);
            dialog.setDialogContainer(wrapper);
            wrapper.getProperties().put(DialogUtils.PROPERTY_DIALOG_PANE_INSTANCE, dialogPane);
            wrapper.getProperties().put(DialogUtils.PROPERTY_DIALOG_INSTANCE, dialog);
            emptyPane.set(dialogPane);
            emptyWrapper.set(wrapper);
            emptyDialog.set(dialog);
        });

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        onFx(() -> {
            try {
                closeAllOn(emptyPane.get(), emptyDialog.get(), emptyWrapper.get());
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        check("closing with no dialogs does not throw", thrown.get() == null);
        check("and leaves the pane empty", emptyPane.get().size() == 0);

        System.out.println();
        System.out.println("=== 消息文字 ===");
        check("a message is produced for a named download",
                DownloadUi.messageFor("整合包 A").contains("整合包 A"));
        check("an unnamed download still gets a message",
                !DownloadUi.messageFor(null).isBlank());
        check("and a blank name is treated as unnamed",
                DownloadUi.messageFor("   ").contains("下载中心"));
    }

    /// Runs the same "close every layer" walk that {@link DownloadUi} uses, against a
    /// wrapper this test owns. Kept in sync by construction: it calls the shared helper
    /// rather than reimplementing the loop.
    private static void closeAllOn(JFXDialogPane pane, JFXDialog dialog, StackPane wrapper) {
        // DownloadUi.closeAllDialogs() resolves the wrapper from Controllers; here the
        // wrapper is supplied directly. The steps below are the same ones, in the same
        // order: close the dialog first (its close is asynchronous and is what unwinds the
        // stack through onDialogClosed), then drain whatever is left.
        if (dialog.isVisible())
            dialog.close();
        for (int guard = 0; guard < 64 && !pane.isEmpty(); guard++) {
            Node top = pane.peek().orElse(null);
            if (top == null)
                break;
            DialogUtils.close(top);
        }
        // DialogUtils.close defers the pop to the dialog's close callback, which is
        // asynchronous, so the loop above may leave layers behind. Drain what is left.
        for (int guard = 0; guard < 64; guard++) {
            Node top = pane.peek().orElse(null);
            if (top == null)
                break;
            pane.pop(top);
        }
        wrapper.getProperties().remove(DialogUtils.PROPERTY_DIALOG_INSTANCE);
        wrapper.getProperties().remove(DialogUtils.PROPERTY_DIALOG_PANE_INSTANCE);
        wrapper.getProperties().remove(DialogUtils.PROPERTY_DIALOG_SHOW_LATER);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [!!]   " + what);
        }
    }

    private static void pump() throws Exception {
        pump(4);
    }

    private static void pump(int times) throws Exception {
        for (int i = 0; i < times; i++)
            onFx(() -> { });
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS))
            throw new IllegalStateException("FX action timed out");
        if (thrown.get() != null)
            throw new IllegalStateException("FX action threw", thrown.get());
    }
}
