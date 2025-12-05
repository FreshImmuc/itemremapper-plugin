package com.itemremapper;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.BlockPosition;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener that handles jukebox music disc sound replacement
 */
public class JukeboxListener implements Listener {

    private final ItemRemapperPlugin plugin;
    private ProtocolManager protocolManager;
    private boolean protocolLibAvailable = false;
    
    // Jukebox tracking
    private final Map<Location, ActiveJukebox> activeJukeboxes = new ConcurrentHashMap<>();
    private final Set<Location> knownJukeboxes = ConcurrentHashMap.newKeySet();
    private long lastDiscoveryTime = 0;
    private static final long DISCOVERY_INTERVAL = 5000; // 5 seconds between full discoveries
    
    // Message tracking
    private final Map<String, Long> sentCustomMessages = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN = 2000; // 2 seconds
    
    // Track jukebox positions that just had a disc inserted
    // We only cancel sounds coming from these specific locations
    private final Map<String, Long> pendingJukeboxSounds = new ConcurrentHashMap<>();
    private static final long JUKEBOX_SOUND_WINDOW = 500; // 500ms window to cancel original sound
    
    // Config values
    private boolean jukeboxEnabled;
    private int soundRange;
    private float volume;
    private float pitch;
    private boolean autoEject;
    
    /**
     * Represents an active jukebox playing a custom sound
     */
    private static class ActiveJukebox {
        final String discType;
        final String customSound;
        final int duration;
        final Set<UUID> playersHearing;
        final ScheduledTask ejectionTask;
        final ScheduledTask playerTrackingTask;
        final long startTime;
        
        ActiveJukebox(String discType, String customSound, int duration, 
                     ScheduledTask ejectionTask, ScheduledTask playerTrackingTask) {
            this.discType = discType;
            this.customSound = customSound;
            this.duration = duration;
            this.playersHearing = ConcurrentHashMap.newKeySet();
            this.ejectionTask = ejectionTask;
            this.playerTrackingTask = playerTrackingTask;
            this.startTime = System.currentTimeMillis();
        }
        
        void cancel() {
            if (ejectionTask != null && !ejectionTask.isCancelled()) {
                ejectionTask.cancel();
            }
            if (playerTrackingTask != null && !playerTrackingTask.isCancelled()) {
                playerTrackingTask.cancel();
            }
        }
    }

    public JukeboxListener(ItemRemapperPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    /**
     * Load configuration values
     */
    private void loadConfig() {
        jukeboxEnabled = plugin.getConfig().getBoolean("jukebox.enabled", true);
        soundRange = plugin.getConfig().getInt("jukebox.sound-range", 64);
        volume = (float) plugin.getConfig().getDouble("jukebox.volume", 1.0);
        pitch = (float) plugin.getConfig().getDouble("jukebox.pitch", 1.0);
        autoEject = plugin.getConfig().getBoolean("jukebox.auto-eject", true);
    }

    /**
     * Sets up ProtocolLib packet listener if available
     */
    public void setupProtocolLib() {
        if (!jukeboxEnabled) {
            plugin.getLogger().info("Jukebox sound replacement is disabled in config");
            return;
        }
        
        try {
            if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
                protocolManager = ProtocolLibrary.getProtocolManager();
                protocolLibAvailable = true;
                
                // Intercept NAMED_SOUND_EFFECT packets (music discs)
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.NAMED_SOUND_EFFECT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        handleNamedSoundPacket(event);
                    }
                });
                
