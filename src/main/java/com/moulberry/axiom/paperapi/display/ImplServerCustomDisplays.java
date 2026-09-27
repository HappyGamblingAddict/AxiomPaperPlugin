package com.moulberry.axiom.paperapi.display;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.paperapi.AxiomAlreadyRegisteredException;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import com.moulberry.axiom.paperapi.block.AxiomCustomBlockBuilder;
import com.moulberry.axiom.paperapi.block.AxiomProperty;
import com.moulberry.axiom.paperapi.block.ImplAxiomCustomBlock;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApiStatus.Internal
public class ImplServerCustomDisplays {

    // Registration happens on plugin lifecycle events while rebroadcasts happen on the global
    // region tick, so the registries are concurrent.
    private static final Map<Identifier, ImplAxiomCustomDisplay> registeredDisplays = new ConcurrentHashMap<>();
    private static final Map<Plugin, List<Identifier>> byPlugin = new ConcurrentHashMap<>();
    private static volatile boolean pendingReregisterAll = false;
    private static volatile boolean hasRegisteredToAPlayer = false;

    public static void register(Plugin plugin, AxiomCustomDisplayBuilder customDisplayBuilder) throws AxiomAlreadyRegisteredException {
        // Registering is server wide bookkeeping, so the global region is the right place for it:
        // the main thread on Paper, the global region tick thread on Folia.
        if (!AxiomScheduler.isGlobalTickThread()) {
            throw new WrongThreadException();
        }

        ImplAxiomCustomDisplay customDisplay = customDisplayBuilder.build();

        // Check for duplicate registration
        if (registeredDisplays.containsKey(customDisplay.id())) {
            throw new AxiomAlreadyRegisteredException("Custom display is already registered with id " + customDisplay.id());
        }

        // Register
        registeredDisplays.put(customDisplay.id(), customDisplay);
        byPlugin.computeIfAbsent(plugin, k -> Collections.synchronizedList(new ArrayList<>())).add(customDisplay.id());

        if (hasRegisteredToAPlayer) {
            pendingReregisterAll = true;
        }
    }

    private static void write(RegistryFriendlyByteBuf registryFriendlyByteBuf) {
        registryFriendlyByteBuf.writeVarInt(registeredDisplays.size());
        for (ImplAxiomCustomDisplay value : registeredDisplays.values()) {
            value.write(registryFriendlyByteBuf);
        }
    }

    public static void unregisterAll(Plugin plugin) {
        List<Identifier> remove = byPlugin.remove(plugin);
        if (remove == null || remove.isEmpty()) {
            return;
        }

        if (hasRegisteredToAPlayer) {
            pendingReregisterAll = true;
        }

        for (Identifier id : remove) {
            registeredDisplays.remove(id);
        }
    }

    public static void tick() {
        if (pendingReregisterAll) {
            pendingReregisterAll = false;

            List<ServerPlayer> players = new ArrayList<>();

            for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
                if (AxiomPaper.PLUGIN.canUseAxiom(player.getBukkitEntity())) {
                    int playerProtocolVersion = AxiomPaper.PLUGIN.getProtocolVersionFor(player.getUUID());
                    if (playerProtocolVersion == SharedConstants.getProtocolVersion()) {
                        players.add(player);
                    }
                }
            }

            if (players.isEmpty()) {
                hasRegisteredToAPlayer = false;
            } else {
                var registryAccess = MinecraftServer.getServer().registryAccess();
                sendAll(players, registryAccess);
            }
        }
    }

    private static void sendAll(List<ServerPlayer> players, RegistryAccess registryAccess) {
        if (players.isEmpty()) {
            return;
        }

        hasRegisteredToAPlayer = true;

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
        write(buf);
        VersionHelper.sendCustomPayloadToAll(players, "axiom:register_custom_items", ByteBufUtil.getBytes(buf));
    }

    public static void sendAll(ServerPlayer player) {
        hasRegisteredToAPlayer = true;

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        write(buf);
        VersionHelper.sendCustomPayload(player, "axiom:register_custom_items", ByteBufUtil.getBytes(buf));
    }

}
