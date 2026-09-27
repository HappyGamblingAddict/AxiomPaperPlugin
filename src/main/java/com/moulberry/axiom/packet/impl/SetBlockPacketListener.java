package com.moulberry.axiom.packet.impl;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.AxiomReflection;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.integration.coreprotect.CoreProtectIntegration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

public class SetBlockPacketListener implements PacketHandler {

    public static final int REASON_REPLACEMODE = 1;
    public static final int REASON_ANGEL = 128;

    private final AxiomPaper plugin;

    public SetBlockPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public static class AxiomPlacingCraftBlockState extends CraftBlockState {
        public AxiomPlacingCraftBlockState(@Nullable World world, BlockPos blockPosition, BlockState blockData) {
            super(world, blockPosition, blockData);
        }
    }

    @Override
    public void onReceive(Player bukkitPlayer, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(bukkitPlayer, AxiomPermission.BUILD_PLACE)) {
            return;
        }

        if (!this.plugin.canModifyWorld(bukkitPlayer, bukkitPlayer.getWorld())) {
            return;
        }

        // Read packet
        IntFunction<Map<BlockPos, BlockState>> mapFunction = this.plugin.limitCollection(Maps::newLinkedHashMapWithExpectedSize);
        IdMapper<BlockState> registry = this.plugin.getBlockRegistry(bukkitPlayer.getUniqueId());
        Map<BlockPos, BlockState> blocks = friendlyByteBuf.readMap(mapFunction,
                buf -> buf.readBlockPos(), buf -> buf.readById(registry::byIdOrThrow));
        boolean updateNeighbors = friendlyByteBuf.readBoolean();
        Set<BlockPos> preventUpdatesAt = Set.of();
        if (updateNeighbors) {
            IntFunction<Set<BlockPos>> setFunction = this.plugin.limitCollection(Sets::newHashSetWithExpectedSize);
            preventUpdatesAt = friendlyByteBuf.readCollection(setFunction, buf -> buf.readBlockPos());
        }

        if (this.plugin.logLargeBlockBufferChanges() && blocks.size() > 64) {
            this.plugin.getLogger().info("Player " + bukkitPlayer.getUniqueId() + " modified " + blocks.size() + " individual blocks with axiom");
        }

        int reason = friendlyByteBuf.readVarInt();
        boolean breaking = friendlyByteBuf.readBoolean();
        BlockHitResult blockHit = friendlyByteBuf.readBlockHitResult();
        InteractionHand hand = friendlyByteBuf.readEnum(InteractionHand.class);
        int sequenceId = friendlyByteBuf.readVarInt();

        ServerPlayer player = ((CraftPlayer)bukkitPlayer).getHandle();
        CraftWorld world = player.level().getWorld();

        if (sequenceId >= 0) {
            player.connection.ackBlockChangesUpTo(sequenceId);
        }

        BlockPlaceContext blockPlaceContext = new BlockPlaceContext(player, hand, player.getItemInHand(hand), blockHit);

        if ((reason & REASON_REPLACEMODE) == 0 && (reason & REASON_ANGEL) == 0) {
            if (!fireBukkitEvents(bukkitPlayer, blockHit, breaking, blocks, player, world, hand)) {
                return;
            }
        }