                // Debug: Intercept WORLD_EVENT packets (jukeboxes trigger this)
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.WORLD_EVENT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        handleWorldEventPacket(event);
                    }
                });
                
                // Intercept SYSTEM_CHAT packets (status bar messages)
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.SYSTEM_CHAT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        handleChatPacket(event);
                    }
                });
                
                // Intercept SET_ACTION_BAR_TEXT packets
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.SET_ACTION_BAR_TEXT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        handleActionBarPacket(event);
                    }
                });
                
                plugin.getLogger().info("ProtocolLib integration enabled - Jukebox sounds will be replaced");
            } else {
                plugin.getLogger().warning("ProtocolLib not found - Jukebox sound replacement disabled");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to setup ProtocolLib: " + e.getMessage());
            protocolLibAvailable = false;
        }
    }
    
    /**
     * Handles NAMED_SOUND_EFFECT packets - cancels vanilla music disc sounds
     */
    private void handleNamedSoundPacket(PacketEvent event) {
        try {
            PacketContainer packet = event.getPacket();
            
            // Try multiple approaches to get the sound name
            String soundName = null;
            
            // Approach 1: Try getting as a Sound object
            try {
                Sound sound = packet.getSoundEffects().read(0);
                if (sound != null) {
                    soundName = sound.getKey().getKey();
                }
            } catch (Exception ignored) {}
            
            // Approach 2: Try getting as a string directly
            if (soundName == null) {
                try {
                    soundName = packet.getStrings().read(0);
                } catch (Exception ignored) {}
            }
            
            // Approach 3: Try getting via generic modifier
            if (soundName == null) {
                try {
                    Object soundObj = packet.getModifier().read(0);
                    if (soundObj != null) {
                        soundName = soundObj.toString();
                    }
                } catch (Exception ignored) {}
            }
            
            // Debug: Log all sound packets if debug is enabled
            if (plugin.isDebugMode() && soundName != null) {
                plugin.debug("NAMED_SOUND_EFFECT packet: " + soundName);
            }
            
            // Check if it's a music disc sound
            if (soundName != null && (soundName.contains("music_disc") || 
                soundName.contains("music.disc") || soundName.contains("record"))) {
                
                // Get the sound position (coordinates are in fixed-point format * 8)
                try {
                    int x = packet.getIntegers().read(0) / 8;
                    int y = packet.getIntegers().read(1) / 8;
                    int z = packet.getIntegers().read(2) / 8;
                    
                    String posKey = x + "," + y + "," + z;
                    
                    // Only cancel if this position is in our pending list (recent disc insert)
                    long currentTime = System.currentTimeMillis();
                    Long insertTime = pendingJukeboxSounds.get(posKey);
                    
                    if (insertTime != null && (currentTime - insertTime) <= JUKEBOX_SOUND_WINDOW) {
                        event.setCancelled(true);
                        plugin.debug("✓ Cancelled NAMED_SOUND_EFFECT from tracked jukebox at " + posKey + ": " + soundName);
                    } else {
                        plugin.debug("Allowing NAMED_SOUND_EFFECT (not from tracked jukebox): " + soundName + " at " + posKey);
                    }
                } catch (Exception e) {
                    plugin.debug("Could not get sound position, allowing: " + soundName);
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error in NAMED_SOUND_EFFECT handler: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Handles WORLD_EVENT packets - jukeboxes use event ID 1010 to play/stop
     */
    private void handleWorldEventPacket(PacketEvent event) {
        try {
            PacketContainer packet = event.getPacket();
            
            // Get the event ID (1010 = jukebox plays record, 1011 = jukebox stops)
            int eventId = packet.getIntegers().read(0);
            
            // 1010 = Play record, 1011 = Stop playing record
            if (eventId == 1010) {
                // Get the block position from the packet
                BlockPosition blockPos = packet.getBlockPositionModifier().read(0);
                String posKey = blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
                
                // Check if this jukebox is in our pending list
                long currentTime = System.currentTimeMillis();
                Long insertTime = pendingJukeboxSounds.get(posKey);
                
                if (insertTime != null && (currentTime - insertTime) <= JUKEBOX_SOUND_WINDOW) {
                    // Cancel the world event from tracked jukebox
                    event.setCancelled(true);
                    plugin.debug("✓ Cancelled WORLD_EVENT 1010 from tracked jukebox at " + posKey);
                } else {
                    plugin.debug("Allowing WORLD_EVENT 1010 (not from tracked jukebox) at " + posKey);
                }
            } else if (eventId == 1011) {
                if (plugin.isDebugMode()) {
                    plugin.debug("WORLD_EVENT 1011 (jukebox stop)");
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error in WORLD_EVENT handler: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handles system chat packets - cancels vanilla jukebox messages
     */
    private void handleChatPacket(PacketEvent event) {
        try {
            PacketContainer packet = event.getPacket();
            WrappedChatComponent component = packet.getChatComponents().read(0);
            if (component == null) return;
            
            String json = component.getJson();
            
            if (json.contains("record.nowPlaying") || 
                (json.contains("translate") && json.contains("record."))) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Cancelled vanilla SYSTEM_CHAT jukebox message");
                }
                
                Player player = event.getPlayer();
                Bukkit.getRegionScheduler().runDelayed(plugin, player.getLocation(), (task) -> {
                    sendReplacementMessage(player);
                }, 1L);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error in chat packet handler: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handles action bar packets - cancels vanilla jukebox messages
     */
    private void handleActionBarPacket(PacketEvent event) {
        try {
            PacketContainer packet = event.getPacket();
            WrappedChatComponent component = packet.getChatComponents().read(0);
            if (component == null) return;
            
            String json = component.getJson();
            
            if (json.contains("record.nowPlaying") || 
                (json.contains("translate") && json.contains("record."))) {
                event.setCancelled(true);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Cancelled vanilla ACTION_BAR jukebox message");
                }
                
                Player player = event.getPlayer();
                Bukkit.getRegionScheduler().runDelayed(plugin, player.getLocation(), (task) -> {
                    sendReplacementMessage(player);
                }, 1L);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error in action bar packet handler: " + e.getMessage());
            }
        }
    }

    /**
     * Handles player interaction with jukeboxes
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!jukeboxEnabled || !protocolLibAvailable) return;
        
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;
        
        ItemStack item = event.getItem();
        
        // Handle disc insertion
        if (item != null && item.getType().name().startsWith("MUSIC_DISC_")) {
            Location jukeboxLoc = block.getLocation();
            knownJukeboxes.add(jukeboxLoc);
            
            // Check if this disc type has a custom sound configured
            String discType = item.getType().name();
            ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(discType);
            
            // Only track jukebox if the disc has a custom sound configured
            if (remap != null && remap.hasCustomSound()) {
                String posKey = jukeboxLoc.getBlockX() + "," + jukeboxLoc.getBlockY() + "," + jukeboxLoc.getBlockZ();
                pendingJukeboxSounds.put(posKey, System.currentTimeMillis());
                plugin.debug("Tracked jukebox at " + posKey + " for sound cancellation (custom sound: " + remap.getCustomSound() + ")");
            } else {
                plugin.debug("Disc " + discType + " has no custom sound - allowing vanilla playback");
            }
            
            // Schedule disc insertion check
            Bukkit.getRegionScheduler().runDelayed(plugin, jukeboxLoc, (task) -> {
                if (block.getState() instanceof Jukebox jukebox) {
                    handleDiscInsertion(jukebox);
                }
            }, 3L);
        } 
        // Handle disc removal
        else if (item == null || item.getType() == Material.AIR) {
            if (block.getState() instanceof Jukebox jukebox) {
                ItemStack currentDisc = jukebox.getRecord();
                if (currentDisc != null && currentDisc.getType() != Material.AIR) {
                    // Player is removing the disc
                    Location loc = jukebox.getLocation();
                    Bukkit.getRegionScheduler().runDelayed(plugin, loc, (task) -> {
                        handleDiscRemoval(loc);
                    }, 1L);
                }
            }
        }
    }
    
    /**
     * Handles when a disc is inserted into a jukebox
     */
    private void handleDiscInsertion(Jukebox jukebox) {
        Location jukeboxLoc = jukebox.getLocation();
        ItemStack record = jukebox.getRecord();
        
        if (record == null || record.getType() == Material.AIR) return;
        
        String discType = record.getType().name();
        ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(discType);
        
        if (remap == null || !remap.hasCustomSound()) {
            plugin.debug("No custom sound configured for " + discType);
            return;
        }
        
        // Stop any existing playback at this location
        stopJukeboxPlayback(jukeboxLoc);
        
        String customSound = remap.getCustomSound();
        int duration = remap.getDuration();
        
        plugin.debug("Starting custom sound playback: " + customSound + " (duration: " + duration + "s)");
        
        // Schedule auto-ejection if enabled
        ScheduledTask ejectionTask = null;
        if (autoEject && duration > 0) {
            ejectionTask = Bukkit.getRegionScheduler().runDelayed(plugin, jukeboxLoc, (task) -> {
                ejectDisc(jukeboxLoc);
            }, duration * 20L); // Convert seconds to ticks
        }
        
        // Start player tracking task
        ScheduledTask trackingTask = Bukkit.getRegionScheduler().runAtFixedRate(plugin, jukeboxLoc, (task) -> {
            updatePlayersInRange(jukeboxLoc);
        }, 1L, 20L); // Check every second
        
        // Create active jukebox entry
        ActiveJukebox activeJukebox = new ActiveJukebox(
            discType, customSound, duration, ejectionTask, trackingTask
        );
        
        activeJukeboxes.put(jukeboxLoc, activeJukebox);
        
        // Initial playback to all players in range
        playCustomSoundToNearbyPlayers(jukeboxLoc, customSound);
        
        // Send custom message
        sendCustomMessageToNearbyPlayers(jukebox);
    }
    
    /**
     * Monitors players and stops sound for those who leave range
     * Note: Does NOT start sound for players entering range mid-song
     */
    private void updatePlayersInRange(Location jukeboxLoc) {
        ActiveJukebox activeJukebox = activeJukeboxes.get(jukeboxLoc);
        if (activeJukebox == null) return;
        
        World world = jukeboxLoc.getWorld();
        if (world == null) return;
        
        Set<UUID> currentPlayersInRange = new HashSet<>();
        
        // Find all players currently in range
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(jukeboxLoc) <= soundRange) {
                currentPlayersInRange.add(player.getUniqueId());
            }
        }
        
        // Find players who left range (they were hearing, but are no longer in range)
        Set<UUID> leftPlayers = new HashSet<>(activeJukebox.playersHearing);
        leftPlayers.removeAll(currentPlayersInRange);
        
        // Stop sound ONLY for players who left range
        for (UUID uuid : leftPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                // Stop the sound for players who left range
                player.stopSound(activeJukebox.customSound);
                activeJukebox.playersHearing.remove(uuid);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Player " + player.getName() + " left jukebox range, stopping sound");
                }
            }
        }
        
        // Note: We do NOT start sound for players entering range
        // Sound only plays for players who were in range when disc was inserted
    }
    
    /**
     * Plays custom sound to all nearby players
     */
    private void playCustomSoundToNearbyPlayers(Location jukeboxLoc, String customSound) {
        World world = jukeboxLoc.getWorld();
        if (world == null) return;
        
        ActiveJukebox activeJukebox = activeJukeboxes.get(jukeboxLoc);
        if (activeJukebox == null) return;
        
        int count = 0;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(jukeboxLoc) <= soundRange) {
                player.playSound(jukeboxLoc, customSound, volume, pitch);
                activeJukebox.playersHearing.add(player.getUniqueId());
                count++;
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Started custom sound for " + count + " players in range");
        }
    }
    
    /**
     * Handles when a disc is removed from a jukebox
     */
    private void handleDiscRemoval(Location jukeboxLoc) {
        stopJukeboxPlayback(jukeboxLoc);
        plugin.debug("Disc removed from jukebox at " + jukeboxLoc);
    }
    
    /**
     * Stops playback at a jukebox location
     */
    private void stopJukeboxPlayback(Location jukeboxLoc) {
        ActiveJukebox activeJukebox = activeJukeboxes.remove(jukeboxLoc);
        if (activeJukebox == null) return;
        
        // Cancel tasks
        activeJukebox.cancel();
        
        // Clean up position tracking
        String posKey = jukeboxLoc.getBlockX() + "," + jukeboxLoc.getBlockY() + "," + jukeboxLoc.getBlockZ();
        pendingJukeboxSounds.remove(posKey);
        
        // Stop sound for all players who were hearing it
        for (UUID uuid : activeJukebox.playersHearing) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.stopSound(activeJukebox.customSound);
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Stopped jukebox playback at " + jukeboxLoc);
        }
    }
    
    /**
     * Ejects the disc from a jukebox
     */
    private void ejectDisc(Location jukeboxLoc) {
        Block block = jukeboxLoc.getBlock();
        if (block.getType() != Material.JUKEBOX) return;
        
        if (block.getState() instanceof Jukebox jukebox) {
            ItemStack record = jukebox.getRecord();
            if (record != null && record.getType() != Material.AIR) {
                // Eject the disc
                jukebox.eject();
                
                // Stop playback
                stopJukeboxPlayback(jukeboxLoc);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Auto-ejected disc from jukebox at " + jukeboxLoc);
                }
            }
        }
    }
    
    /**
     * Sends replacement status bar message to player
     */
    private void sendReplacementMessage(Player player) {
        if (!protocolLibAvailable) return;
        
        // Find nearest jukebox location from cache
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        if (world == null) return;
        
        Location nearestLoc = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (Location loc : knownJukeboxes) {
            if (!loc.getWorld().equals(world)) continue;
            
            double distance = playerLoc.distance(loc);
            if (distance <= soundRange && distance < nearestDistance) {
                nearestLoc = loc;
                nearestDistance = distance;
            }
        }
        
        // If found, schedule message sending on that region
        if (nearestLoc != null) {
            Location finalLoc = nearestLoc;
            Bukkit.getRegionScheduler().run(plugin, finalLoc, (task) -> {
                Block block = finalLoc.getBlock();
                if (block.getType() == Material.JUKEBOX && block.getState() instanceof Jukebox jukebox) {
                    ItemStack record = jukebox.getRecord();
                    if (record != null && record.getType() != Material.AIR) {
                        sendCustomMessageToPlayer(jukebox, player);
                    }
                }
            });
        }
    }
    
    /**
     * Sends custom message to nearby players
     */
    private void sendCustomMessageToNearbyPlayers(Jukebox jukebox) {
        ItemStack record = jukebox.getRecord();
        if (record == null || record.getType() == Material.AIR) return;
        
        String materialName = record.getType().name();
        ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(materialName);
        
        if (remap == null || !remap.hasDisplayName()) return;
        
        String customName = remap.getDisplayName();
        String message = "§7Now playing: " + customName;
        
        // Anti-spam check
        long currentTime = System.currentTimeMillis();
        Long lastSent = sentCustomMessages.get(customName);
        if (lastSent != null && (currentTime - lastSent) < MESSAGE_COOLDOWN) {
            return;
        }
        
        sentCustomMessages.put(customName, currentTime);
        
        Location jukeboxLoc = jukebox.getLocation();
        World world = jukeboxLoc.getWorld();
        if (world == null) return;
        
        TextComponent textComponent = new TextComponent(message);
        int count = 0;
        
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(jukeboxLoc) <= soundRange) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, textComponent);
                count++;
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Sent custom message to " + count + " players: " + customName);
        }
    }
    
    /**
     * Sends custom message to a specific player
     */
    private void sendCustomMessageToPlayer(Jukebox jukebox, Player player) {
        ItemStack record = jukebox.getRecord();
        if (record == null || record.getType() == Material.AIR) return;
        
        String materialName = record.getType().name();
        ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(materialName);
        
        if (remap == null || !remap.hasDisplayName()) return;
        
        String customName = remap.getDisplayName();
        String message = "§7Now playing: " + customName;
        
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }
    

    
    /**
     * Starts the jukebox scanner
     */
    public void startJukeboxScanner() {
        // Fast scanner for state changes (every 0.5 seconds)
        // This catches hopper insertions quickly
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (task) -> this.scanJukeboxes(), 10L, 10L);
        
        if (plugin.isDebugMode()) {
            plugin.getLogger().info("Jukebox scanner started (checks every 0.5s for hopper insertions)");
        }
    }
    
    /**
     * Scans for jukebox state changes - detects both insertions and removals
     */
    private void scanJukeboxes() {
        // Periodically discover new jukeboxes (every 5 seconds)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDiscoveryTime > DISCOVERY_INTERVAL) {
            Bukkit.getAsyncScheduler().runNow(plugin, (task) -> discoverJukeboxes());
            lastDiscoveryTime = currentTime;
        }
        
        // Always check known jukeboxes for state changes
        // Create a copy to avoid concurrent modification
        Set<Location> locationsToCheck = new HashSet<>(knownJukeboxes);
        
        // Group locations by world/region to batch operations
        Map<World, List<Location>> locationsByWorld = new HashMap<>();
        for (Location loc : locationsToCheck) {
            locationsByWorld.computeIfAbsent(loc.getWorld(), k -> new ArrayList<>()).add(loc);
        }
        
        // Process each world's locations in batched region tasks
        for (Map.Entry<World, List<Location>> entry : locationsByWorld.entrySet()) {
            for (Location loc : entry.getValue()) {
                // Schedule check on the appropriate region
                Bukkit.getRegionScheduler().run(plugin, loc, (task) -> {
                    Block block = loc.getBlock();
                    
                    if (block.getType() != Material.JUKEBOX) {
                        knownJukeboxes.remove(loc);
                        stopJukeboxPlayback(loc);
                        return;
                    }
                    
                    if (block.getState() instanceof Jukebox jukebox) {
                        ItemStack record = jukebox.getRecord();
                        ActiveJukebox activeJukebox = activeJukeboxes.get(loc);
                        
                        String currentDiscType = null;
                        if (record != null && record.getType() != Material.AIR) {
                            currentDiscType = record.getType().name();
                        }
                        
                        // Case 1: Disc was removed
                        if (currentDiscType == null && activeJukebox != null) {
                            handleDiscRemoval(loc);
                            plugin.debug("Scanner detected disc removal at " + loc);
                        }
                        // Case 2: New disc was inserted (or different disc)
                        else if (currentDiscType != null && 
                                (activeJukebox == null || !currentDiscType.equals(activeJukebox.discType))) {
                            plugin.debug("Scanner detected disc insertion: " + currentDiscType + " at " + loc);
                            handleDiscInsertion(jukebox);
                        }
                    }
                });
            }
        }
    }
    
    /**
     * Discovers jukeboxes in loaded chunks near players
     * Called from async scheduler to avoid blocking
     */
    private void discoverJukeboxes() {
        for (World world : Bukkit.getWorlds()) {
            // Only scan worlds with players (thread-safe snapshot)
            if (world.getPlayers().isEmpty()) continue;
            
            // Get loaded chunks snapshot (thread-safe)
            org.bukkit.Chunk[] chunks = world.getLoadedChunks();
            
            // Collect chunk locations to scan
            List<Location> chunkLocations = new ArrayList<>();
            for (org.bukkit.Chunk chunk : chunks) {
                // Only scan chunks near players (within 8 chunks)
                if (!hasPlayersNearChunk(chunk, 8)) continue;
                
                // Store chunk center location for scheduling
                chunkLocations.add(chunk.getBlock(8, 64, 8).getLocation());
            }
            
            // Schedule chunk scans on the appropriate regions
            for (Location chunkLoc : chunkLocations) {
                Bukkit.getRegionScheduler().run(plugin, chunkLoc, (task) -> {
                    // Re-get chunk on the correct thread to avoid cross-thread access
                    World taskWorld = chunkLoc.getWorld();
                    if (taskWorld == null) return;
                    
                    // Safely get the chunk on the region thread
                    int chunkX = chunkLoc.getBlockX() >> 4;
                    int chunkZ = chunkLoc.getBlockZ() >> 4;
                    
                    if (!taskWorld.isChunkLoaded(chunkX, chunkZ)) return;
                    
                    org.bukkit.Chunk regionChunk = taskWorld.getChunkAt(chunkX, chunkZ);
                    
                    // Access tile entities on the correct region thread
                    for (org.bukkit.block.BlockState state : regionChunk.getTileEntities()) {
                        if (state instanceof Jukebox) {
                            Location loc = state.getLocation();
                            if (!knownJukeboxes.contains(loc)) {
                                knownJukeboxes.add(loc);
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Discovered jukebox at " + loc);
                                }
                            }
                        }
                    }
                });
            }
        }
    }
    
    /**
     * Checks if any players are near a chunk
     */
    private boolean hasPlayersNearChunk(org.bukkit.Chunk chunk, int chunkRadius) {
        World world = chunk.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        
        for (Player player : world.getPlayers()) {
            int playerChunkX = player.getLocation().getBlockX() >> 4;
            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            
            if (Math.abs(playerChunkX - chunkX) <= chunkRadius && 
                Math.abs(playerChunkZ - chunkZ) <= chunkRadius) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Starts cleanup task
     */
    public void startCacheCleanupTask() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (task) -> {
            long currentTime = System.currentTimeMillis();
            
            // Clean up old message timestamps
            sentCustomMessages.entrySet().removeIf(entry -> 
                (currentTime - entry.getValue()) > MESSAGE_COOLDOWN * 2
            );
            
            // Clean up old jukebox position timestamps
            pendingJukeboxSounds.entrySet().removeIf(entry ->
                (currentTime - entry.getValue()) > JUKEBOX_SOUND_WINDOW * 2
            );
        }, 100L, 100L);
    }

    /**
     * Cleanup on disable
     */
    public void cleanup() {
        if (protocolLibAvailable && protocolManager != null) {
            protocolManager.removePacketListeners(plugin);
        }
        
        // Stop all active jukeboxes
        for (Location loc : new HashSet<>(activeJukeboxes.keySet())) {
            stopJukeboxPlayback(loc);
        }
        
        activeJukeboxes.clear();
        sentCustomMessages.clear();
        knownJukeboxes.clear();
    }
}
