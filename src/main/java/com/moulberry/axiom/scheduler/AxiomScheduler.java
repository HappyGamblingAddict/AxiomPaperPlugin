package com.moulberry.axiom.scheduler;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Single entry point for every piece of scheduling Axiom does.
 *
 * <p>Axiom is a single logical main-thread plugin, but on Folia "the main thread" is a set of
 * region tick threads. Every method here is built on the regionised scheduler API that Paper also
 * implements (delegating to the main thread), so the same jar runs on Paper, Folia and Puffer
 * without any branching at the call sites.
 *
 * <p>The three rules used throughout the codebase are:
 * <ul>
 *     <li>{@link #global} for bookkeeping that touches no region-owned data.</li>
 *     <li>{@link #region}/{@link #ownsRegion} for block &amp; chunk data.</li>
 *     <li>{@link #entity} for anything that reads or writes entity state.</li>
 * </ul>
 */
public final class AxiomScheduler {

    private static volatile Plugin plugin;

    private AxiomScheduler() {
    }

    public static void init(Plugin owningPlugin) {
        plugin = owningPlugin;
    }

    public static Plugin plugin() {
        Plugin current = plugin;
        if (current == null) {
            throw new IllegalStateException("AxiomScheduler used before AxiomScheduler#init");
        }
        return current;
    }

    /**
     * True when running on a regionised server, where the global region thread is <em>not</em>
     * allowed to touch chunk or entity data.
     *
     * <p>Paper's {@code CraftServer#getName()} is patched to report "Folia" on Folia derivatives.
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    private static final boolean FOLIA = detectFolia();

    private static boolean detectFolia() {
        try {
            return Bukkit.getServer().getName().equals("Folia");
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ global

    public static void global(Runnable run) {
        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
        scheduler.execute(plugin(), run);
    }

    public static ScheduledTask globalAtFixedRate(Runnable run, long initialDelayTicks, long periodTicks) {
        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
        return scheduler.runAtFixedRate(plugin(), task -> run.run(), initialDelayTicks, periodTicks);
    }

    public static boolean isGlobalTickThread() {
        return Bukkit.isGlobalTickThread();
    }

    // ------------------------------------------------------------------ region

    public static void region(Location location, Runnable run) {
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        scheduler.execute(plugin(), location, run);
    }

    public static void region(World world, int chunkX, int chunkZ, Runnable run) {
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        scheduler.execute(plugin(), world, chunkX, chunkZ, run);
    }

    public static void region(ServerLevel level, BlockPos pos, Runnable run) {
        region(level.getWorld(), pos.getX() >> 4, pos.getZ() >> 4, run);
    }

    public static void region(World world, int chunkX, int chunkZ, Runnable run, long delayTicks) {
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        scheduler.runDelayed(plugin(), world, chunkX, chunkZ, task -> run.run(), delayTicks);
    }

    public static ScheduledTask regionAtFixedRate(Location location, Runnable run, long initialDelayTicks, long periodTicks) {
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        return scheduler.runAtFixedRate(plugin(), location, task -> run.run(), initialDelayTicks, periodTicks);
    }

    public static ScheduledTask regionAtFixedRate(World world, int chunkX, int chunkZ, Runnable run, long initialDelayTicks, long periodTicks) {
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        return scheduler.runAtFixedRate(plugin(), world, chunkX, chunkZ, task -> run.run(), initialDelayTicks, periodTicks);
    }

    /** Whether the calling thread is allowed to read/write the chunk containing {@code pos}. */
    public static boolean ownsRegion(ServerLevel level, BlockPos pos) {
        return Bukkit.isOwnedByCurrentRegion(level.getWorld(), pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean ownsRegion(ServerLevel level, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(level.getWorld(), chunkX, chunkZ);
    }

    public static boolean ownsRegion(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static boolean ownsRegion(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    /**
     * Runs {@code run} if the current thread already owns the chunk containing {@code pos},
     * otherwise hands it to that chunk's region. This keeps the common "we are already in the
     * right place" path allocation free and synchronous, which matters because most Axiom edits
     * touch a handful of chunks near the player.
     */
    public static void runInRegion(ServerLevel level, BlockPos pos, Runnable run) {
        if (ownsRegion(level, pos)) {
            run.run();
        } else {
            region(level, pos, run);
        }
    }

    // ------------------------------------------------------------------ entity

    /**
     * Runs {@code run} inline when we already own {@code entity}, otherwise schedules it onto the
     * region the entity currently lives in. Returns false when the task had to be deferred, which
     * the caller may use to fall back to a purely networked response.
     */
    public static boolean runOnEntity(Entity entity, Runnable run) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            run.run();
            return true;
        }
        entity.getScheduler().execute(plugin(), run, null, 0L);
        return false;
    }

    public static boolean ownsEntity(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    /** Returns null if the task was rejected (retired entity, plugin disabled, ...). */
    public static ScheduledTask entityAtFixedRate(Entity entity, Runnable run, long initialDelayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin(), task -> run.run(), null, initialDelayTicks, periodTicks);
    }

    // ------------------------------------------------------------------ async

    public static void async(Runnable run) {
        Bukkit.getAsyncScheduler().runNow(plugin(), task -> run.run());
    }

    public static void cancelAll() {
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin());
            Bukkit.getAsyncScheduler().cancelTasks(plugin());
        } catch (Throwable ignored) {
        }
    }
}
