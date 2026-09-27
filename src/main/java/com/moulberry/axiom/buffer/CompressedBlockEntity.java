package com.moulberry.axiom.buffer;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.moulberry.axiom.AxiomPaper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import java.io.*;
import java.util.Objects;

public record CompressedBlockEntity(int originalSize, byte compressionDict, byte[] compressed) {

    /*
     * A ZstdDictCompress/ZstdDictDecompress wraps a raw native context pointer and is explicitly
     * NOT thread safe. On a regionised server several regions compress and decompress block entity
     * NBT at the same time, so the contexts (and the scratch stream) are per thread. The dictionary
     * bytes themselves are immutable and are only used to construct the contexts.
     */
    private static volatile byte[] dictionaryBytes = null;

    private static final ThreadLocal<ZstdDictCompress> zstdDictCompress = ThreadLocal.withInitial(() -> {
        if (dictionaryBytes == null) {
            throw new IllegalStateException("CompressedBlockEntity not initialized");
        }
        return new ZstdDictCompress(dictionaryBytes, Zstd.defaultCompressionLevel());
    });

    private static final ThreadLocal<ZstdDictDecompress> zstdDictDecompress = ThreadLocal.withInitial(() -> {
        if (dictionaryBytes == null) {
            throw new IllegalStateException("CompressedBlockEntity not initialized");
        }
        return new ZstdDictDecompress(dictionaryBytes);
    });

    public static void initialize(AxiomPaper plugin) {
        try (InputStream is = Objects.requireNonNull(plugin.getResource("zstd_dictionaries/block_entities_v1.dict"))) {
            dictionaryBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Build the contexts for this thread up front so a failure surfaces at enable rather than
        // on the first block a player pastes.
        zstdDictCompress.get();
        zstdDictDecompress.get();
    }

    public static CompressedBlockEntity compress(CompoundTag tag, ByteArrayOutputStream baos) {
        try {
            baos.reset();
            DataOutputStream dos = new DataOutputStream(baos);
            NbtIo.write(tag, dos);
            byte[] uncompressed = baos.toByteArray();
            byte[] compressed = Zstd.compress(uncompressed, zstdDictCompress.get());
            return new CompressedBlockEntity(uncompressed.length, (byte) 0, compressed);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CompoundTag decompress() {
        if (this.compressionDict != 0) throw new UnsupportedOperationException("Unknown compression dict: " + this.compressionDict);

        try {
            byte[] nbt = Zstd.decompress(this.compressed, zstdDictDecompress.get(), this.originalSize);
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt)), AxiomPaper.PLUGIN.createNbtAccounter());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static CompressedBlockEntity read(FriendlyByteBuf friendlyByteBuf) {
        int originalSize = friendlyByteBuf.readVarInt();
        byte compressionDict = friendlyByteBuf.readByte();
        byte[] compressed = friendlyByteBuf.readByteArray();
        return new CompressedBlockEntity(originalSize, compressionDict, compressed);
    }

    public void write(FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeVarInt(this.originalSize);
        friendlyByteBuf.writeByte(this.compressionDict);
        friendlyByteBuf.writeByteArray(this.compressed);
    }

}
