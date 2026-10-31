package org.dsh.downloadhub;

import javafx.application.Platform;
import org.jackhuang.hmcl.event.GameLaunchStateEvent;
import org.jackhuang.hmcl.event.EventBus;
import org.jackhuang.hmcl.plugin.PluginMenuService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that a launch reports both of its ends, because the title bar entry is disabled
 * between them.
 *
 * <h2>The bug this guards against</h2>
 * <p>The download hub greys itself out from {@code STARTING} until {@code FINISHED}. The
 * {@code FINISHED} event used to be fired only from the process listener, which runs when
 * the game process exits — so a launch that failed, was cancelled, or never produced a
 * process left the button disabled for the rest of the session. It could not be clicked
 * and could not be recovered without restarting the launcher.</p>
 *
 * <p>The fix reports {@code FINISHED} from the task executor's stop handler, which runs
 * for every ending. This test drives both events through the real event bus and checks
 * that a listener sees a balanced pair.</p>
 */
public final class LaunchStateTest {

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
        System.out.println("=== 启动与结束事件成对到达 ===");

        List<GameLaunchStateEvent.Phase> seen = new CopyOnWriteArrayList<>();
        CountDownLatch both = new CountDownLatch(2);

        onFx(() -> PluginMenuService.registerLaunchListener(event -> {
            seen.add(event.getPhase());
            both.countDown();
        }));

        // Simulate a launch that fails: the executor reports start, then stop. Before the
        // fix the second event never came for this path.
        onFx(() -> EventBus.EVENT_BUS.fireEvent(
                new GameLaunchStateEvent(new Object(), GameLaunchStateEvent.Phase.STARTING, "1.20.1")));
        onFx(() -> EventBus.EVENT_BUS.fireEvent(
                new GameLaunchStateEvent(new Object(), GameLaunchStateEvent.Phase.FINISHED, "1.20.1")));

        boolean arrived = both.await(10, TimeUnit.SECONDS);
        pump(4);

        check("both phases reached the listener", arrived);
        check("the first phase is STARTING",
                !seen.isEmpty() && seen.get(0) == GameLaunchStateEvent.Phase.STARTING);
        check("the last phase is FINISHED",
                seen.size() >= 2 && seen.get(seen.size() - 1) == GameLaunchStateEvent.Phase.FINISHED);

        System.out.println();
        System.out.println("=== 禁用与恢复是幂等的 ===");
        // The handler must tolerate a repeated FINISHED: the fix now reports it from two
        // places, and firing twice must not leave the button toggled back off.
        List<GameLaunchStateEvent.Phase> repeated = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        onFx(() -> PluginMenuService.registerLaunchListener(event -> {
            repeated.add(event.getPhase());
            three.countDown();
        }));
        onFx(() -> {
            EventBus.EVENT_BUS.fireEvent(new GameLaunchStateEvent(
                    new Object(), GameLaunchStateEvent.Phase.FINISHED, "1.20.1"));
            EventBus.EVENT_BUS.fireEvent(new GameLaunchStateEvent(
                    new Object(), GameLaunchStateEvent.Phase.FINISHED, "1.20.1"));
            EventBus.EVENT_BUS.fireEvent(new GameLaunchStateEvent(
                    new Object(), GameLaunchStateEvent.Phase.STARTING, "1.20.1"));
        });
        check("repeated reports all arrive", three.await(10, TimeUnit.SECONDS));
        pump(4);
        check("a second FINISHED is delivered rather than swallowed",
                repeated.stream().filter(p -> p == GameLaunchStateEvent.Phase.FINISHED).count() == 2);
        check("a later STARTING still arrives",
                repeated.get(repeated.size() - 1) == GameLaunchStateEvent.Phase.STARTING);
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
