package com.moulberry.axiom;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.moulberry.axiom.blueprint.ServerBlueprintManager;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.commands.AxiomDebugCommand;
import com.moulberry.axiom.commands.AxiomMigrateCommand;
import com.moulberry.axiom.event.AxiomCreateWorldPropertiesEvent;
import com.moulberry.axiom.event.AxiomModifyWorldEvent;
import com.moulberry.axiom.integration.coreprotect.CoreProtectIntegration;
import com.moulberry.axiom.integration.plotsquared.PlotSquaredIntegration;
import com.moulberry.axiom.listener.LuckPermsListener;
import com.moulberry.axiom.listener.NoPhysicalTriggerListener;
import com.moulberry.axiom.operations.OperationQueue;
import com.moulberry.axiom.operations.PendingOperation;
import com.moulberry.axiom.packet.*;
import com.moulberry.axiom.packet.impl.*;
import com.moulberry.axiom.paperapi.display.ImplServerCustomDisplays;
import com.moulberry.axiom.paperapi.entity.ImplAxiomHiddenEntities;
import com.moulberry.axiom.paperapi.block.ImplServerCustomBlocks;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.restrictions.AxiomPermissionSet;
import com.moulberry.axiom.restrictions.Restrictions;
import com.moulberry.axiom.scheduler.AxiomScheduler;
import com.moulberry.axiom.world_properties.server.ServerWorldPropertiesRegistry;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.papermc.paper.event.player.PlayerFailMoveEvent;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import io.papermc.paper.network.ChannelInitializeListener;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.util.TriState;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.IdMapper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.*;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

public class AxiomPaper extends JavaPlugin implements Listener {

    public static AxiomPaper PLUGIN; // tsk tsk tsk

    public final Set<UUID> activeAxiomPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public final Set<UUID> failedPermissionAxiomPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public final Map<UUID, Restrictions> playerRestrictions = new ConcurrentHashMap<>();
    public final Map<UUID, IdMapper<BlockState>> playerBlockRegistry = new ConcurrentHashMap<>();
    public final Map<UUID, Integer> playerProtocolVersion = new ConcurrentHashMap<>();
    private final Map<UUID, AxiomPermissionSet> playerPermissions = new ConcurrentHashMap<>();
    private final Map<UUID, PlotSquaredIntegration.PlotBounds> lastPlotBoundsForPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> noPhysicalTriggerPlayers = ConcurrentHashMap.newKeySet();
    private final OperationQueue operationQueue = new OperationQueue();
    private final Object dispatchSendsLock = new Object();
    private final Object2IntOpenHashMap<UUID> availableDispatchSends = new Object2IntOpenHashMap<>();
    private final Map<UUID, ScheduledTask> playerTickTasks = new ConcurrentHashMap<>();
    public Configuration configuration;

    public IdMapper<BlockState> allowedBlockRegistry = null;
    private boolean logLargeBlockBufferChanges = false;
    private int packetCollectionReadLimit = 1024;
    private long maxNbtDecompressLimit = 131072;
    private final Set<EntityType<?>> whitelistedEntities = new HashSet<>();
    private final Set<EntityType<?>> blacklistedEntities = new HashSet<>();

    private int defaultAllowedDispatchSendsPerSecond = 1024;
    private LinkedHashMap<String, Integer> allowedDispatchSendOverrides = new LinkedHashMap<>();

    private volatile boolean registeredNoPhysicalTriggerListener = false;
    public boolean logCoreProtectChanges = true;

    public Path blueprintFolder = null;
    public boolean allowAnnotations = false;
    private int infiniteReachLimit = -1;
    private boolean sendMarkers = false;
    private int maxChunkRelightsPerTick = 0;
    private int maxChunkSendsPerTick = 0;
    private int maxChunkLoadDistance = 256;

    public int configRemovedEntries = 0;
    public int configAddedEntries = 0;

    private boolean clearCachedPermissionsOnTick = true;
    private int checkAxiomEnableDisableTimer = 0;

    private String incompatibleDataVersion = "warn";
    private String unsupportedAxiomVersion = "kick";
    private String whitelistWorldRegex = null;
    private String blacklistWorldRegex = null;
    private boolean disableEntitySanitization = false;
    private boolean allowTeleportBetweenWorlds = false;
    private int maxBlockBufferPacketSize = 0;

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    public String getIncompatibleDataVersion() {
        return this.incompatibleDataVersion;
    }

    public String getUnsupportedAxiomVersion() {
        return this.unsupportedAxiomVersion;
    }

    public boolean isEntitySanitizationDisabled() {
        return this.disableEntitySanitization;
    }

    public boolean isTeleportBetweenWorldsAllowed() {
        return this.allowTeleportBetweenWorlds;
    }

    public int getMaxBlockBufferPacketSize() {
        return this.maxBlockBufferPacketSize;
    }

