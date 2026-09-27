package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.PositionSet;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class TickBlocksPacketListener implements PacketHandler {

    private record Section(int minX, int minY, int minZ, short[] bitmask) { }

    private final AxiomPaper plugin;
    public TickBlocksPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onReceive(Player bukkitPlayer, FriendlyByteBuf friendlyByteBuf) {
        var player = ((CraftPlayer)bukkitPlayer).getHandle();

        var level = player.level();
        if (level == null) {
            return;
        }

        if (!this.plugin.canUseAxiom(bukkitPlayer, AxiomPermission.BUILD_DANGEROUS_TICK)) {
            return;
        }

        var world = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        PositionSet positionSet;
        BlockPos aabbMin;
        BlockPos aabbMax;

        byte type = friendlyByteBuf.readByte();
        if (type == 0) {
            positionSet = PositionSet.read(friendlyByteBuf);
            aabbMin = null;
            aabbMax = null;
        } else if (type == 1) {
            positionSet = null;
            aabbMin = friendlyByteBuf.readBlockPos();
            aabbMax = friendlyByteBuf.readBlockPos();
        } else {
            throw new RuntimeException("Unknown type: " + type);
        }

        if (level.dimension() != world) {
            return;
        }

        int count;
        if (positionSet != null) {
            count = positionSet.count();
        } else {
            int sizeX = Math.abs(aabbMax.getX() - aabbMin.getX()) + 1;
            int sizeY = Math.abs(aabbMax.getY() - aabbMin.getY()) + 1;
            int sizeZ = Math.abs(aabbMax.getZ() - aabbMin.getZ()) + 1;
            count = sizeX * sizeY * sizeZ;
        }
        boolean showMessage = count > 1048576;

        if (showMessage) {
            long estimatedTime = Math.max(1, count / 2097152);
            AxiomScheduler.global(() -> {
                var server = level.getServer();

                Component msg = Component.literal(player.getScoreboardName() + " updated & ticked " + count + " blocks using Axiom. The server may lag...");
                server.getPlayerList().broadcastSystemMessage(msg, false);

                msg = Component.literal("Estimated Time (varies depending on server hardware): " + estimatedTime + "s");
                server.getPlayerList().broadcastSystemMessage(msg, false);

                if (estimatedTime > 30) {
                    msg = Component.literal("Estimated time is >30s, expect to be kicked from the server");
                    server.getPlayerList().broadcastSystemMessage(msg, false);
                }
            });
        }

        long start = System.currentTimeMillis();

        // Ticking a selection routinely covers many chunks, each of which may be owned by a
        // different region, so the work is grouped per chunk and every group is ticked by the
        // region that owns it.
        if (positionSet != null) {
            Map<ChunkPos, List<Section>> sectionsPerChunk = new LinkedHashMap<>();
            positionSet.forEachSection((minX, minY, minZ, bitmask) -> sectionsPerChunk
                .computeIfAbsent(new ChunkPos(minX >> 4, minZ >> 4), k -> new ArrayList<>())
                .add(new Section(minX, minY, minZ, bitmask)));

            AtomicInteger outstanding = new AtomicInteger(sectionsPerChunk.size());
            for (Map.Entry<ChunkPos, List<Section>> entry : sectionsPerChunk.entrySet()) {
                ChunkPos chunkPos = entry.getKey();
                List<Section> sections = entry.getValue();

                try {
                    AxiomScheduler.region(level.getWorld(), chunkPos.x(), chunkPos.z(), () -> {
                        try {
                            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
                            for (Section section : sections) {
                                PositionSet.forEachInSection(section.minX(), section.minY(), section.minZ(), section.bitmask(),
                                    (x, y, z) -> tickBlock(level, blockPos, x, y, z));
                            }
                        } catch (Throwable t) {
                            kickOnPlayer(bukkitPlayer, "An error occured while ticking blocks: " + t.getMessage());
                        } finally {
                            doneTick(outstanding, level, start, showMessage);
                        }
                    });
                } catch (Throwable t) {
                    // Scheduler refused this chunk. Account for it anyway, otherwise the remaining
                    // chunks never finish and the "done" broadcast never goes out.
                    doneTick(outstanding, level, start, showMessage);
                }
            }
        } else {
            int minX = Math.min(aabbMin.getX(), aabbMax.getX());
            int minY = Math.min(aabbMin.getY(), aabbMax.getY());
            int minZ = Math.min(aabbMin.getZ(), aabbMax.getZ());
            int maxX = Math.max(aabbMin.getX(), aabbMax.getX());
            int maxY = Math.max(aabbMin.getY(), aabbMax.getY());
            int maxZ = Math.max(aabbMax.getZ(), aabbMax.getZ());

            int minSectionX = minX >> 4;
            int minSectionZ = minZ >> 4;
            int maxSectionX = maxX >> 4;
            int maxSectionZ = maxZ >> 4;

            int chunks = (maxSectionX - minSectionX + 1) * (maxSectionZ - minSectionZ + 1);
            AtomicInteger outstanding = new AtomicInteger(chunks);

            for (int cx = minSectionX; cx <= maxSectionX; cx++) {
                for (int cz = minSectionZ; cz <= maxSectionZ; cz++) {
                    final int sectionMinX = Math.max(minX, cx << 4);
                    final int sectionMaxX = Math.min(maxX, (cx << 4) + 15);
                    final int sectionMinZ = Math.max(minZ, cz << 4);
                    final int sectionMaxZ = Math.min(maxZ, (cz << 4) + 15);

                    try {
                        AxiomScheduler.region(level.getWorld(), cx, cz, () -> {
                            try {
                                BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
                                for (int y = minY; y <= maxY; y++) {
                                    for (int x = sectionMinX; x <= sectionMaxX; x++) {
                                        for (int z = sectionMinZ; z <= sectionMaxZ; z++) {
                                            tickBlock(level, blockPos, x, y, z);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                kickOnPlayer(bukkitPlayer, "An error occured while ticking blocks: " + t.getMessage());
                            } finally {
                                doneTick(outstanding, level, start, showMessage);
                            }
                        });
                    } catch (Throwable t) {
                        doneTick(outstanding, level, start, showMessage);
                    }
                }
            }
        }
    }

    private static void doneTick(AtomicInteger outstanding, ServerLevel level, long start, boolean showMessage) {
        if (outstanding.decrementAndGet() == 0) {
            finishTicking(level, start, showMessage);
        }
    }

    /**
     * Error paths here run on whichever region owns the chunk being ticked, which is not
     * necessarily the player's, and kicking is an entity mutation.
     */
    private static void kickOnPlayer(Player player, String message) {
        AxiomScheduler.runOnEntity(player, () -> player.kick(net.kyori.adventure.text.Component.text(message)));
    }

    private static void finishTicking(ServerLevel level, long start, boolean showMessage) {
        if (!showMessage) {
            return;
        }
        AxiomScheduler.global(() -> {
            long end = System.currentTimeMillis();
            long seconds = (end - start + 500) / 1000;
            Component msg = Component.literal("Done updating & ticking blocks (took " + seconds + "s)");
            level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        });
    }

    /** Runs on the region owning the chunk containing this position. */
    private static void tickBlock(ServerLevel level, BlockPos.MutableBlockPos blockPos, int x, int y, int z) {
        blockPos.set(x, y, z);

        BlockState blockState = level.getBlockState(blockPos);
        if (blockState.isAir()) {
            return;
        }

        FluidState fluidState = blockState.getFluidState();
        if (!fluidState.isEmpty()) {
            fluidState.tick(level, blockPos, blockState);
        }

        if (blockState.getBlock() instanceof LiquidBlock) {
            blockState.tick(level, blockPos, level.getRandom());
        } else {
            BlockState blockStateNew = Block.updateFromNeighbourShapes(blockState, level, blockPos);
            if (blockStateNew != blockState) {
                level.setBlock(blockPos, blockStateNew, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            }
        }
    }

}
