package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.packet.impl.RequestChunkDataPacketListener;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams the contents of requested chunks back to a player.
 *
 * <p>Chunk contents are read on the region that owns each chunk, so a request spanning several
 * regions is served in parallel. Section palettes and block entity NBT are serialised on the owning
 * region rather than kept as live references, because the response is assembled once every chunk is
 * done and that can happen from a different region than any single chunk.
 */
public class RequestChunksOperation extends PendingOperation {

    private final ServerPlayer serverPlayer;
    private final long id;

    private final int playerChunkX;
    private final int playerChunkZ;
    private final int maxChunkLoadDistance;

    private final LongArrayList remainingChunks;
    private int claimedChunks = 0;

    private final Long2ObjectMap<LongList> sendBlockEntityForPendingChunks;
    private final Long2ObjectMap<IntList> sendSectionsForPendingChunks;
    private final boolean sendBlockEntitiesInChunks;

    /** Serialised section palettes keyed by position. */
    private final Map<Long, byte[]> sections = new ConcurrentHashMap<>();
    /** Section positions that are entirely air and therefore have nothing to send. */
    private final Set<Long> airSections = ConcurrentHashMap.newKeySet();
    private final Map<Long, CompressedBlockEntity> blockEntities = new ConcurrentHashMap<>();

    public RequestChunksOperation(ServerLevel level, ServerPlayer serverPlayer, long id, LongSet chunkFutures,
                                  Long2ObjectMap<LongList> sendBlockEntityForPendingChunks,
                                  Long2ObjectMap<IntList> sendSectionsForPendingChunks, boolean sendBlockEntitiesInChunks,
                                  Long2ObjectMap<byte[]> alreadyReadSections,
                                  Long2ObjectMap<CompressedBlockEntity> alreadyReadBlockEntities) {
        super(level);

        this.serverPlayer = serverPlayer;
        this.id = id;
        this.sendBlockEntityForPendingChunks = sendBlockEntityForPendingChunks;
        this.sendSectionsForPendingChunks = sendSectionsForPendingChunks;
        this.sendBlockEntitiesInChunks = sendBlockEntitiesInChunks;

        // The caller already read everything living in a region it owns. Carry that over so the
        // final response is the union of both sources rather than just the operation's own output.
        for (Long2ObjectMap.Entry<byte[]> entry : alreadyReadSections.long2ObjectEntrySet()) {
            if (entry.getValue() == null) {
                this.airSections.add(entry.getLongKey());
            } else {
                this.sections.put(entry.getLongKey(), entry.getValue());
            }
        }
        this.blockEntities.putAll(alreadyReadBlockEntities);

        this.playerChunkX = serverPlayer.getBlockX() >> 4;
        this.playerChunkZ = serverPlayer.getBlockZ() >> 4;
        this.maxChunkLoadDistance = AxiomPaper.PLUGIN.getMaxChunkLoadDistance(level.getWorld());

        this.remainingChunks = new LongArrayList(chunkFutures);
        this.remainingChunks.unstableSort(LongComparators.NATURAL_COMPARATOR);
    }

    @Override
    public ServerPlayer executor() {
        return this.serverPlayer;
    }

    @Override
    protected int claimChunks(long[] dest, int max) {
        // remainingChunks is sorted and only advanced from the global region, so a cursor is
        // enough and keeps claiming allocation free.
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
    protected void processChunk(ServerLevel level, ChunkPos pos, LevelChunk chunk) {
        // CompressedBlockEntity.compress() uses the stream purely as scratch space, so concurrent
        // regions each need their own.
        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        long chunkPosLong = ChunkPos.pack(chunk.locX, chunk.locZ);

        LongList blockEntitiesInChunk = this.sendBlockEntityForPendingChunks.get(chunkPosLong);
        if (blockEntitiesInChunk != null) {
            var blockEntityIterator = blockEntitiesInChunk.longIterator();
            while (blockEntityIterator.hasNext()) {
                long blockEntityPos = blockEntityIterator.nextLong();
                mutableBlockPos.set(blockEntityPos);

                BlockEntity blockEntity = chunk.getBlockEntity(mutableBlockPos, LevelChunk.EntityCreationType.CHECK);
                if (blockEntity != null) {
                    CompoundTag tag = blockEntity.saveWithoutMetadata(this.serverPlayer.registryAccess());
                    this.blockEntities.put(blockEntityPos, CompressedBlockEntity.compress(tag, scratch));
                }
            }
        }

        IntList sendSectionsInChunk = this.sendSectionsForPendingChunks.get(chunkPosLong);
        if (sendSectionsInChunk == null) {
            return;
        }

        boolean hasNonAirSectionInChunk = false;

        var sectionIterator = sendSectionsInChunk.intIterator();
        while (sectionIterator.hasNext()) {
            int sy = sectionIterator.nextInt();

            int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) continue;
            LevelChunkSection section = chunk.getSection(sectionIndex);

            long sectionKey = BlockPos.asLong(chunk.locX, sy, chunk.locZ);
            if (section.hasOnlyAir()) {
                this.airSections.add(sectionKey);
            } else {
                hasNonAirSectionInChunk = true;

                // Serialised here, on the owning region: a PalettedContainer is a live view into the
                // chunk and must not be read again after this task returns.
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                section.getStates().write(buf);
                this.sections.put(sectionKey, ByteBufUtil.getBytes(buf));
            }
        }

        if (!this.sendBlockEntitiesInChunks || !hasNonAirSectionInChunk) {
            return;
        }

        Set<Map.Entry<BlockPos, BlockEntity>> entrySet = chunk.blockEntities.entrySet();
        Iterator<Map.Entry<BlockPos, BlockEntity>> iterator;
        if (entrySet instanceof Object2ObjectMap.FastEntrySet fastEntrySet) {
            iterator = fastEntrySet.fastIterator();
        } else {
            iterator = entrySet.iterator();
        }

        while (iterator.hasNext()) {
            Map.Entry<BlockPos, BlockEntity> entry = iterator.next();

            BlockPos blockPos = entry.getKey();
            int sectionY = blockPos.getY() >> 4;
            if (!sendSectionsInChunk.contains(sectionY)) {
                continue;
            }

            CompoundTag tag = entry.getValue().saveWithoutMetadata(this.serverPlayer.registryAccess());
            this.blockEntities.put(blockPos.asLong(), CompressedBlockEntity.compress(tag, scratch));
        }
    }

    @Override
    protected void complete(ServerLevel level) {
        // Claim the finish before deferring to the player's region, so a second region thread that
        // also observes zero outstanding chunks cannot send a duplicate response.
        if (!markFinished()) {
            return;
        }

        Long2ObjectOpenHashMap<byte[]> responseSections = new Long2ObjectOpenHashMap<>(this.sections);
        for (Long airSection : this.airSections) {
            responseSections.put(airSection, null);
        }

        Long2ObjectOpenHashMap<CompressedBlockEntity> responseBlockEntities =
            new Long2ObjectOpenHashMap<>(this.blockEntities);

        AxiomScheduler.runOnEntity(this.serverPlayer.getBukkitEntity(), () ->
            RequestChunkDataPacketListener.sendResponse(this.serverPlayer, this.id, responseBlockEntities, responseSections));
    }

}
