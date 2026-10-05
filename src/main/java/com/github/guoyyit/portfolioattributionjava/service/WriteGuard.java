package com.github.guoyyit.portfolioattributionjava.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 进程内并发护栏。
 *
 * <p>写操作获取排他锁，且持锁范围覆盖<b>整个数据库事务</b>（在事务开启前加锁、
 * 提交/回滚之后解锁）；读操作获取共享锁并在其只读事务期间持有。因此：
 * <ul>
 *   <li>多个读可以并发，且不会与任何写事务并发执行；</li>
 *   <li>任何一次查询观察到的都是某个写事务完整提交之后的状态，
 *       不可能读到部分写入或新旧混合的数据。</li>
 * </ul>
 *
 * <p>该护栏与数据库层的事务隔离（默认 SNAPSHOT，见 application.properties 的
 * {@code portfolio.query.isolation}）共同构成可验证的一致性保证：护栏在进程内
 * 消除查询与提交的交织，快照隔离保证单条查询内多次读取同一数据库快照。
 */
@Component
public class WriteGuard {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public <T> T runInWriteLock(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void runInWriteLock(Runnable action) {
        runInWriteLock(() -> {
            action.run();
            return null;
        });
    }

    public <T> T runInReadLock(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }
}
