package com.moulberry.axiom.operations;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A long running, chunk scoped job submitted by a player.
 *
 * <p>An operation can span hundreds of chunks, and on a regionised server those chunks belong to
 * several different regions. {@link OperationQueue} therefore hands out chunks one at a time: it
 * asks the operation to {@link #claimChunks claim} a budget of chunk positions per tick and runs
 * {@link #processChunk} for each one as a task on the region that owns that chunk. No region ever
 * reads a chunk it does not own, and chunks in different regions are processed in parallel.
 *
 * <p>Contract for implementations:
 * <ul>
 *     <li>{@link #claimChunks} and {@link #shouldLoadChunk} are called from the global region and
 *     must not touch chunk or entity data.</li>
 *     <li>{@link #processChunk} and {@link #complete} run on region threads and may be called
 *     concurrently for different chunks, so nothing may assume ordering between them.</li>
 *     <li>{@link #complete} runs once, after the last claimed chunk has been processed.</li>
 * </ul>
 */
public abstract class PendingOperation {

    private final ServerLevel level;
    private final AtomicInteger outstandingChunks = new AtomicInteger();
    private final AtomicBoolean finished = new AtomicBoolean();

    protected PendingOperation(ServerLevel level) {
        this.level = level;
    }

    public final ServerLevel level() {
        return this.level;
    }

    public abstract ServerPlayer executor();

    /** Chunks that have been claimed but not yet processed. */
    public final int outstandingChunks() {
        return this.outstandingChunks.get();
    }

    public final boolean isFinished() {
        return this.finished.get();
    }

    /**
     * Claims the right to finish this operation, returning false if somebody else already did.
     * Several regions can observe zero outstanding chunks at the same time, so exactly one of them
     * has to win.
     */
    protected final boolean markFinished() {
        return this.finished.compareAndSet(false, true);
    }

    /**
     * Reserve up to {@code max} chunk positions to work on, writing them into {@code dest} starting
     * at index 0 and returning how many were written. Only called from the global region.
     */
    protected abstract int claimChunks(long[] dest, int max);

    /**
     * Whether the queue is allowed to load this chunk when it is not already loaded. Only called
     * from the global region, so it must be a pure function of state captured earlier.
     */
    protected boolean shouldLoadChunk(ServerLevel level, ChunkPos pos) {
        return true;
    }

    /** Runs on the region that owns {@code pos}, with the chunk already loaded. */
    protected abstract void processChunk(ServerLevel level, ChunkPos pos, LevelChunk chunk);

    /** Runs once, after the last claimed chunk has been processed. */
    protected abstract void complete(ServerLevel level);

    /** Called instead of {@link #complete} when a chunk throws. */
    protected void fail(Throwable t) {
    }

    final void chunkClaimed() {
        this.outstandingChunks.incrementAndGet();
    }

    final void chunkProcessed() {
        this.outstandingChunks.decrementAndGet();
    }
}
