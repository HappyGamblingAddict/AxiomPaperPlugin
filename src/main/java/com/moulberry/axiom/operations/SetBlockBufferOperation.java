package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.AxiomReflection;
import com.moulberry.axiom.WorldExtension;
import com.moulberry.axiom.buffer.BlockBuffer;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.integration.SectionPermissionChecker;
import com.moulberry.axiom.integration.coreprotect.CoreProtectIntegration;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies a client's block edit buffer.
 *
 * <p>Chunks are handed out one at a time by {@link OperationQueue} and each is applied on its own
 * region, so a paste that crosses region borders is applied by several region threads at the same
 * time. The body of {@link #processChunk} is otherwise unchanged from the original single threaded
 * implementation.
 */
public class SetBlockBufferOperation extends PendingOperation {

    private final ServerPlayer player;
    private final BlockBuffer buffer;
    private final boolean allowNbt;

    /**
     * Captured on the submitting (player) thread so that {@link #shouldLoadChunk} and
     * {@link #processChunk}, which run on the regions that own the chunks, never have to read
     * entity state belonging to a region they do not own.
     */
    private final int playerChunkX;
    private final int playerChunkZ;
    private final int maxChunkLoadDistance;
    private final String playerName;
    private final boolean canUseGameMasterBlocks;
    private final RegistryAccess playerRegistryAccess;

    private final Long2ObjectOpenHashMap<List<Long2ObjectMap.Entry<PalettedContainer<BlockState>>>> sectionsForChunks;
    private final LongArrayList remainingChunks;
    private int claimedChunks = 0;
    private final AtomicBoolean sendGameMasterBlockWarning = new AtomicBoolean();

    public SetBlockBufferOperation(ServerLevel level, ServerPlayer player, BlockBuffer buffer, boolean allowNbt) {
        super(level);

        this.player = player;
        this.buffer = buffer;
        this.allowNbt = allowNbt;

        this.playerChunkX = player.getBlockX() >> 4;
        this.playerChunkZ = player.getBlockZ() >> 4;
        this.maxChunkLoadDistance = AxiomPaper.PLUGIN.getMaxChunkLoadDistance(level.getWorld());
        this.playerName = player.getBukkitEntity().getName();
        this.canUseGameMasterBlocks = player.canUseGameMasterBlocks();
        this.playerRegistryAccess = player.registryAccess();

        this.sectionsForChunks = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<PalettedContainer<BlockState>> entry : buffer.entrySet()) {
            long pos = entry.getLongKey();
            long chunkPos = ChunkPos.pack(BlockPos.getX(pos), BlockPos.getZ(pos));

            List<Long2ObjectMap.Entry<PalettedContainer<BlockState>>> sections = this.sectionsForChunks.get(chunkPos);
            if (sections == null) {
                sections = new ArrayList<>();
                this.sectionsForChunks.put(chunkPos, sections);
            }
            sections.add(entry);
        }

        this.remainingChunks = new LongArrayList(this.sectionsForChunks.keySet());
        this.remainingChunks.sort(LongComparators.NATURAL_COMPARATOR);
    }

    @Override
    public ServerPlayer executor() {
        return this.player;
    }

    @Override
    protected int claimChunks(long[] dest, int max) {
        // remainingChunks is sorted and only ever advanced from the global region, so a cursor is
        // enough and keeps chunk claiming free of list mutation.
        int available = this.remainingChunks.size() - this.claimedChunks;
        int count = Math.min(max, available);
        for (int i = 0; i < count; i++) {
            dest[i] = this.remainingChunks.getLong(this.claimedChunks + i);
        }
        this.claimedChunks += count;
        return count;
    }

    @Override
    public boolean hasUnclaimedChunks() {
        return this.claimedChunks < this.remainingChunks.size();
    }

    @Override
    protected boolean shouldLoadChunk(ServerLevel level, ChunkPos pos) {
        int distance = Math.abs(this.playerChunkX - pos.x()) + Math.abs(this.playerChunkZ - pos.z());
        return distance < this.maxChunkLoadDistance;
    }

    @Override
    protected void processChunk(ServerLevel level, ChunkPos chunkPos, LevelChunk chunk) {
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        WorldExtension extension = WorldExtension.get(level);

        BlockState emptyState = BlockBuffer.EMPTY_STATE;

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

        boolean chunkChanged = false;
        boolean chunkLightChanged = false;

        long chunkPosLong = ChunkPos.pack(chunk.locX, chunk.locZ);
        List<Long2ObjectMap.Entry<PalettedContainer<BlockState>>> sections = this.sectionsForChunks.get(chunkPosLong);
        for (Long2ObjectMap.Entry<PalettedContainer<BlockState>> entry : sections) {
            int cx = BlockPos.getX(entry.getLongKey());
            int cy = BlockPos.getY(entry.getLongKey());
            int cz = BlockPos.getZ(entry.getLongKey());
            PalettedContainer<BlockState> container = entry.getValue();

            if (cy < level.getMinSectionY() || cy > level.getMaxSectionY()) {
                continue;
            }

            SectionPermissionChecker checker = Integration.checkSection(player.getBukkitEntity(), level.getWorld(), cx, cy, cz);
            if (checker != null && checker.noneAllowed()) {
                continue;
            }

            LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(cy));
            boolean hasOnlyAir = section.hasOnlyAir();

            boolean containerMaybeHasPoi = container.maybeHas(PoiTypes::hasPoi);
            boolean sectionMaybeHasPoi = section.maybeHas(PoiTypes::hasPoi);

            Short2ObjectMap<CompressedBlockEntity> blockEntityChunkMap = this.allowNbt ? this.buffer.getBlockEntityChunkMap(entry.getLongKey()) : null;

            int minX = 0;
            int minY = 0;
            int minZ = 0;
            int maxX = 15;
            int maxY = 15;
            int maxZ = 15;

            if (checker != null) {
                minX = checker.bounds().minX();
                minY = checker.bounds().minY();
                minZ = checker.bounds().minZ();
                maxX = checker.bounds().maxX();
                maxY = checker.bounds().maxY();
                maxZ = checker.bounds().maxZ();
                if (checker.allAllowed()) {
                    checker = null;
                }
            }

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockState blockState = container.get(x, y, z);
                        if (blockState == emptyState) continue;

                        int bx = cx*16 + x;
                        int by = cy*16 + y;
                        int bz = cz*16 + z;

                        if (hasOnlyAir && blockState.isAir()) {
                            continue;
                        }

                        if (checker != null && !checker.allowed(x, y, z)) continue;

                        Block block = blockState.getBlock();

                        BlockState old = section.setBlockState(x, y, z, blockState, true);
                        if (blockState != old) {
                            chunkChanged = true;
                            blockPos.set(bx, by, bz);

                            motionBlocking.update(x, by, z, blockState);
                            motionBlockingNoLeaves.update(x, by, z, blockState);
                            oceanFloor.update(x, by, z, blockState);
                            worldSurface.update(x, by, z, blockState);

                            // Update Light
                            chunkLightChanged |= LightEngine.hasDifferentLightProperties(old, blockState);

                            // Remove block entity if block type changes
                            if (!old.is(block) && old.hasBlockEntity() && !blockState.shouldChangedStateKeepBlockEntity(old)) {
                                chunk.removeBlockEntity(blockPos);
                            }

                            // Update Poi
                            Optional<Holder<PoiType>> newPoi = containerMaybeHasPoi ? PoiTypes.forState(blockState) : Optional.empty();
                            Optional<Holder<PoiType>> oldPoi = sectionMaybeHasPoi ? PoiTypes.forState(old) : Optional.empty();
                            if (!Objects.equals(oldPoi, newPoi)) {
                                if (oldPoi.isPresent()) level.getPoiManager().remove(blockPos);
                                if (newPoi.isPresent()) level.getPoiManager().add(blockPos, newPoi.get());
                            }
                        }

                        if (blockState.hasBlockEntity()) {
                            blockPos.set(bx, by, bz);

                            BlockEntity blockEntity = chunk.getBlockEntity(blockPos, LevelChunk.EntityCreationType.CHECK);

                            // Remove old block entity if it isn't valid
                            if (blockEntity != null && !blockEntity.isValidBlockState(blockState)) {
                                chunk.removeBlockEntity(blockPos);
                                blockEntity = null;
                            }

                            if (blockEntity == null) {
                                // There isn't a block entity here, create it!
                                blockEntity = ((EntityBlock)block).newBlockEntity(blockPos, blockState);
                                if (blockEntity != null) {
                                    chunk.addAndRegisterBlockEntity(blockEntity);
                                }
                            } else {
                                // Block entity is here and the type is correct
                                blockEntity.setBlockState(blockState);
                                AxiomReflection.updateBlockEntityTicker(chunk, blockEntity);
                            }

                            if (blockEntity != null && blockEntityChunkMap != null) {
                                if (blockEntity instanceof GameMasterBlock && !canUseGameMasterBlocks) {
                                    sendGameMasterBlockWarning.set(true);
                                } else {
                                    int key = x | (y << 4) | (z << 8);
                                    CompressedBlockEntity savedBlockEntity = blockEntityChunkMap.get((short) key);
                                    if (savedBlockEntity != null) {
                                        var input = TagValueInput.create(ProblemReporter.DISCARDING, playerRegistryAccess, savedBlockEntity.decompress());
                                        blockEntity.loadWithComponents(input);
                                        chunkChanged = true;
                                    }
                                }
                            }
                        }

                        if (CoreProtectIntegration.isEnabled() && old != blockState) {
                            String changedBy = playerName;
                            BlockPos changedPos = new BlockPos(bx, by, bz);

                            CoreProtectIntegration.logRemoval(changedBy, old, level.getWorld(), changedPos);
                            CoreProtectIntegration.logPlacement(changedBy, blockState, level.getWorld(), changedPos);
                        }
                    }
                }
            }

            boolean nowHasOnlyAir = section.hasOnlyAir();
            if (hasOnlyAir != nowHasOnlyAir) {
                level.getChunkSource().getLightEngine().updateSectionStatus(SectionPos.of(cx, cy, cz), nowHasOnlyAir);
                level.getChunkSource().onSectionEmptinessChanged(cx, cy, cz, nowHasOnlyAir);
            }
        }

        if (chunkChanged) {
            extension.sendChunk(chunk.locX, chunk.locZ);
            chunk.markUnsaved();
        }
        if (chunkLightChanged) {
            extension.lightChunk(chunk.locX, chunk.locZ);
        }
    }

    @Override
    protected void complete(ServerLevel level) {
        if (this.sendGameMasterBlockWarning.get()) {
            AxiomScheduler.runOnEntity(this.player.getBukkitEntity(), () -> this.player.sendSystemMessage(
                Component.literal("Unable to set data for Game Master block since you don't have op").withStyle(ChatFormatting.RED)));
        }
    }

}
