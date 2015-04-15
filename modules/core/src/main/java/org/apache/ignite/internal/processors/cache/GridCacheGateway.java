/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

/**
 * Cache gateway.
 */
@GridToStringExclude
public class GridCacheGateway<K, V> {
    /** Context. */
    private final GridCacheContext<K, V> ctx;

    /** Stopped flag for dynamic caches. */
    private volatile boolean stopped;

    /** */
    private GridSpinReadWriteLock rwLock = new GridSpinReadWriteLock();

    /**
     * @param ctx Cache context.
     */
    public GridCacheGateway(GridCacheContext<K, V> ctx) {
        assert ctx != null;

        this.ctx = ctx;
    }

    /**
     * Enter a cache call.
     */
    public void enter() {
        if (ctx.deploymentEnabled())
            ctx.deploy().onEnter();

        rwLock.readLock();

        if (stopped) {
            rwLock.readUnlock();

            throw new IllegalStateException("Dynamic cache has been stopped: " + ctx.name());
        }
    }

    /**
     * Enter a cache call.
     *
     * @return {@code true} if enter successful, {@code false} if the cache or the node was stopped.
     */
    public boolean enterIfNotClosed() {
        enterIfNotClosedNoLock();

        // Must unlock in case of unexpected errors to avoid
        // deadlocks during kernal stop.
        rwLock.readLock();

        if (stopped) {
            rwLock.readUnlock();

            return false;
        }

        return true;
    }

    /**
     * Enter a cache call without lock.
     *
     * @return {@code true} if enter successful, {@code false} if the cache or the node was stopped.
     */
    public boolean enterIfNotClosedNoLock() {
        if (ctx.deploymentEnabled())
            ctx.deploy().onEnter();

        return !stopped;
    }

    /**
     * Leave a cache call entered by {@link #enter()} method.
     */
    public void leaveNoLock() {
        ctx.tm().resetContext();
        ctx.mvcc().contextReset();

        // Unwind eviction notifications.
        if (!ctx.shared().closed(ctx))
            CU.unwindEvicts(ctx);
    }

    /**
     * Leave a cache call entered by {@link #enter()} method.
     */
    public void leave() {
        try {
           leaveNoLock();
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * @param prj Projection to guard.
     * @return Previous projection set on this thread.
     */
    @Nullable public GridCacheProjectionImpl<K, V> enter(@Nullable GridCacheProjectionImpl<K, V> prj) {
        onEnter();

        rwLock.readLock();

        if (stopped) {
            rwLock.readUnlock();

            throw new IllegalStateException("Dynamic cache has been stopped: " + ctx.name());
        }

        // Must unlock in case of unexpected errors to avoid
        // deadlocks during kernal stop.
        try {
            return setProjectionPerCall(prj);
        }
        catch (RuntimeException e) {
            rwLock.readUnlock();

            throw e;
        }
    }

    /**
     * @param prj Projection to guard.
     * @return Previous projection set on this thread.
     */
    @Nullable public GridCacheProjectionImpl<K, V> enterNoLock(@Nullable GridCacheProjectionImpl<K, V> prj) {
        onEnter();

        if (stopped)
            throw new IllegalStateException("Dynamic cache has been stopped: " + ctx.name());

        return setProjectionPerCall(prj);
    }

    /**
     * On enter.
     */
    private void onEnter() {
        try {
            ctx.itHolder().checkWeakQueue();

            GridCacheAdapter<K, V> cache = ctx.cache();

            GridCachePreloader<K, V> preldr = cache != null ? cache.preloader() : null;

            if (preldr == null)
                throw new IllegalStateException("Grid is in invalid state to perform this operation. " +
                    "It either not started yet or has already being or have stopped [gridName=" + ctx.gridName() + ']');

            preldr.startFuture().get();
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to wait for cache preloader start [cacheName=" +
                ctx.name() + "]", e);
        }

        if (ctx.deploymentEnabled())
            ctx.deploy().onEnter();
    }

    /**
     * Set thread local projection per call.
     *
     * @param prj Projection to guard.
     * @return Previous projection set on this thread.
     */
    private GridCacheProjectionImpl<K, V> setProjectionPerCall(@Nullable GridCacheProjectionImpl<K, V> prj) {
        GridCacheProjectionImpl<K, V> prev = ctx.projectionPerCall();

        if (prev != null || prj != null)
            ctx.projectionPerCall(prj);

        return prev;
    }

    /**
     * @param prev Previous.
     */
    public void leave(GridCacheProjectionImpl<K, V> prev) {
        try {
            leaveNoLock(prev);
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * @param prev Previous.
     */
    public void leaveNoLock(GridCacheProjectionImpl<K, V> prev) {
        ctx.tm().resetContext();
        ctx.mvcc().contextReset();

        // Unwind eviction notifications.
        CU.unwindEvicts(ctx);

        // Return back previous thread local projection per call.
        ctx.projectionPerCall(prev);
    }

    /**
     *
     */
    public void block() {
        stopped = true;
    }

    /**
     *
     */
    public void onStopped() {
        boolean interrupted = false;

        while (true) {
            if (rwLock.tryWriteLock())
                break;
            else {
                try {
                    U.sleep(200);
                }
                catch (IgniteInterruptedCheckedException ignore) {
                    interrupted = true;
                }
            }
        }

        if (interrupted)
            Thread.currentThread().interrupt();

        try {
            // No-op.
            stopped = true;
        }
        finally {
            rwLock.writeUnlock();
        }
    }
}
