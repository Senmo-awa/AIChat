package com.example.aichat.ai;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进行中的 HTTP 请求登记处。
 * <p>
 * 为什么需要：服务器执行 {@code stop} 时，世界保存可能要几秒，期间在途的对话请求会继续跑完
 * ——既继续烧 token，也没人看得到结果（玩家正在被踢下线）。这里把底层的
 * {@code HttpClient.sendAsync} future 登记下来，停服时一并 cancel，等于把生成中的推理掐掉。
 * <p>
 * 只登记最原始的 future（取消它才会真正中断连接），不要登记派生出来的链条。
 */
public final class InFlight {
    private static final Set<CompletableFuture<?>> ACTIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean cancelled;

    private InFlight() {
    }

    /** 登记一个底层请求；若已经进入停服状态，直接取消，不再发出。 */
    public static <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        if (cancelled) {
            future.cancel(true);
            return future;
        }
        ACTIVE.add(future);
        future.whenComplete((value, error) -> ACTIVE.remove(future));
        return future;
    }

    /** 停服：取消所有在途请求，并让后续请求直接失败。 */
    public static int cancelAll() {
        cancelled = true;
        int count = 0;
        for (CompletableFuture<?> future : ACTIVE) {
            if (future.cancel(true)) {
                count++;
            }
        }
        ACTIVE.clear();
        return count;
    }

    /** 服务器重新启动时复位（集成服务器/热重载场景）。 */
    public static void reset() {
        cancelled = false;
        ACTIVE.clear();
    }

    public static int activeCount() {
        return ACTIVE.size();
    }
}
