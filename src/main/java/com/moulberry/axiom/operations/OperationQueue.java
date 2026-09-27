package com.moulberry.axiom.operations;

import com.moulberry.axiom.scheduler.AxiomScheduler;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives {@link PendingOperation}s, running each chunk of work on the region that owns it.
 *
 * <p>This replaces the previous design, which kept one FIFO per world and advanced it from the
 * global tick. That was only correct while one thread owned every chunk in the game, which is
 * exactly the assumption a regionised server removes. Here the global region only ever does
 * bookkeeping: it asks each operation for a budget of chunk positions, then hands each of those
 * positions to that chunk's region. A paste spanning twenty regions is processed by twenty region
 * threads at once, and no thread ever touches a chunk outside its own region.
 */
public class OperationQueue {

    /**
     * How many chunks a single operation may have in flight at once. Matches the historical
     * {@code MAX_CHUNK_FUTURES} so a large paste is claimed at the same rate as before.
     */
    private static final int MAX_CHUNK_CLAIMS_PER_TICK = 256;

    /** Give up on a chunk that will not load after roughly a second. */
    private static final int MAX_CHUNK_LOAD_ATTEMPTS = 20 * 20;

    private final Set<PendingOperation> operations = ConcurrentHashMap.newKeySet();

    public void add(PendingOperation operation) {
        this.operations.add(operation);
    }

    /**
     * Global region tick. Claims more chunk work for every in-flight operation and dispatches it.
     * Only chunk positions are moved around here, never chunk data.
     */
    public void tick() {
        for (PendingOperation operation : this.operations) {
            this.drive(operation.level(), operation);
        }
    }

    private void drive(ServerLevel level, PendingOperation operation) {
        if (!this.operations.contains(operation)) {
            return;
        }

        // Back pressure: don't let a huge paste enqueue thousands of region tasks at once. The old
        // implementation capped in-flight chunks at MAX_CHUNK_FUTURES for the same reason.
        if (operation.outstandingChunks() >= MAX_CHUNK_CLAIMS_PER_TICK) {
            return;
        }

        // Local rather than shared: add() may be called from a region thread while tick() runs on
        // the global region, and claimChunks writes into this array.
        long[] claims = new long[MAX_CHUNK_CLAIMS_PER_TICK];

        int claimed;
        try {
            claimed = operation.claimChunks(claims, MAX_CHUNK_CLAIMS_PER_TICK);
        } catch (Throwable t) {
            this.fail(operation, t);
            return;
        }

        for (int i = 0; i < claimed; i++) {
            operation.chunkClaimed();
            try {
                this.dispatch(level, operation, ChunkPos.unpack(claims[i]), 0);
            } catch (Throwable t) {
                // The scheduler refused the task (plugin disabled, world unloading, ...). Balance
                // the counter or the operation would never finish and would pin its buffer forever.
                operation.chunkProcessed();
                this.fail(operation, t);
                return;
            }
        }
    }

    /**
     * Hands one chunk to its owning region. {@code attempt} counts how many times we have already
     * gone around waiting for the chunk to finish loading, so the retry lands on a later tick
     * instead of spinning on the same tick.
     */
    private void dispatch(ServerLevel level, PendingOperation operation, ChunkPos pos, int attempt) {
        World world = level.getWorld();
        AxiomScheduler.region(world, pos.x(), pos.z(), () -> {
            if (!this.operations.contains(operation)) {
                operation.chunkProcessed();
                return;
            }

            try {
                LevelChunk chunk = level.getChunkIfLoaded(pos.x(), pos.z());
                if (chunk == null) {
                    if (attempt >= MAX_CHUNK_LOAD_ATTEMPTS || !operation.shouldLoadChunk(level, pos)) {
                        // Out of the allowed load range, or the chunk will not load. Drop just this
                        // piece of work instead of stalling the rest of the operation behind it.
                        operation.chunkProcessed();
                        this.maybeComplete(level, operation);
                        return;
                    }

                    // Kick off (or keep waiting on) the load. The future is deliberately not
                    // inspected here: the retry re-reads the chunk from the owning region instead.
                    world.getChunkAtAsync(pos.x(), pos.z());
                    AxiomScheduler.region(world, pos.x(), pos.z(),
                        () -> this.dispatch(level, operation, pos, attempt + 1), 1L);
                    return;
                }

                operation.processChunk(level, pos, chunk);
                operation.chunkProcessed();
            } catch (Throwable t) {
                operation.chunkProcessed();
                this.fail(operation, t);
                return;
            }

            this.maybeComplete(level, operation);
        });
    }

    private void maybeComplete(ServerLevel level, PendingOperation operation) {
        if (operation.outstandingChunks() > 0) {
            return;
        }

        // Exactly one region thread may finish the operation. Several can be inside this method at
        // once once the last chunks land, and completion sends a network response.
        if (!this.operations.remove(operation)) {
            return;
        }
        if (operation.isFinished()) {
            return;
        }

        try {
            operation.complete(level);
        } catch (Throwable t) {
            this.fail(operation, t);
        }
    }

    private void fail(PendingOperation operation, Throwable t) {
        if (!this.operations.remove(operation)) {
            return;
        }

        try {
            operation.fail(t);
        } catch (Throwable ignored) {
        }

        ServerPlayer executor = operation.executor();
        if (executor != null) {
            AxiomScheduler.runOnEntity(executor.getBukkitEntity(),
                () -> executor.getBukkitEntity().kick(Component.text("An error occurred while processing operation: " + t.getMessage())));
        }
    }

    /** Number of operations currently in flight. Exposed for the debug command. */
    public int size() {
        return this.operations.size();
    }
}