    @Override
    public void onEnable() {
        PLUGIN = this;
        AxiomScheduler.init(this);

        AxiomReflection.init();

        this.saveDefaultConfig();
        this.configuration = this.getConfig();

        Set<String> validResolutions = Set.of("kick", "warn", "ignore");
        if (!validResolutions.contains(this.configuration.getString("incompatible-data-version"))) {
            this.getLogger().warning("Invalid value for incompatible-data-version, expected 'kick', 'warn' or 'ignore'");
        }
        if (!validResolutions.contains(this.configuration.getString("unsupported-axiom-version"))) {
            this.getLogger().warning("Invalid value for unsupported-axiom-version, expected 'kick', 'warn' or 'ignore'");
        }

        checkOutdatedConfig();

        this.logLargeBlockBufferChanges = this.configuration.getBoolean("log-large-block-buffer-changes");

        if (this.configuration.getBoolean("allow-large-payload-for-all-packets")) {
            this.packetCollectionReadLimit = Short.MAX_VALUE;
            this.maxNbtDecompressLimit = Long.MAX_VALUE;
        }

        // Config values that are read while handling packets. ConfigurationSection is not safe to
        // read concurrently with the /axiompapermigrateconfig write, so they are snapshotted here.
        this.incompatibleDataVersion = orDefault(this.configuration.getString("incompatible-data-version"), "warn");
        this.unsupportedAxiomVersion = orDefault(this.configuration.getString("unsupported-axiom-version"), "kick");
        this.whitelistWorldRegex = this.configuration.getString("whitelist-world-regex");
        this.blacklistWorldRegex = this.configuration.getString("blacklist-world-regex");
        this.disableEntitySanitization = this.configuration.getBoolean("disable-entity-sanitization");
        this.allowTeleportBetweenWorlds = this.configuration.getBoolean("allow-teleport-between-worlds");
        this.maxBlockBufferPacketSize = this.configuration.getInt("max-block-buffer-packet-size");

        this.whitelistedEntities.clear();
        this.blacklistedEntities.clear();
        for (String whitelistedEntity : this.configuration.getStringList("whitelist-entities")) {
            EntityType.byString(whitelistedEntity).ifPresent(this.whitelistedEntities::add);
        }
        for (String blacklistedEntity : this.configuration.getStringList("blacklist-entities")) {
            EntityType.byString(blacklistedEntity).ifPresent(this.blacklistedEntities::add);
        }

        List<String> disallowedBlocks = this.configuration.getStringList("disallowed-blocks");
        this.allowedBlockRegistry = DisallowedBlocks.createAllowedBlockRegistry(disallowedBlocks);

        this.allowAnnotations = this.configuration.getBoolean("allow-annotations");
        boolean allowServerBlueprints = this.configuration.getBoolean("blueprint-sharing");

        this.infiniteReachLimit = this.configuration.getInt("infinite-reach-limit");

        Bukkit.getPluginManager().registerEvents(this, this);
        // Bukkit.getPluginManager().registerEvents(new WorldPropertiesExample(), this);
        CompressedBlockEntity.initialize(this);

        Messenger msg = Bukkit.getMessenger();

        msg.registerOutgoingPluginChannel(this, "axiom:enable");
        msg.registerOutgoingPluginChannel(this, "axiom:response_chunk_data");
        msg.registerOutgoingPluginChannel(this, "axiom:register_world_properties");
        msg.registerOutgoingPluginChannel(this, "axiom:set_world_property");
        msg.registerOutgoingPluginChannel(this, "axiom:ack_world_properties");
        msg.registerOutgoingPluginChannel(this, "axiom:restrictions");
        msg.registerOutgoingPluginChannel(this, "axiom:marker_data");
        msg.registerOutgoingPluginChannel(this, "axiom:marker_nbt_response");
        msg.registerOutgoingPluginChannel(this, "axiom:annotation_update");
        msg.registerOutgoingPluginChannel(this, "axiom:add_server_heightmap");
        msg.registerOutgoingPluginChannel(this, "axiom:custom_blocks");
        msg.registerOutgoingPluginChannel(this, "axiom:register_custom_block_v2");
        msg.registerOutgoingPluginChannel(this, "axiom:ignore_display_entities");
        msg.registerOutgoingPluginChannel(this, "axiom:register_custom_items");

        Map<String, PacketHandler> largePayloadHandlers = new HashMap<>();

        registerPacketHandler("hello", new HelloPacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);
        registerPacketHandler("set_gamemode", new SetGamemodePacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);
        registerPacketHandler("set_fly_speed", new SetFlySpeedPacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);
        registerPacketHandler("teleport", new TeleportPacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);
        registerPacketHandler("set_world_time", new SetTimePacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);
        registerPacketHandler("set_no_physical_trigger", new SetNoPhysicalTriggerPacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);
        registerPacketHandler("set_world_property", new SetWorldPropertyListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);

        registerPacketHandler("request_chunk_data", new RequestChunkDataPacketListener(this), msg,
                this.configuration.getBoolean("allow-large-chunk-data-request") ? LargePayloadBehaviour.FORCE_LARGE : LargePayloadBehaviour.DEFAULT, largePayloadHandlers);
        registerPacketHandler("request_entity_data", new RequestEntityDataPacketListener(this), msg,
                this.configuration.getBoolean("allow-large-chunk-data-request") ? LargePayloadBehaviour.FORCE_LARGE : LargePayloadBehaviour.DEFAULT, largePayloadHandlers);

        registerPacketHandler("spawn_entity", new SpawnEntityPacketListener(this), msg, LargePayloadBehaviour.DEFAULT, largePayloadHandlers);
        registerPacketHandler("manipulate_entity", new ManipulateEntityPacketListener(this), msg, LargePayloadBehaviour.DEFAULT, largePayloadHandlers);
        registerPacketHandler("delete_entity", new DeleteEntityPacketListener(this), msg, LargePayloadBehaviour.DEFAULT, largePayloadHandlers);
        registerPacketHandler("marker_nbt_request", new MarkerNbtRequestPacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);

        registerPacketHandler("set_block", new SetBlockPacketListener(this), msg, LargePayloadBehaviour.DEFAULT, largePayloadHandlers);
        registerPacketHandler("set_buffer", new SetBlockBufferPacketListener(this), msg, LargePayloadBehaviour.FORCE_LARGE, largePayloadHandlers);

        if (allowServerBlueprints) {
            registerPacketHandler("upload_blueprint", new UploadBlueprintPacketListener(this), msg, LargePayloadBehaviour.FORCE_LARGE, largePayloadHandlers);
            registerPacketHandler("request_blueprint", new BlueprintRequestPacketListener(this), msg, LargePayloadBehaviour.FORCE_SMALL, largePayloadHandlers);
        }
        if (this.allowAnnotations) {
            registerPacketHandler("annotation_update", new UpdateAnnotationPacketListener(this), msg, LargePayloadBehaviour.FORCE_LARGE, largePayloadHandlers);
        }

        if (!largePayloadHandlers.isEmpty()) {
            // Hack to figure out the id of the CustomPayload packet
            ProtocolInfo<ServerGamePacketListener> protocol = GameProtocols.SERVERBOUND_TEMPLATE.bind(k -> new RegistryFriendlyByteBuf(k,
                MinecraftServer.getServer().registryAccess()), new GameProtocols.Context() {
                @Override
                public boolean hasInfiniteMaterials() {
                    return false;
                }
            });
            RegistryFriendlyByteBuf friendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), MinecraftServer.getServer().registryAccess());
            protocol.codec().encode(friendlyByteBuf, new ServerboundCustomPayloadPacket(VersionHelper.createCustomPayload(VersionHelper.createIdentifier("dummy"), new byte[0])));
            int payloadId = friendlyByteBuf.readVarInt();

