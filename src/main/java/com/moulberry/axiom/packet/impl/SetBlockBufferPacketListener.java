package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.BiomeBuffer;
import com.moulberry.axiom.buffer.BlockBuffer;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.operations.SetBlockBufferOperation;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SetBlockBufferPacketListener implements PacketHandler {

    private record BiomeChange(int x, int y, int z, Holder<Biome> biome) { }

    private final AxiomPaper plugin;

    public SetBlockBufferPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handleAsync() {
        return true;
    }

    public void onReceive(Player player, FriendlyByteBuf friendlyByteBuf) {
        ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
        MinecraftServer server = serverPlayer.level().getServer();
        if (server == null) return;

        ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        friendlyByteBuf.readUUID(); // Discard, we don't need to associate buffers

        byte type = friendlyByteBuf.readByte();
        if (type == 0) {
            BlockBuffer buffer = BlockBuffer.load(friendlyByteBuf, this.plugin.getBlockRegistry(serverPlayer.getUUID()), serverPlayer.getBukkitEntity());
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBlockBuffer(serverPlayer, server, buffer, worldKey, clientAvailableDispatchSends);
        } else if (type == 1) {
            BiomeBuffer buffer = BiomeBuffer.load(friendlyByteBuf);
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBiomeBuffer(serverPlayer, server, buffer, worldKey, clientAvailableDispatchSends);
        } else {
            throw new RuntimeException("Unknown buffer type: " + type);
        }
    }

    /**
     * Error paths here can run on a region that does not own the player, and kicking is an entity
     * mutation, so it is bounced onto the player's own region.
     */
    private static void kickOnPlayer(ServerPlayer player, String message) {
        AxiomScheduler.runOnEntity(player.getBukkitEntity(), () -> player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text(message)));
    }

    private void applyBlockBuffer(ServerPlayer player, MinecraftServer server, BlockBuffer buffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        // The checks below read entity state (permissions) and the player's world, so they stay on
        // the player's own region. The block writes themselves go to the operation queue, which
        // applies each chunk on the region that owns it.
        try {
            if (this.plugin.logLargeBlockBufferChanges()) {
                this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getSectionCount() + " chunk sections (blocks)");
                if (buffer.getTotalBlockEntities() > 0) {
                    this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getTotalBlockEntities() + " block entities, compressed bytes = " +
                        buffer.getTotalBlockEntityBytes());
                }
            }

            if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), buffer.getSectionCount(), clientAvailableDispatchSends)) {
                return;
            }

            if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                return;
            }

            ServerLevel world = player.level();
            if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                return;
            }

            boolean allowNbt = this.plugin.hasPermission(player.getBukkitEntity(), AxiomPermission.BUILD_NBT);
            this.plugin.addPendingOperation(world, new SetBlockBufferOperation(world, player, buffer, allowNbt));
        } catch (Throwable t) {
            kickOnPlayer(player, "An error occured while processing block change: " + t.getMessage());;
        }
    }

    private void applyBiomeBuffer(ServerPlayer player, MinecraftServer server, BiomeBuffer biomeBuffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        try {
            if (this.plugin.logLargeBlockBufferChanges()) {
                this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + biomeBuffer.getSectionCount() + " chunk sections (biomes)");
            }

            if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), biomeBuffer.getSectionCount(), clientAvailableDispatchSends)) {
                return;
            }

            if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                return;
            }

            ServerLevel world = player.level();
            if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                return;
            }

            int minSection = world.getMinSectionY();
            int maxSection = world.getMaxSectionY();

            Optional<Registry<Biome>> registryOptional = world.registryAccess().lookup(Registries.BIOME);
            if (registryOptional.isEmpty()) return;

            Registry<Biome> registry = registryOptional.get();

            // Group by chunk: a biome paint routinely spans many chunks which may each be owned by a
            // different region, so the work is dispatched per chunk below.
            Map<ChunkPos, List<BiomeChange>> changesPerChunk = new HashMap<>();

            biomeBuffer.forEachEntry((x, y, z, biome) -> {
                int cy = y >> 2;
                if (cy < minSection || cy > maxSection) {
                    return;
                }

                var holder = registry.get(biome);
                if (holder.isPresent()) {
                    changesPerChunk.computeIfAbsent(new ChunkPos(x >> 2, z >> 2), k -> new ArrayList<>())
                        .add(new BiomeChange(x, y, z, holder.get()));
                }
            });

            for (Map.Entry<ChunkPos, List<BiomeChange>> entry : changesPerChunk.entrySet()) {
                ChunkPos chunkPos = entry.getKey();
                List<BiomeChange> changes = entry.getValue();

                AxiomScheduler.region(world.getWorld(), chunkPos.x(), chunkPos.z(), () -> {
                    try {
                        applyBiomesToChunk(player, world, chunkPos, changes);
                    } catch (Throwable t) {
                        kickOnPlayer(player, "An error occured while processing biome change: " + t.getMessage());;
                    }
                });
            }
        } catch (Throwable t) {
            kickOnPlayer(player, "An error occured while processing biome change: " + t.getMessage());;
        }
    }

    /** Runs on the region that owns {@code chunkPos}. */
    private void applyBiomesToChunk(ServerPlayer player, ServerLevel world, ChunkPos chunkPos, List<BiomeChange> changes) {
        int minSection = world.getMinSectionY();
        LevelChunk chunk = (LevelChunk) world.getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, false);
        if (chunk == null) return;

        boolean changed = false;

        for (BiomeChange change : changes) {
            var section = chunk.getSection((change.y() >> 2) - minSection);
            PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) section.getBiomes();

            if (!Integration.canPlaceBlock(player.getBukkitEntity(),
                new Location(world.getWorld(), (change.x() << 2) + 1, (change.y() << 2) + 1, (change.z() << 2) + 1))) {
                continue;
            }

            container.set(change.x() & 3, change.y() & 3, change.z() & 3, change.biome());
            changed = true;
        }

        if (!changed) {
            return;
        }

        chunk.markUnsaved();

        for (ServerPlayer viewer : world.getChunkSource().chunkMap.getPlayers(chunkPos, false)) {
            viewer.connection.send(ClientboundChunksBiomesPacket.forChunks(List.of(chunk)));
        }
    }

}
