package com.moulberry.axiom.viaversion;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.BlockBuffer;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.minecraft.chunks.DataPalette;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.PaletteType1_18;
import com.viaversion.viaversion.api.type.types.chunk.PaletteType1_21_5;
import com.viaversion.viaversion.api.type.types.chunk.PaletteTypeBase;
import com.viaversion.viaversion.util.MathUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.IdMap;
import net.minecraft.core.IdMapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ViaVersionHelper {

    // Written from whichever region handles a player's hello packet, so both caches need to
    // tolerate concurrent first-touch.
    private static final Map<Integer, IdMapper<BlockState>> blockRegistryCache = new ConcurrentHashMap<>();
    private static final Map<Integer, String> blockRegistryErrorCache = new ConcurrentHashMap<>();

    public static IdMapper<BlockState> getBlockRegistryForVersion(IdMapper<BlockState> mapper, int playerVersion) {
        // Success is checked first and is never overwritten by an error: a version that resolved
        // once must keep resolving, even if a later concurrent attempt failed for its own reason.
        IdMapper<BlockState> cached = blockRegistryCache.get(playerVersion);
        if (cached != null) {
            return cached;
        }
        String cachedError = blockRegistryErrorCache.get(playerVersion);
        if (cachedError != null) {
            throw new RuntimeException(cachedError);
        }

        List<ProtocolPathEntry> path = Via.getManager().getProtocolManager().getProtocolPath(playerVersion,
            SharedConstants.getProtocolVersion());

        if (path == null) {
            blockRegistryErrorCache.put(playerVersion, "Failed to find protocol path");
            throw new RuntimeException("Failed to find protocol path");
        }

        for (int i = path.size()-1; i >= 0; i--) {
            ProtocolPathEntry protocolPathEntry = path.get(i);

            MappingData mappingData = protocolPathEntry.protocol().getMappingData();

            if (mappingData == null) {
                blockRegistryErrorCache.put(playerVersion, "Failed to load mapping data (" + protocolPathEntry + ")");
                throw new RuntimeException("Failed to load mapping data (" + protocolPathEntry + ")");
            }

            Mappings blockStateMappings = mappingData.getBlockStateMappings();

            if (blockStateMappings == null) {
                blockRegistryErrorCache.put(playerVersion, "Failed to load BlockState mappings (" + protocolPathEntry + ")");
                throw new RuntimeException("Failed to load BlockState mappings (" + protocolPathEntry + ")");
            }

            mapper = ViaVersionHelper.applyMappings(mapper, blockStateMappings);
        }

        blockRegistryCache.put(playerVersion, mapper);
        return mapper;
    }

    private static IdMapper<BlockState> applyMappings(IdMapper<BlockState> registry, Mappings mappings) {
        IdMapper<BlockState> newBlockRegistry = new IdMapper<>();

        // Add empty mappings for non-existent blocks
        int size = mappings.mappedSize();
        for (int i = 0; i < size; i++) {
            newBlockRegistry.addMapping(BlockBuffer.EMPTY_STATE, i);
        }

        // Map blocks
        // We do this in reverse to prioritise earlier blocks in case of clashes
        // e.g. We probably want air to map to 0 instead of 3874
        for (int i = registry.size() - 1; i >= 0; i--) {
            BlockState blockState = registry.byId(i);

            if (blockState != null) {
                int newId = mappings.getNewId(i);
                if (newId >= 0) {
                    newBlockRegistry.addMapping(blockState, newId);
                }
            }
        }

        // Ensure block -> id is correct for the empty state
        int newEmptyStateId = mappings.getNewId(registry.getId(BlockBuffer.EMPTY_STATE));
        if (newEmptyStateId >= 0) {
            newBlockRegistry.addMapping(BlockBuffer.EMPTY_STATE, newEmptyStateId);
        }

        return newBlockRegistry;
    }

    private static final int UNNAMED_COMPOUND_TAG_CHANGE_VERSION = 764; // 1.20.2

    public static CompoundTag readTagViaVersion(FriendlyByteBuf friendlyByteBuf, int playerVersion) {
        Type<com.viaversion.nbt.tag.CompoundTag> from = getTagType(playerVersion);
        Type<com.viaversion.nbt.tag.CompoundTag> to = getTagType(SharedConstants.getProtocolVersion());

        return readTagViaVersion(friendlyByteBuf, from, to);
    }

    private static Type<com.viaversion.nbt.tag.CompoundTag> getTagType(int version) {
        if (version < UNNAMED_COMPOUND_TAG_CHANGE_VERSION) {
            return Types.NAMED_COMPOUND_TAG;
        } else {
            return Types.COMPOUND_TAG;
        }
    }

    private static CompoundTag readTagViaVersion(FriendlyByteBuf friendlyByteBuf,
        Type<com.viaversion.nbt.tag.CompoundTag> from,
        Type<com.viaversion.nbt.tag.CompoundTag> to) {
        if (from == to) {
            return friendlyByteBuf.readNbt();
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        to.write(buffer, from.read(friendlyByteBuf));
        return buffer.readNbt();
    }

    public static void readPalettedContainerViaVersion(FriendlyByteBuf friendlyByteBuf, PalettedContainer<BlockState> container, int playerVersion) {
        Type<DataPalette> from = getPalettedContainerType(playerVersion, container.data.configuration().bitsInMemory());
        Type<DataPalette> to = getPalettedContainerType(SharedConstants.getProtocolVersion(), container.data.configuration().bitsInMemory());

        if (from == to) {
            container.read(friendlyByteBuf);
            return;
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        to.write(buffer, from.read(friendlyByteBuf));
        container.read(buffer);
    }

    private static final LoadingCache<Integer, Type<DataPalette>> PALETTE_TYPE_1_21_5 = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public Type<DataPalette> load(Integer size) {
            return new PaletteType1_21_5(PaletteType.BLOCKS, size);
        }
    });

    private static final LoadingCache<Integer, Type<DataPalette>> PALETTE_TYPE_1_18 = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public Type<DataPalette> load(Integer size) {
            return new PaletteType1_18(PaletteType.BLOCKS, size);
        }
    });

    private static Type<DataPalette> getPalettedContainerType(int version, int bits) {
        if (version >= 770) { // 1.21.5
            return PALETTE_TYPE_1_21_5.getUnchecked(bits);
        } else {
            return PALETTE_TYPE_1_18.getUnchecked(bits);
        }
    }

}