            ChannelInitializeListenerHolder.addListener(Key.key("axiom:handle_big_payload"), new ChannelInitializeListener() {
                @Override
                public void afterInitChannel(@NonNull Channel channel) {
                    Connection connection = (Connection) channel.pipeline().get("packet_handler");
                    AxiomBigPayloadHandler.apply(channel.pipeline(), new AxiomBigPayloadHandler(payloadId, connection, largePayloadHandlers, true));
                }
            });
        }

        if (allowServerBlueprints) {
            this.blueprintFolder = this.getDataFolder().toPath().resolve("blueprints");
            try {
                Files.createDirectories(this.blueprintFolder);
            } catch (IOException ignored) {}
            ServerBlueprintManager.initialize(this.blueprintFolder);
        }

        Path heightmapsPath = this.getDataFolder().toPath().resolve("heightmaps");
        try {
            Files.createDirectories(heightmapsPath);
        } catch (IOException ignored) {}
        ServerHeightmaps.load(heightmapsPath);

        AxiomScheduler.globalAtFixedRate(this::tick, 1L, 1L);
        AxiomScheduler.global(this::seedExistingMarkers);

        this.sendMarkers = this.configuration.getBoolean("send-markers");
        this.maxChunkRelightsPerTick = this.configuration.getInt("max-chunk-relights-per-tick");
        this.maxChunkSendsPerTick = this.configuration.getInt("max-chunk-sends-per-tick");
        this.maxChunkLoadDistance = this.configuration.getInt("max-chunk-load-distance");

        this.logCoreProtectChanges = this.configuration.getBoolean("log-core-protect-changes");

        this.defaultAllowedDispatchSendsPerSecond = this.configuration.getInt("block-buffer-rate-limit");
        if (this.defaultAllowedDispatchSendsPerSecond <= 0) {
            this.defaultAllowedDispatchSendsPerSecond = 1024;
        }
        ConfigurationSection limits = this.configuration.getConfigurationSection("limits");
        if (limits != null) {
            for (String key : limits.getKeys(false)) {
                ConfigurationSection values = limits.getConfigurationSection(key);
                if (values == null) {
                    continue;
                }

                int allowedDispatchSends = values.getInt("block-buffer-rate-limit");
                if (allowedDispatchSends > 0) {
                    this.allowedDispatchSendOverrides.put(key, allowedDispatchSends);
                }
            }
        }

        try {
            LegacyPaperCommandManager<CommandSender> manager = LegacyPaperCommandManager.createNative(
                this,
                ExecutionCoordinator.simpleCoordinator()
            );

            if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
                manager.registerBrigadier();
            }

            AxiomDebugCommand.register(this, manager);
            AxiomMigrateCommand.register(manager);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            this.getLogger().info("LuckPerms integration enabled");
            this.clearCachedPermissionsOnTick = false;
            LuckPermsListener.register(this);
        }

        if (CoreProtectIntegration.isEnabled()) {
            this.getLogger().info("CoreProtect integration enabled");
        }
    }

    private void checkOutdatedConfig() {
        this.configAddedEntries = 0;
        this.configRemovedEntries = 0;

        Configuration defaultConfig = this.configuration.getDefaults();
        if (defaultConfig == null) {
            return;
        }

        Set<String> defaultKeys = defaultConfig.getKeys(false);
        Set<String> currentKeys = this.configuration.getKeys(false);

        var a = new HashSet<>(defaultKeys);
        a.removeAll(currentKeys);
        this.configAddedEntries = a.size();

        var b = new HashSet<>(currentKeys);
        b.removeAll(defaultKeys);
        this.configRemovedEntries = b.size();
    }

    public void migrateConfig(CommandSender commandSender) {
        if (this.configAddedEntries == 0 && this.configRemovedEntries == 0) {
            commandSender.sendMessage(Component.text("No migration necessary").color(NamedTextColor.YELLOW));
            return;
        }

        this.configAddedEntries = 0;
        this.configRemovedEntries = 0;

        Configuration defaultConfig = this.configuration.getDefaults();
        if (!(defaultConfig instanceof FileConfiguration fileConfiguration)) {
            commandSender.sendMessage(Component.text("Internal error: Default config doesn't implement FileConfiguration").color(NamedTextColor.RED));
            return;
        }

        Path dataFolder = this.getDataFolder().toPath();
        Path configPath = dataFolder.resolve("config.yml");
        Path backupPath = dataFolder.resolve("config.yml.bak");
        if (!Files.exists(configPath)) {
            commandSender.sendMessage(Component.text("Internal error: config.yml doesn't exist").color(NamedTextColor.RED));
            return;
        }

        // Backup
        try {
            Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
            commandSender.sendMessage(Component.text("Internal error: couldn't backup existing config").color(NamedTextColor.RED));
            return;
        }

        commandSender.sendMessage(Component.text("Successfully backed up current config to config.yml.bak").color(NamedTextColor.GREEN));

        // Set new values
        Set<String> keys = defaultConfig.getKeys(false);
        for (String key : keys) {
            Object object = this.configuration.get(key);
            if (object != null) {
                fileConfiguration.set(key, object);
            }
        }

        // Save
        try {
            fileConfiguration.save(configPath.toFile());
        } catch (IOException e) {
            commandSender.sendMessage(Component.text("Internal error: failed to save migrated config.yml").color(NamedTextColor.RED));
            e.printStackTrace();
        }

        commandSender.sendMessage(Component.text("Successfully migrated config.yml").color(NamedTextColor.GREEN));
    }

    public int getMaxChunkLoadDistance(World world) {
        int maxChunkLoadDistance = this.maxChunkLoadDistance;

        // Don't allow loading chunks outside render distance for plot worlds
        if (PlotSquaredIntegration.isPlotWorld(world)) {
            maxChunkLoadDistance = 0;
        }

        return maxChunkLoadDistance;
    }

    private enum LargePayloadBehaviour {
        DEFAULT,
        FORCE_LARGE,
        FORCE_SMALL
    }

    public void clearCachedPermissionsFor(UUID uuid) {
        this.playerPermissions.remove(uuid);
    }

    /**
     * Global region tick. Deliberately contains no reads or writes of chunk or entity state: the
     * per-player work is handed to each player's own region via their entity scheduler, the
     * operation queue drives itself from region tasks, and the chunk resend/relight queues are
     * drained by dispatching to the region that owns each chunk.
     */
    private void tick() {
        if (this.clearCachedPermissionsOnTick) {
            this.playerPermissions.clear();
        }

        this.checkAxiomEnableDisableTimer += 1;
        if (this.checkAxiomEnableDisableTimer >= 20) {
            this.checkAxiomEnableDisableTimer = 0;
            this.sweepOnlinePlayers();
        }

        this.operationQueue.tick();

        WorldExtension.tick(MinecraftServer.getServer(), this.sendMarkers, this.maxChunkRelightsPerTick, this.maxChunkSendsPerTick);

        ImplServerCustomBlocks.tick();
        ImplServerCustomDisplays.tick();
        ImplAxiomHiddenEntities.tick();
    }

    /**
     * Runs on the global region. Every touch of a player is immediately forwarded to that player's
     * entity region; the only state manipulated here is Axiom's own bookkeeping. Per-player cleanup
     * is handled by {@link #onPlayerQuit} rather than a retainAll sweep, because the scheduled work
     * has not run yet at this point and its results cannot be collected synchronously.
     */
    private void sweepOnlinePlayers() {
        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            AxiomScheduler.runOnEntity(player, () -> sweepPlayer(player));
        }
    }

    /** Always runs on the owning region of {@code player}. */
    private void sweepPlayer(Player player) {
        UUID uuid = player.getUniqueId();

        if (this.activeAxiomPlayers.contains(uuid)) {
            if (!this.hasPermission(player, AxiomPermission.USE)) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeBoolean(false);
                byte[] bytes = ByteBufUtil.getBytes(buf);
                VersionHelper.sendCustomPayload(player, "axiom:enable", bytes);

                this.failedPermissionAxiomPlayers.add(uuid);
                deactivateAxiomPlayer(uuid);
            } else {
                ensurePlayerTickTask(player);
                tickPlayer(player, true);
            }
        } else if (this.failedPermissionAxiomPlayers.contains(uuid)) {
            if (this.hasPermission(player, AxiomPermission.USE)) {
                VersionHelper.sendCustomPayload(player, "axiom:redo_handshake", new byte[]{});
                this.failedPermissionAxiomPlayers.remove(uuid);
            }
        }
    }

    /**
     * Keeps a per-player task alive on that player's region: it refills the dispatch send budget.
     * EntityScheduler#runAtFixedRate can return null for a rejected task, so the sweep re-arms it
     * rather than leaving the player throttled down to a few percent of their configured rate.
     */
    private void ensurePlayerTickTask(Player player) {
        UUID uuid = player.getUniqueId();
        if (this.playerTickTasks.containsKey(uuid)) {
            return;
        }

        ScheduledTask task = AxiomScheduler.entityAtFixedRate(player, () -> {
            if (!this.activeAxiomPlayers.contains(uuid)) {
                return;
            }
            tickPlayer(player, false);
        }, 1L, 1L);

        if (task != null) {
            this.playerTickTasks.put(uuid, task);
        }
    }

    private void deactivateAxiomPlayer(UUID uuid) {
        this.activeAxiomPlayers.remove(uuid);
        this.noPhysicalTriggerPlayers.remove(uuid);
        this.playerPermissions.remove(uuid);
        this.playerRestrictions.remove(uuid);
        this.playerBlockRegistry.remove(uuid);
        this.playerProtocolVersion.remove(uuid);
        this.lastPlotBoundsForPlayers.remove(uuid);
        synchronized (this.dispatchSendsLock) {
            this.availableDispatchSends.removeInt(uuid);
        }
        cancelPlayerTickTask(uuid);
    }

    private void cancelPlayerTickTask(UUID uuid) {
        ScheduledTask task = this.playerTickTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Gizmo markers are tracked from these events rather than by walking the level's entity list,
     * which is not region scoped. Both fire on the region owning the entity on Folia.
     */
    @EventHandler
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!this.sendMarkers) {
            return;
        }
        if (event.getEntity() instanceof org.bukkit.entity.Marker marker) {
            WorldExtension.get((ServerLevel) ((CraftWorld) marker.getWorld()).getHandle()).trackMarker(marker);
        }
    }

    @EventHandler
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (!this.sendMarkers) {
            return;
        }
        if (event.getEntity() instanceof org.bukkit.entity.Marker marker) {
            WorldExtension.get((ServerLevel) ((CraftWorld) marker.getWorld()).getHandle()).untrackMarker(marker);
        }
    }

    /**
     * Entities that already existed when the plugin started never fire
     * {@link EntityAddToWorldEvent}, so seed the registry from the currently loaded entities once.
     *
     * <p>Skipped on a regionised server: there is no single thread allowed to enumerate a level's
     * entities there, so gizmos placed before the plugin loaded are picked up as their chunks load
     * instead (the add event fires for every entity entering a world, including on chunk load).
     */
    private void seedExistingMarkers() {
        if (!this.sendMarkers) {
            return;
        }

        if (AxiomScheduler.isFolia()) {
            this.getLogger().info("Gizmo marker seeding is skipped on regionised servers; markers will appear as their chunks load");
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            Collection<org.bukkit.entity.Marker> markers;
            try {
                markers = world.getEntitiesByClass(org.bukkit.entity.Marker.class);
            } catch (Throwable t) {
                this.getLogger().warning("Unable to seed gizmo markers for world " + world.getName() + ": " + t);
                continue;
            }
            WorldExtension extension = WorldExtension.get((ServerLevel) ((CraftWorld) world).getHandle());
            for (org.bukkit.entity.Marker marker : markers) {
                extension.trackMarker(marker);
            }
        }
    }

    public void addPendingOperation(ServerLevel level, PendingOperation operation) {
        if (operation.level() != level) {
            throw new IllegalArgumentException("Operation was built for a different level");
        }
        this.operationQueue.add(operation);
    }

    private int getAllowedDispatchSendsPerSecond(Player player) {
        for (Map.Entry<String, Integer> entry : this.allowedDispatchSendOverrides.entrySet()) {
            if (player.hasPermission("axiomlimits." + entry.getKey())) {
                return entry.getValue();
            }
        }
        return this.defaultAllowedDispatchSendsPerSecond;
    }

    public boolean consumeDispatchSends(Player player, int sends, int clientAvailableDispatchSends) {
        int allowedDispatchSendsPerSecond = this.getAllowedDispatchSendsPerSecond(player);

        int currentSends;
        synchronized (this.dispatchSendsLock) {
            currentSends = this.availableDispatchSends.getOrDefault(player.getUniqueId(), allowedDispatchSendsPerSecond*20);
            currentSends -= sends*20;
            currentSends = Math.min(currentSends, clientAvailableDispatchSends*20);
            this.availableDispatchSends.put(player.getUniqueId(), currentSends);
        }

        if (currentSends < -allowedDispatchSendsPerSecond*20) {
            player.kick(net.kyori.adventure.text.Component.text("You are sending updates too fast!"));
            return false;
        } else {
            return true;
        }
    }

    public void onAxiomActive(Player player) {
        this.activeAxiomPlayers.add(player.getUniqueId());
        this.failedPermissionAxiomPlayers.remove(player.getUniqueId());

        this.playerPermissions.remove(player.getUniqueId());
        this.playerRestrictions.remove(player.getUniqueId());

        ensurePlayerTickTask(player);
        tickPlayer(player, true);
    }

    private void tickPlayer(Player player, boolean updateRestrictions) {
        int allowedDispatchSendsPerSecond = this.getAllowedDispatchSendsPerSecond(player);

        boolean first;
        int previousAllowed20 = 0;
        int newAllowed20;
        synchronized (this.dispatchSendsLock) {
            if (!this.availableDispatchSends.containsKey(player.getUniqueId())) {
                first = true;
                newAllowed20 = allowedDispatchSendsPerSecond*20;
            } else {
                first = false;
                previousAllowed20 = this.availableDispatchSends.getInt(player.getUniqueId());
                newAllowed20 = Math.min(allowedDispatchSendsPerSecond*20, previousAllowed20 + allowedDispatchSendsPerSecond);
            }
            this.availableDispatchSends.put(player.getUniqueId(), newAllowed20);
        }

        if (first) {
            sendUpdateAvailableDispatchSends(player, allowedDispatchSendsPerSecond, allowedDispatchSendsPerSecond);
        } else {
            int previousAllowed = previousAllowed20 / 20;
            int newAllowed = newAllowed20 / 20;
            if (previousAllowed != newAllowed) {
                sendUpdateAvailableDispatchSends(player, newAllowed - previousAllowed, allowedDispatchSendsPerSecond);
            }
        }

        if (updateRestrictions) {
            Restrictions restrictions = this.calculateRestrictions(player);

            boolean restrictionsChanged;

            if (this.playerRestrictions.containsKey(player.getUniqueId())) {
                Restrictions oldRestrictions = this.playerRestrictions.get(player.getUniqueId());
                restrictionsChanged = !Objects.equals(restrictions, oldRestrictions);
            } else {
                restrictionsChanged = true;
            }

            if (restrictionsChanged) {
                restrictions.send(player);
                this.playerRestrictions.put(player.getUniqueId(), restrictions);
            }
        }
    }

    private void sendUpdateAvailableDispatchSends(Player player, int add, int max) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(add);
        buf.writeVarInt(max);
        byte[] bytes = ByteBufUtil.getBytes(buf);
        VersionHelper.sendCustomPayload(player, "axiom:update_available_dispatch_sends", bytes);
    }

    private Restrictions calculateRestrictions(Player player) {
        if (player.isOp() || player.hasPermission("axiom.all")) {
            Restrictions restrictions = new Restrictions();
            restrictions.allowedPermissions = EnumSet.of(AxiomPermission.ALL);
            restrictions.infiniteReachLimit = this.infiniteReachLimit;
            return restrictions;
        }

        AxiomPermissionSet permissionSet = this.getPermissions(player);

        if (permissionSet.contains(AxiomPermission.ALL)) {
            Restrictions restrictions = new Restrictions();
            restrictions.allowedPermissions = EnumSet.of(AxiomPermission.ALL);
            restrictions.infiniteReachLimit = this.infiniteReachLimit;
            return restrictions;
        }

        Set<PlotSquaredIntegration.PlotBox> bounds = Set.of();

        if (!permissionSet.contains(AxiomPermission.ALLOW_COPYING_OTHER_PLOTS)) {
            if (PlotSquaredIntegration.isPlotWorld(player.getWorld())) {
                PlotSquaredIntegration.PlotBounds editable = PlotSquaredIntegration.getCurrentEditablePlot(player);
                if (editable != null) {
                    lastPlotBoundsForPlayers.put(player.getUniqueId(), editable);
                    bounds = editable.boxes();
                } else {
                    PlotSquaredIntegration.PlotBounds lastPlotBounds = lastPlotBoundsForPlayers.get(player.getUniqueId());
                    if (lastPlotBounds != null && lastPlotBounds.worldName().equals(player.getWorld().getName())) {
                        bounds = lastPlotBounds.boxes();
                    } else {
                        bounds = Set.of(new PlotSquaredIntegration.PlotBox(BlockPos.ZERO, BlockPos.ZERO));
                    }
                }
            }

            if (bounds.size() == 1) {
                PlotSquaredIntegration.PlotBox plotBounds = bounds.iterator().next();

                int min = Integer.MIN_VALUE;
                int max = Integer.MAX_VALUE;

                if (plotBounds.min().getX() == min && plotBounds.min().getY() == min && plotBounds.min().getZ() == min &&
                        plotBounds.max().getX() == max && plotBounds.max().getY() == max && plotBounds.max().getZ() == max) {
                    bounds = Set.of();
                }
            }
        }

        EnumSet<AxiomPermission> allowed = EnumSet.noneOf(AxiomPermission.class);
        EnumSet<AxiomPermission> denied = EnumSet.noneOf(AxiomPermission.class);

        for (AxiomPermission permission : permissionSet.explicitlyAllowed) {
            if (permission.parent != null && permissionSet.explicitlyAllowed.contains(permission.parent)) {
                continue;
            }
            allowed.add(permission);
        }
        for (AxiomPermission permission : permissionSet.explicitlyDenied) {
            if (permission.parent != null && permissionSet.explicitlyDenied.contains(permission.parent)) {
                continue;
            }
            denied.add(permission);
        }

        Restrictions restrictions = new Restrictions();
        restrictions.allowedPermissions = allowed;
        restrictions.deniedPermissions = denied;
        restrictions.infiniteReachLimit = this.infiniteReachLimit;
        restrictions.bounds = bounds;
        return restrictions;
    }

    private void registerPacketHandler(String name, PacketHandler handler, Messenger messenger, LargePayloadBehaviour behaviour,
                                       Map<String, PacketHandler> largePayloadHandlers) {
        boolean isLargePayload = switch (behaviour) {
            case DEFAULT -> this.configuration.getBoolean("allow-large-payload-for-all-packets");
            case FORCE_LARGE -> true;
            case FORCE_SMALL -> false;
        };

        if (isLargePayload) {
            largePayloadHandlers.put("axiom:"+name, handler);
            messenger.registerIncomingPluginChannel(this, "axiom:"+name, new DummyPacketListener());
        } else {
            messenger.registerIncomingPluginChannel(this, "axiom:"+name, new WrapperPacketListener(handler));
        }
    }

    public <T> IntFunction<T> limitCollection(IntFunction<T> applier) {
        return FriendlyByteBuf.limitValue(applier, this.packetCollectionReadLimit);
    }

    public NbtAccounter createNbtAccounter() {
        return NbtAccounter.create(this.maxNbtDecompressLimit);
    }

    public boolean logLargeBlockBufferChanges() {
        return this.logLargeBlockBufferChanges;
    }

    public boolean canUseAxiom(Player player) {
        return this.activeAxiomPlayers.contains(player.getUniqueId());
    }

    public boolean canUseAxiom(Player player, AxiomPermission axiomPermission) {
        return this.activeAxiomPlayers.contains(player.getUniqueId()) && hasPermission(player, axiomPermission);
    }

    public AxiomPermissionSet getPermissions(Player player) {
        UUID uuid = player.getUniqueId();
        AxiomPermissionSet cached = this.playerPermissions.get(uuid);
        if (cached != null) {
            return cached;
        }

        // Deliberately not inside computeIfAbsent: calculatePermissions reads entity state and we
        // do not want to hold a map bin (and therefore block other players) while doing it.
        AxiomPermissionSet calculated = calculatePermissions(player);
        AxiomPermissionSet existing = this.playerPermissions.putIfAbsent(uuid, calculated);
        return existing != null ? existing : calculated;
    }

    public boolean hasPermission(Player player, AxiomPermission axiomPermission) {
        if (player.isOp()) {
            return true;
        }
        return this.getPermissions(player).contains(axiomPermission);
    }

    private AxiomPermissionSet calculatePermissions(Player player) {
        if (player.isOp()) {
            return AxiomPermissionSet.NONE;
        }

        EnumSet<AxiomPermission> allowed = EnumSet.noneOf(AxiomPermission.class);
        EnumSet<AxiomPermission> denied = EnumSet.noneOf(AxiomPermission.class);

        for (AxiomPermission permission : AxiomPermission.values()) {
            TriState value = player.permissionValue(permission.getPermissionNode());
            switch (value) {
                case FALSE -> denied.add(permission);
                case NOT_SET -> {
                }
                case TRUE -> allowed.add(permission);
            }
        }

        return new AxiomPermissionSet(allowed, denied);
    }

    public boolean canEntityBeManipulated(EntityType<?> entityType) {
        if (entityType == EntityType.PLAYER) {
            return false;
        }
        if (!this.whitelistedEntities.isEmpty() && !this.whitelistedEntities.contains(entityType)) {
            return false;
        }
        if (this.blacklistedEntities.contains(entityType)) {
            return false;
        }
        return true;
    }

    public boolean isNoPhysicalTrigger(UUID uuid) {
        return this.noPhysicalTriggerPlayers.contains(uuid);
    }

    public void setNoPhysicalTrigger(UUID uuid, boolean noPhysicalTrigger) {
        if (noPhysicalTrigger) {
            if (!this.registeredNoPhysicalTriggerListener) {
                this.registeredNoPhysicalTriggerListener = true;
                Bukkit.getPluginManager().registerEvents(new NoPhysicalTriggerListener(this), this);
            }

            this.noPhysicalTriggerPlayers.add(uuid);
        } else {
            this.noPhysicalTriggerPlayers.remove(uuid);
        }
    }

    public boolean isMismatchedDataVersion(UUID uuid) {
        return this.playerProtocolVersion.containsKey(uuid);
    }

    public int getProtocolVersionFor(UUID uuid) {
        Integer version = this.playerProtocolVersion.get(uuid);
        return version != null ? version : SharedConstants.getProtocolVersion();
    }

    public IdMapper<BlockState> getBlockRegistry(UUID uuid) {
        IdMapper<BlockState> registry = this.playerBlockRegistry.get(uuid);
        return registry != null ? registry : this.allowedBlockRegistry;
    }

    private final Map<World, ServerWorldPropertiesRegistry> worldProperties = Collections.synchronizedMap(new WeakHashMap<>());

    public @Nullable ServerWorldPropertiesRegistry getWorldPropertiesIfPresent(World world) {
        return this.worldProperties.get(world);
    }

    public @Nullable ServerWorldPropertiesRegistry getOrCreateWorldProperties(World world) {
        ServerWorldPropertiesRegistry existing = this.worldProperties.get(world);
        if (existing != null) {
            return existing;
        }

        synchronized (this.worldProperties) {
            if (this.worldProperties.containsKey(world)) {
                return this.worldProperties.get(world);
            }
            ServerWorldPropertiesRegistry properties = createWorldProperties(world);
            if (properties != null) {
                this.worldProperties.put(world, properties);
            }
            return properties;
        }
    }

    public boolean canModifyWorld(Player player, World world) {
        String whitelist = this.whitelistWorldRegex;
        if (whitelist != null && !whitelist.isBlank() && !world.getName().matches(whitelist)) {
            return false;
        }

        String blacklist = this.blacklistWorldRegex;
        if (blacklist != null && !blacklist.isBlank() && world.getName().matches(blacklist)) {
            return false;
        }

        AxiomModifyWorldEvent modifyWorldEvent = new AxiomModifyWorldEvent(player, world);
        Bukkit.getPluginManager().callEvent(modifyWorldEvent);
        return !modifyWorldEvent.isCancelled();
    }

    @EventHandler
    public void onPluginUnload(PluginDisableEvent disableEvent) {
        ImplServerCustomBlocks.unregisterAll(disableEvent.getPlugin());
        ImplServerCustomDisplays.unregisterAll(disableEvent.getPlugin());
    }

    @EventHandler
    public void onFailMove(PlayerFailMoveEvent event) {
        if (!this.canUseAxiom(event.getPlayer(), AxiomPermission.PLAYER_BYPASS_MOVEMENT_RESTRICTIONS)) {
            return;
        }
        if (event.getFailReason() == PlayerFailMoveEvent.FailReason.MOVED_INTO_UNLOADED_CHUNK) {
            return;
        }
        if (!event.getPlayer().getWorld().isChunkLoaded(event.getTo().getBlockX() >> 4, event.getTo().getBlockZ() >> 4)) {
            return;
        }

        if (event.getFailReason() == PlayerFailMoveEvent.FailReason.MOVED_TOO_QUICKLY) {
            event.setAllowed(true); // Support for arcball camera
        } else if (event.getPlayer().isFlying()) {
            event.setAllowed(true); // Support for noclip
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        this.clearCachedPermissionsFor(event.getPlayer().getUniqueId());

        if (!this.activeAxiomPlayers.contains(event.getPlayer().getUniqueId())) {
            return;
        }

        World world = event.getPlayer().getWorld();

        ServerWorldPropertiesRegistry properties = getOrCreateWorldProperties(world);

        if (properties == null) {
            VersionHelper.sendCustomPayload(event.getPlayer(), "axiom:register_world_properties", new byte[]{0});
        } else {
            properties.registerFor(this, event.getPlayer());
        }

        WorldExtension.onPlayerJoin(world, event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        this.failedPermissionAxiomPlayers.remove(uuid);
        deactivateAxiomPlayer(uuid);
    }

    @EventHandler
    public void onGameRuleChanged(WorldGameRuleChangeEvent event) {
        if (event.getGameRule() == GameRules.ADVANCE_WEATHER) {
            ServerWorldPropertiesRegistry.PAUSE_WEATHER.setValue(event.getWorld(), !Boolean.parseBoolean(event.getValue()));
        }
    }

    private ServerWorldPropertiesRegistry createWorldProperties(World world) {
        ServerWorldPropertiesRegistry registry = new ServerWorldPropertiesRegistry(new WeakReference<>(world));

        AxiomCreateWorldPropertiesEvent createEvent = new AxiomCreateWorldPropertiesEvent(world, registry);
        Bukkit.getPluginManager().callEvent(createEvent);
        if (createEvent.isCancelled()) return null;

        return registry;
    }

}