        // A single Axiom edit regularly spans several chunks, and each chunk can be owned by a
        // different region. Group by chunk and hand every group to its own region.
        //
        // The neighbour-updating pass and the suppressed "no updates" pass are dispatched as two
        // separate waves over all chunks: the original single threaded code applied every
        // neighbour-updating block first and only then the suppressed ones, and setWithoutUpdates
        // depends on that ordering so it sees the final state of the blocks placed around it.
        Map<Long, List<Map.Entry<BlockPos, BlockState>>> blocksPerChunk = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            blocksPerChunk.computeIfAbsent(chunkKey(entry.getKey()), k -> new ArrayList<>()).add(entry);
        }

        // Captured here, on the player's own region: the per-chunk region tasks below may not own
        // the player, and CoreProtect logging is not region safe.
        final String actorName = bukkitPlayer.getName();

        // Update blocks
        if (updateNeighbors && preventUpdatesAt.isEmpty()) {
            dispatchPerChunk(world, blocksPerChunk, chunkBlocks -> {
                for (Map.Entry<BlockPos, BlockState> entry : chunkBlocks) {
                    applyBlock(bukkitPlayer, actorName, player, world, entry.getKey(), entry.getValue(), 3);
                }
            }, bukkitPlayer);
        } else if (updateNeighbors) {
            // preventUpdatesAt is consulted for the neighbours of every block, and a neighbour can
            // live in a different chunk than the block itself, so every region task needs the whole
            // set rather than just its own chunk's slice.
            Set<BlockPos> preventAt = preventUpdatesAt;

            // Wave 1: place everything that does get neighbour updates.
            dispatchPerChunk(world, blocksPerChunk, chunkBlocks -> {
                Direction[] directions = Direction.values();
                BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
                for (Map.Entry<BlockPos, BlockState> entry : chunkBlocks) {
                    BlockPos blockPos = entry.getKey();
                    BlockState blockState = entry.getValue();

                    if (!canBreakOrPlace(bukkitPlayer, blockState, world, blockPos)) {
                        continue;
                    }

                    // Check if we have a neighbor that shouldn't receive updates
                    // Unfortunately this will also prevent updates to ALL the other neighbors,
                    // but that case is rare enough for it not to matter
                    boolean updateNeighborsForThisBlock = true;
                    for (Direction direction : directions) {
                        if (preventAt.contains(mutable.setWithOffset(blockPos, direction))) {
                            updateNeighborsForThisBlock = false;
                            break;
                        }
                    }

                    if (preventAt.contains(blockPos) && !updateNeighborsForThisBlock) {
                        continue; // fully suppressed, handled in wave 2
                    }

                    applyBlock(bukkitPlayer, actorName, player, world, blockPos, blockState, updateNeighborsForThisBlock ? 3 : 18);
                }
            }, bukkitPlayer);

            // Wave 2: the suppressed blocks. Region tasks are always tick deferred, so within one
            // chunk wave 2 is ordered after wave 1, but across regions the two run in parallel and
            // a boundary block may observe its neighbour before wave 1 has landed.
            dispatchPerChunk(world, blocksPerChunk, chunkBlocks -> {
                for (Map.Entry<BlockPos, BlockState> entry : chunkBlocks) {
                    if (preventAt.contains(entry.getKey())) {
                        setWithoutUpdates(bukkitPlayer, actorName, entry.getValue(), world, entry.getKey(), player);
                    }
                }
            }, bukkitPlayer);
        } else {
            dispatchPerChunk(world, blocksPerChunk, chunkBlocks -> {
                for (Map.Entry<BlockPos, BlockState> entry : chunkBlocks) {
                    setWithoutUpdates(bukkitPlayer, actorName, entry.getValue(), world, entry.getKey(), player);
                }
            }, bukkitPlayer);
        }

        if (!breaking) {
            BlockPos clickedPos = blockPlaceContext.getClickedPos();

            if (blocks.containsKey(clickedPos)) {
                // Read the held item here, on the player's own region, rather than inside the
                // chunk's region task, which may not own the player.
                ItemStack inHand = player.getItemInHand(hand);

                AxiomScheduler.runInRegion(player.level(), clickedPos,
                    () -> finishPlacement(bukkitPlayer, player, world, inHand, blocks, clickedPos));
            }
        }
    }

    private static long chunkKey(BlockPos pos) {
        return net.minecraft.world.level.ChunkPos.asLong(pos.getX(), pos.getZ());
    }

    /** Runs {@code body} once per chunk, on the region that owns that chunk. */
    private void dispatchPerChunk(CraftWorld world, Map<Long, List<Map.Entry<BlockPos, BlockState>>> blocksPerChunk,
                                  java.util.function.Consumer<List<Map.Entry<BlockPos, BlockState>>> body, Player bukkitPlayer) {
        for (List<Map.Entry<BlockPos, BlockState>> chunkBlocks : blocksPerChunk.values()) {
            if (chunkBlocks.isEmpty()) continue;
            BlockPos first = chunkBlocks.get(0).getKey();
            AxiomScheduler.region(world, first.getX() >> 4, first.getZ() >> 4, () -> {
                try {
                    body.accept(chunkBlocks);
                } catch (Throwable t) {
                    // This runs on a region that may not own the player, and kicking is an entity
                    // mutation, so bounce it onto the player's own region.
                    AxiomScheduler.runOnEntity(bukkitPlayer,
                        () -> bukkitPlayer.kick(net.kyori.adventure.text.Component.text("An error occured while placing blocks: " + t.getMessage())));
                }
            });
        }
    }

    /** Runs on the region owning {@code clickedPos}. */
    private void finishPlacement(Player bukkitPlayer, ServerPlayer player, CraftWorld world, ItemStack inHand, Map<BlockPos, BlockState> blocks, BlockPos clickedPos) {
        try {
            // Disallow in unloaded chunks
            if (!player.level().isLoaded(clickedPos)) {
                return;
            }

            BlockState desiredBlockState = blocks.get(clickedPos);
            BlockState actualBlockState = player.level().getBlockState(clickedPos);
            Block actualBlock = actualBlockState.getBlock();

            // Ensure block is correct
            if (desiredBlockState == null || desiredBlockState.isAir() || actualBlockState.isAir()) return;
            if (desiredBlockState.getBlock() != actualBlock) return;

            // Check plot squared
            if (!Integration.canPlaceBlock(bukkitPlayer, new Location(world, clickedPos.getX(), clickedPos.getY(), clickedPos.getZ()))) {
                return;
            }

            BlockItem.updateCustomBlockEntityTag(player.level(), player, clickedPos, inHand);

            BlockEntity blockEntity = player.level().getBlockEntity(clickedPos);
            if (blockEntity != null) {
                blockEntity.applyComponentsFromItemStack(inHand);
            }

            if (!(actualBlock instanceof BedBlock) && !(actualBlock instanceof DoublePlantBlock) && !(actualBlock instanceof DoorBlock)) {
                actualBlock.setPlacedBy(player.level(), clickedPos, actualBlockState, player, inHand);
            }
        } catch (Throwable t) {
            // This may run on a region that does not own the player, and kicking is an entity
            // mutation, so bounce it onto the player's own region.
            AxiomScheduler.runOnEntity(bukkitPlayer,
                () -> bukkitPlayer.kick(net.kyori.adventure.text.Component.text("An error occured while finishing block placement: " + t.getMessage())));
        }
    }

    private static boolean fireBukkitEvents(Player bukkitPlayer, BlockHitResult blockHit, boolean breaking, Map<BlockPos, BlockState> blocks, ServerPlayer player, CraftWorld world, InteractionHand hand) {
        org.bukkit.inventory.ItemStack heldItem;
        if (hand == InteractionHand.MAIN_HAND) {
            heldItem = bukkitPlayer.getInventory().getItemInMainHand();
        } else {
            heldItem = bukkitPlayer.getInventory().getItemInOffHand();
        }

        org.bukkit.block.Block blockClicked = bukkitPlayer.getWorld().getBlockAt(blockHit.getBlockPos().getX(),
            blockHit.getBlockPos().getY(), blockHit.getBlockPos().getZ());

        BlockFace blockFace = CraftBlock.notchToBlockFace(blockHit.getDirection());

        // Call interact event
        PlayerInteractEvent playerInteractEvent = new PlayerInteractEvent(bukkitPlayer,
            breaking ? Action.LEFT_CLICK_BLOCK : Action.RIGHT_CLICK_BLOCK, heldItem, blockClicked, blockFace);
        if (!playerInteractEvent.callEvent()) {
            return false;
        }

        // Call BlockMultiPlace / BlockPlace event
        if (!breaking) {
            List<org.bukkit.block.BlockState> blockStates = new ArrayList<>();
            boolean anyForeign = false;
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                // Only describe blocks this thread may read: the event has to be constructed and
                // fired on one thread, and on a regionised server that is the player's region.
                if (!AxiomScheduler.ownsRegion(world, entry.getKey().getX() >> 4, entry.getKey().getZ() >> 4)) {
                    anyForeign = true;
                    continue;
                }
                BlockState existing = player.level().getBlockState(entry.getKey());
                if (existing.canBeReplaced()) {
                    blockStates.add(new AxiomPlacingCraftBlockState(world, entry.getKey(), entry.getValue()));
                }
            }

            if (blockStates.isEmpty() && anyForeign && !blocks.isEmpty()) {
                // Every block is in a region this thread may not read, so nothing could be
                // described above. Fire anyway (with the clicked position, which needs no world
                // read) rather than firing no place event at all, which would let a client edit
                // entirely outside its own region without protection plugins ever seeing it.
                BlockPos clickedPos = blockHit.getBlockPos();
                BlockState clickedState = blocks.get(clickedPos);
                if (clickedState != null) {
                    blockStates.add(new AxiomPlacingCraftBlockState(world, clickedPos, clickedState));
                }
            }

            if (!blockStates.isEmpty()) {
                Cancellable event;
                if (blockStates.size() > 1) {
                    event = CraftEventFactory.callBlockMultiPlaceEvent(player.level(),
                        player, hand, blockStates, blockHit.getBlockPos());
                } else {
                    event = CraftEventFactory.callBlockPlaceEvent(player.level(),
                        player, hand, blockStates.get(0), blockHit.getBlockPos());
                }
                if (event.isCancelled()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Runs on the region owning {@code blockPos}. */
    private void applyBlock(Player bukkitPlayer, String actorName, ServerPlayer player, CraftWorld world, BlockPos blockPos, BlockState blockState, int flags) {
        boolean logPlacement = false;

        if (CoreProtectIntegration.isEnabled()) {
            BlockState old = player.level().getBlockState(blockPos);
            if (old != blockState) {
                CoreProtectIntegration.logRemoval(actorName, old, world, blockPos);
                logPlacement = true;
            }
        }

        player.level().setBlock(blockPos, blockState, flags);

        if (logPlacement) {
            CoreProtectIntegration.logPlacement(actorName, blockState, world, blockPos);
        }
    }

    private void setWithoutUpdates(Player bukkitPlayer, String actorName, BlockState blockState, CraftWorld world, BlockPos blockPos, ServerPlayer player) {
        if (!canBreakOrPlace(bukkitPlayer, blockState, world, blockPos)) {
            return;
        }

        int bx = blockPos.getX();
        int by = blockPos.getY();
        int bz = blockPos.getZ();
        int x = bx & 0xF;
        int y = by & 0xF;
        int z = bz & 0xF;
        int cx = bx >> 4;
        int cy = by >> 4;
        int cz = bz >> 4;

        ServerLevel level = player.level();
        LevelChunk chunk = level.getChunkIfLoaded(cx, cz);
        if (chunk == null) return;
        chunk.markUnsaved();

        int sectionIndex = level.getSectionIndexFromSectionY(cy);
        if (sectionIndex < 0 || sectionIndex >= level.getSectionsCount()) return;

        LevelChunkSection section = chunk.getSection(sectionIndex);
        boolean hasOnlyAir = section.hasOnlyAir();

        Heightmap worldSurface = null;
        Heightmap oceanFloor = null;
        Heightmap motionBlocking = null;
        Heightmap motionBlockingNoLeaves = null;
        for (Map.Entry<Heightmap.Types, Heightmap> heightmap : chunk.getHeightmaps()) {
            switch (heightmap.getKey()) {
                case WORLD_SURFACE -> worldSurface = heightmap.getValue();
                case OCEAN_FLOOR -> oceanFloor = heightmap.getValue();
                case MOTION_BLOCKING -> motionBlocking = heightmap.getValue();
                case MOTION_BLOCKING_NO_LEAVES -> motionBlockingNoLeaves = heightmap.getValue();
                default -> {}
            }
        }

        BlockState old = section.setBlockState(x, y, z, blockState, true);
        if (blockState != old) {
            Block block = blockState.getBlock();
            motionBlocking.update(x, by, z, blockState);
            motionBlockingNoLeaves.update(x, by, z, blockState);
            oceanFloor.update(x, by, z, blockState);
            worldSurface.update(x, by, z, blockState);

            if (blockState.hasBlockEntity()) {
                BlockEntity blockEntity = chunk.getBlockEntity(blockPos, LevelChunk.EntityCreationType.CHECK);

                if (blockEntity == null) {
                    // There isn't a block entity here, create it!
                    blockEntity = ((EntityBlock)block).newBlockEntity(blockPos, blockState);
                    if (blockEntity != null) {
                        chunk.addAndRegisterBlockEntity(blockEntity);
                    }
                } else if (blockEntity.getType().isValid(blockState)) {
                    // Block entity is here and the type is correct
                    // Just update the state and ticker and move on
                    blockEntity.setBlockState(blockState);
                    AxiomReflection.updateBlockEntityTicker(chunk, blockEntity);
                } else {
                    // Block entity type isn't correct, we need to recreate it
                    chunk.removeBlockEntity(blockPos);

                    blockEntity = ((EntityBlock)block).newBlockEntity(blockPos, blockState);
                    if (blockEntity != null) {
                        chunk.addAndRegisterBlockEntity(blockEntity);
                    }
                }
            } else if (old.hasBlockEntity()) {
                chunk.removeBlockEntity(blockPos);
            }

            // Mark block changed
            level.getChunkSource().blockChanged(blockPos);

            // Update Light
            if (LightEngine.hasDifferentLightProperties(old, blockState)) {
                // Note: Skylight Sources not currently needed on Paper due to Starlight
                // This might change in the future, so be careful!
                // chunk.getSkyLightSources().update(chunk, x, by, z);
                level.getChunkSource().getLightEngine().checkBlock(blockPos);
            }

            // Update Poi
            Optional<Holder<PoiType>> newPoi = PoiTypes.forState(blockState);
            Optional<Holder<PoiType>> oldPoi = PoiTypes.forState(old);
            if (!Objects.equals(oldPoi, newPoi)) {
                if (oldPoi.isPresent()) level.getPoiManager().remove(blockPos);
                if (newPoi.isPresent()) level.getPoiManager().add(blockPos, newPoi.get());
            }

            if (CoreProtectIntegration.isEnabled()) {
                String changedBy = actorName;
                BlockPos changedPos = new BlockPos(bx, by, bz);

                CoreProtectIntegration.logRemoval(changedBy, old, world, changedPos);
                CoreProtectIntegration.logPlacement(changedBy, blockState, world, changedPos);
            }
        }

        boolean nowHasOnlyAir = section.hasOnlyAir();
        if (hasOnlyAir != nowHasOnlyAir) {
            level.getChunkSource().getLightEngine().updateSectionStatus(SectionPos.of(cx, cy, cz), nowHasOnlyAir);
            level.getChunkSource().onSectionEmptinessChanged(cx, cy, cz, nowHasOnlyAir);
        }
    }

    private static boolean canBreakOrPlace(Player bukkitPlayer, BlockState blockState, CraftWorld world, BlockPos blockPos) {
        if (blockState == null) {
            return false;
        }
        if (!world.isChunkLoaded(blockPos.getX() >> 4, blockPos.getZ() >> 4)) {
            return false;
        }
        if (blockState.isAir()) {
            return Integration.canBreakBlock(bukkitPlayer, world.getBlockAt(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
        } else {
            return Integration.canPlaceBlock(bukkitPlayer, new Location(world, blockPos.getX(), blockPos.getY(), blockPos.getZ()));
        }
    }

}
