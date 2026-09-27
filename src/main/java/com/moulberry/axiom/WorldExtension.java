package com.moulberry.axiom;

import com.moulberry.axiom.annotations.ServerAnnotations;
import com.moulberry.axiom.marker.MarkerData;
import com.moulberry.axiom.paperapi.entity.ImplAxiomHiddenEntities;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.*;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-dimension bookkeeping for chunk re-sends, relighting and gizmo markers.
 *
 * <p>Two things here relied on Axiom having a single thread:
 * <ul>
 *     <li>Building a {@link ClientboundLevelChunkWithLightPacket} reads a chunk's sections, its
 *     heightmaps and the level light engine, so it has to happen on the region owning the chunk.
 *     {@link #sendChunk} and {@link #lightChunk} now only record the chunk position, and the global
 *     tick hands the real work to that chunk's region. The drain itself never touches chunk data,
 *     only the two pending sets.</li>
 *     <li>Diffing gizmo markers used to walk {@code ServerLevel#getEntities()}, which is the whole
 *     level on Paper but has no regionised equivalent. Markers are now tracked from
 *     {@code EntityAddToWorldEvent}/{@code EntityRemoveFromWorldEvent} and each one samples itself
 *     on its own entity scheduler, so a marker is only ever read by the region that owns it.</li>
 * </ul>
 */
public class WorldExtension {

    private static final Map<ResourceKey<Level>, WorldExtension> extensions = new ConcurrentHashMap<>();

    public static WorldExtension get(ServerLevel serverLevel) {
        return extensions.computeIfAbsent(serverLevel.dimension(), k -> new WorldExtension()).withLevel(serverLevel);
    }

    private WorldExtension() {
    }

    private WorldExtension withLevel(ServerLevel serverLevel) {
        // Written once per dimension in practice; the volatile is only there so a region thread
        // that happens to be first to touch a dimension cannot observe a null level.
        this.level = serverLevel;
        return this;
    }

    public static void onPlayerJoin(World world, Player player) {
        ServerLevel level = ((CraftWorld)world).getHandle();
        get(level).onPlayerJoin(player);

        if (AxiomPaper.PLUGIN.canUseAxiom(player)) {
            ServerAnnotations.sendAll(world, ((CraftPlayer)player).getHandle());
        }
    }

    /** Global region tick; must not touch chunk or entity data. */
    public static void tick(MinecraftServer server, boolean sendMarkers, int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        // Drop extensions for dimensions that are no longer loaded, otherwise their pending chunk
        // sets and tracked markers are retained forever.
        extensions.keySet().retainAll(server.levelKeys());

        for (ServerLevel level : server.getAllLevels()) {
            get(level).tick(sendMarkers, maxChunkRelightsPerTick, maxChunkSendsPerTick);
        }
    }

    // Written by get() from whichever region first touched this dimension, read by the global tick.
    private volatile ServerLevel level;

    private final LongSet pendingChunksToSend = new LongOpenHashSet();
    private final LongSet pendingChunksToLight = new LongOpenHashSet();
    private final Map<UUID, MarkerData> previousMarkerData = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> trackedMarkers = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> markerSamplers = new ConcurrentHashMap<>();
    private final Queue<UUID> pendingMarkerUpdates = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> pendingMarkerRemovals = new ConcurrentLinkedQueue<>();

    public void sendChunk(int cx, int cz) {
        synchronized (this.pendingChunksToSend) {
            this.pendingChunksToSend.add(ChunkPos.asLong(cx, cz));
        }
    }

    public void lightChunk(int cx, int cz) {
        synchronized (this.pendingChunksToLight) {
            this.pendingChunksToLight.add(ChunkPos.asLong(cx, cz));
        }
    }

    public void onPlayerJoin(Player player) {
        if (!this.previousMarkerData.isEmpty()) {
            List<MarkerData> markerData = new ArrayList<>(this.previousMarkerData.values());

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeCollection(markerData, MarkerData::write);
            buf.writeCollection(Set.<UUID>of(), (buffer, uuid) -> buffer.writeUUID(uuid));

            byte[] bytes = ByteBufUtil.getBytes(buf);
            VersionHelper.sendCustomPayload(player, "axiom:marker_data", bytes);
        }

        try {
            ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
            if (this.level.chunkPacketBlockController.shouldModify(serverPlayer, this.level.getChunkIfLoaded(serverPlayer.blockPosition()))) {
                Component text = Component.text("Axiom: Warning, anti-xray is enabled. This will cause issues when copying blocks. Please turn anti-xray off");
                player.sendMessage(text.color(NamedTextColor.RED));
            }
        } catch (Throwable ignored) {}
    }

    public void tick(boolean sendMarkers, int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        this.drainChunksToSend(maxChunkSendsPerTick);
        this.drainChunksToLight(maxChunkRelightsPerTick);

        if (sendMarkers) {
            this.flushMarkerUpdates();
        }
    }

    private void drainChunksToSend(int maxChunkSendsPerTick) {
        boolean sendAll = maxChunkSendsPerTick <= 0;
        int budget = maxChunkSendsPerTick;

        LongList batch = new LongArrayList();
        synchronized (this.pendingChunksToSend) {
            LongIterator iterator = this.pendingChunksToSend.longIterator();
            while (iterator.hasNext()) {
                long packed = iterator.nextLong();
                if (!sendAll) {
                    iterator.remove();
                }
                batch.add(packed);

                if (!sendAll && --budget <= 0) {
                    break;
                }
            }
            if (sendAll) {
                this.pendingChunksToSend.clear();
            }
        }

        ServerLevel level = this.level;
        for (long packed : batch) {
            ChunkPos pos = new ChunkPos(packed);
            AxiomScheduler.region(level.getWorld(), pos.x, pos.z, () -> sendChunkNow(level, pos));
        }
    }

    private void sendChunkNow(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunkIfLoaded(pos.x, pos.z);
        if (chunk == null) {
            // Not loaded right now: put it back so it is retried later rather than dropping the
            // forced update. The original kept unsent chunks queued for the same reason.
            sendChunk(pos.x, pos.z);
            return;
        }

        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        List<ServerPlayer> players = chunkMap.getPlayers(pos, false);
        if (players.isEmpty()) {
            // No viewers yet, but a player may start looking at this chunk before the next drain.
            // Requeue so the client never ends up with stale blocks.
            sendChunk(pos.x, pos.z);
            return;
        }

        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, false);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }

    private void drainChunksToLight(int maxChunkRelightsPerTick) {
        boolean relightAll = maxChunkRelightsPerTick <= 0;
        int budget = maxChunkRelightsPerTick;

        LongList batch = new LongArrayList();
        synchronized (this.pendingChunksToLight) {
            LongIterator iterator = this.pendingChunksToLight.longIterator();
            while (iterator.hasNext()) {
                long packed = iterator.nextLong();
                if (!relightAll) {
                    iterator.remove();
                }
                batch.add(packed);

                if (!relightAll && --budget <= 0) {
                    break;
                }
            }
            if (relightAll) {
                this.pendingChunksToLight.clear();
            }
        }

        if (batch.isEmpty()) {
            return;
        }

        // Relighting reads and writes section light data for every chunk in the set, so a batch may
        // not straddle regions: each chunk is handed to its own region instead.
        ServerLevel level = this.level;
        for (long packed : batch) {
            ChunkPos pos = new ChunkPos(packed);
            AxiomScheduler.region(level.getWorld(), pos.x, pos.z,
                () -> level.getChunkSource().getLightEngine().starlight$serverRelightChunks(Set.of(pos), chunkPos -> {}, count -> {}));
        }
    }

    // ------------------------------------------------------------------ markers

    public void trackMarker(Entity bukkitMarker) {
        UUID uuid = bukkitMarker.getUniqueId();
        if (this.trackedMarkers.putIfAbsent(uuid, bukkitMarker) != null) {
            return;
        }

        this.pendingMarkerUpdates.add(uuid);

        // Every marker samples itself from its own region, so no region ever has to read another
        // region's entities. Markers are otherwise immutable in vanilla, so a low sample rate is
        // enough to pick up custom data component edits.
        ScheduledTask task = bukkitMarker.getScheduler().runAtFixedRate(AxiomScheduler.plugin(),
            scheduled -> sampleMarker(bukkitMarker), null, 5L, 5L);
        if (task != null) {
            this.markerSamplers.put(uuid, task);
        }
    }

    public void untrackMarker(Entity bukkitMarker) {
        UUID uuid = bukkitMarker.getUniqueId();
        this.trackedMarkers.remove(uuid);
        this.pendingMarkerUpdates.remove(uuid);
        this.pendingMarkerRemovals.add(uuid);

        ScheduledTask task = this.markerSamplers.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private void sampleMarker(Entity bukkitMarker) {
        if (!this.trackedMarkers.containsKey(bukkitMarker.getUniqueId())) {
            return;
        }

        UUID uuid = bukkitMarker.getUniqueId();
        if (ImplAxiomHiddenEntities.isMarkerHidden((org.bukkit.entity.Marker) bukkitMarker)) {
            return;
        }

        MarkerData currentData;
        try {
            currentData = MarkerData.createFrom((Marker) ((CraftEntity) bukkitMarker).getHandle());
        } catch (Throwable ignored) {
            return;
        }

        // Re-check after the (potentially slow) read: the marker may have been untracked while we
        // were sampling it, and publishing a removal alongside an update would leave the client with
        // both entries for the same gizmo.
        if (!this.trackedMarkers.containsKey(uuid)) {
            return;
        }

        if (!Objects.equals(currentData, this.previousMarkerData.get(uuid))) {
            this.previousMarkerData.put(uuid, currentData);
            this.pendingMarkerUpdates.add(uuid);
        }
    }

    private void flushMarkerUpdates() {
        if (this.pendingMarkerUpdates.isEmpty() && this.pendingMarkerRemovals.isEmpty()) {
            return;
        }

        // Drain with remove() rather than iterating then clearing: a marker on another region can be
        // sampled between the two, and clear() would drop that update permanently because
        // previousMarkerData has already been updated.
        List<MarkerData> changedData = new ArrayList<>();
        UUID uuid;
        while ((uuid = this.pendingMarkerUpdates.poll()) != null) {
            MarkerData data = this.previousMarkerData.get(uuid);
            if (data != null && !this.pendingMarkerRemovals.contains(uuid)) {
                changedData.add(data);
            }
        }

        Set<UUID> removed = new HashSet<>();
        while ((uuid = this.pendingMarkerRemovals.poll()) != null) {
            this.previousMarkerData.remove(uuid);
            removed.add(uuid);
        }

        if (changedData.isEmpty() && removed.isEmpty()) {
            return;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeCollection(changedData, MarkerData::write);
        buf.writeCollection(removed, (buffer, removedUuid) -> buffer.writeUUID(removedUuid));
        byte[] bytes = ByteBufUtil.getBytes(buf);

        // Read the server-wide player list rather than ServerLevel#players(): the latter walks the
        // level's entity list, which is not safe to touch from the global region.
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (player.level() == this.level && AxiomPaper.PLUGIN.canUseAxiom(player.getBukkitEntity())) {
                players.add(player);
            }
        }

        VersionHelper.sendCustomPayloadToAll(players, "axiom:marker_data", bytes);
    }

}
