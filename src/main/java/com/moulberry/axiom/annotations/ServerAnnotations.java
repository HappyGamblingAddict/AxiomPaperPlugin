package com.moulberry.axiom.annotations;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.annotations.data.AnnotationData;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public class ServerAnnotations {

    // Accessed from the global region (annotation updates) and from player regions (sendAll), so
    // both the cache and the payload map have to tolerate concurrent access.
    private static final Map<World, ServerAnnotations> serverAnnotationCache = Collections.synchronizedMap(new WeakHashMap<>());
    private static final NamespacedKey ANNOTATION_DATA_KEY = new NamespacedKey(AxiomPaper.PLUGIN, "annotation_data");

    final Map<UUID, AnnotationData> annotations = Collections.synchronizedMap(new LinkedHashMap<>());

    private static void sendAnnotationUpdates(List<AnnotationUpdateAction> actions, List<ServerPlayer> players) {
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        friendlyByteBuf.writeCollection(actions, (buffer, action) -> action.write(buffer));

        byte[] bytes = ByteBufUtil.getBytes(friendlyByteBuf);
        for (ServerPlayer serverPlayer : players) {
            VersionHelper.sendCustomPayload(serverPlayer, VersionHelper.createIdentifier("axiom:annotation_update"), bytes);
        }
    }

    private static ServerAnnotations forWorld(World world) {
        return serverAnnotationCache.computeIfAbsent(world, w -> {
            ServerAnnotations loaded = w.getPersistentDataContainer().get(ANNOTATION_DATA_KEY, ServerAnnotationsAdapater.INSTANCE);
            return loaded != null ? loaded : new ServerAnnotations();
        });
    }

    public static void sendAll(World world, ServerPlayer player) {
        if (!AxiomPaper.PLUGIN.allowAnnotations) {
            return;
        }

        // Reading the world's persistent data container is global-region work on Folia, so the
        // payload is collected there and only the network write happens on the player's region.
        AxiomScheduler.global(() -> sendAllOnGlobal(world, player));
    }

    private static void sendAllOnGlobal(World world, ServerPlayer player) {
        List<AnnotationUpdateAction> actions = new ArrayList<>();

        actions.add(new AnnotationUpdateAction.ClearAllAnnotations());

        ServerAnnotations serverAnnotations = forWorld(world);
        synchronized (serverAnnotations.annotations) {
            for (Map.Entry<UUID, AnnotationData> entry : serverAnnotations.annotations.entrySet()) {
                actions.add(new AnnotationUpdateAction.CreateAnnotation(entry.getKey(), entry.getValue()));
            }
        }

        AxiomScheduler.runOnEntity(player.getBukkitEntity(), () -> sendAnnotationUpdates(actions, List.of(player)));
    }

    public static void handleUpdates(World world, List<AnnotationUpdateAction> actions) {
        if (!AxiomPaper.PLUGIN.allowAnnotations) {
            return;
        }

        ServerAnnotations serverAnnotations = forWorld(world);

        boolean dirty = false;

        // handleUpdates runs on the global region while sendAll runs on player regions, so the
        // payload map is guarded as a whole.
        synchronized (serverAnnotations.annotations) {
            for (AnnotationUpdateAction action : actions) {
                if (action instanceof AnnotationUpdateAction.CreateAnnotation create) {
                    serverAnnotations.annotations.put(create.uuid(), create.annotationData());
                    dirty = true;
                } else if (action instanceof AnnotationUpdateAction.DeleteAnnotation delete) {
                    AnnotationData removed = serverAnnotations.annotations.remove(delete.uuid());
                    if (removed != null) {
                        dirty = true;
                    }
                } else if (action instanceof AnnotationUpdateAction.MoveAnnotation move) {
                    AnnotationData annotation = serverAnnotations.annotations.get(move.uuid());
                    if (annotation != null) {
                        annotation.setPosition(move.to());
                        dirty = true;
                    }
                } else if (action instanceof AnnotationUpdateAction.ClearAllAnnotations) {
                    if (!serverAnnotations.annotations.isEmpty()) {
                        serverAnnotations.annotations.clear();
                        dirty = true;
                    }
                } else if (action instanceof AnnotationUpdateAction.RotateAnnotation rotate) {
                    AnnotationData annotation = serverAnnotations.annotations.get(rotate.uuid());
                    if (annotation != null) {
                        annotation.setRotation(rotate.to());
                        dirty = true;
                    }
                } else {
                    throw new UnsupportedOperationException("Unknown action: " + action.getClass());
                }
            }
        }

        if (dirty) {
            world.getPersistentDataContainer().set(ANNOTATION_DATA_KEY, ServerAnnotationsAdapater.INSTANCE, serverAnnotations);
        }

        // Forward actions back to clients. Read the server wide player list rather than
        // ServerLevel#players(), which walks the level's entity list and is not region safe.
        List<ServerPlayer> playersWithAxiom = new ArrayList<>();
        ServerLevel handle = ((CraftWorld)world).getHandle();
        for (ServerPlayer player : handle.getServer().getPlayerList().getPlayers()) {
            if (player.level() == handle && AxiomPaper.PLUGIN.canUseAxiom(player.getBukkitEntity())) {
                playersWithAxiom.add(player);
            }
        }

        if (!playersWithAxiom.isEmpty()) {
            sendAnnotationUpdates(actions, playersWithAxiom);
        }
    }

}
