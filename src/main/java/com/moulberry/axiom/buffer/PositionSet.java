package com.moulberry.axiom.buffer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public class PositionSet {

    private final Long2ObjectMap<short[]> map;
    private int count = 0;

    public PositionSet() {
        this.map  = new Long2ObjectOpenHashMap<>();
        this.count = 0;
    }

    private PositionSet(Long2ObjectMap<short[]> map, int count) {
        this.map = map;
        this.count = count;
    }

    public int sectionCount() {
        return this.map.size();
    }

    public int count() {
        return this.count;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    /**
     * Calls {@code consumer} once per 16x16x16 section in this set, passing the section's minimum
     * block coordinates and its 16x16 bitmask.
     *
     * <p>Callers that mutate the world must process each section on the region that owns its chunk,
     * so this is the granularity they should iterate at: each section belongs to exactly one chunk.
     */
    public void forEachSection(SectionConsumer consumer) {
        for (Long2ObjectMap.Entry<short[]> entry : this.map.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            consumer.accept(BlockPos.getX(key) * 16, BlockPos.getY(key) * 16, BlockPos.getZ(key) * 16, entry.getValue());
        }
    }

    /** Expands a single section, as handed out by {@link #forEachSection}. */
    public static void forEachInSection(int minX, int minY, int minZ, short[] bitmask, TriIntConsumer consumer) {
        int index = 0;
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                short v = bitmask[index++];

                if (v == -1) {
                    for (int x = 0; x < 16; x++) {
                        consumer.accept(minX + x, minY + y, minZ + z);
                    }
                } else if (v != 0) {
                    for (int x = 0; x < 16; x++) {
                        if ((v & (1 << x)) != 0) {
                            consumer.accept(minX + x, minY + y, minZ + z);
                        }
                    }
                }
            }
        }
    }

    public void forEach(TriIntConsumer consumer) {
        forEachSection((minX, minY, minZ, bitmask) -> forEachInSection(minX, minY, minZ, bitmask, consumer));
    }

    public static PositionSet read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Long2ObjectMap<short[]> map = new Long2ObjectOpenHashMap<>(Math.min(256, size));

        int count = 0;
        for (int i = 0; i < size; i++) {
            long pos = buf.readLong();

            short[] array = new short[16*16];
            for (int j = 0; j < 16*16; j++) {
                short s = buf.readShort();
                count += Integer.bitCount(s & 0xFFFF);
                array[j] = s;
            }

            map.put(pos, array);
        }

        return new PositionSet(map, count);
    }

    @FunctionalInterface
    public interface SectionConsumer {
        void accept(int minX, int minY, int minZ, short[] bitmask);
    }

}
