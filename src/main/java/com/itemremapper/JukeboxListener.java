package com.itemremapper;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener that handles jukebox music disc renaming
 */
public class JukeboxListener implements Listener {

    private final ItemRemapperPlugin plugin;
    private ProtocolManager protocolManager;
    private boolean protocolLibAvailable = false;
    
    // Thread-safe cache to store which jukeboxes are currently playing and what disc
    private final Map<Location, String> activeJukeboxes = new ConcurrentHashMap<>();
    
    // Set to track our own custom messages to prevent infinite loops
    private final Map<String, Long> sentCustomMessages = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN = 2000; // 2 seconds cooldown
    
    // Performance optimization: Known jukebox locations for faster scanning
    private final Set<Location> knownJukeboxes = ConcurrentHashMap.newKeySet();
    
    // Performance optimization: Track scanned chunks to avoid duplicates
    private final Set<String> scannedChunksThisTick = ConcurrentHashMap.newKeySet();
    
    // Performance optimization: Batch processing
    private final Queue<Jukebox> jukeboxesToCheck = new LinkedList<>();
    private static final int MAX_JUKEBOXES_PER_TICK = 5; // Limit processing per tick

    public JukeboxListener(ItemRemapperPlugin plugin) {
        this.plugin = plugin;
        // Don't setup ProtocolLib in constructor - will be called from onEnable
    }

    /**
     * Sets up ProtocolLib packet listener if available
     */
    public void setupProtocolLib() {
        try {
            // Check if ProtocolLib is available
            if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
                protocolManager = ProtocolLibrary.getProtocolManager();
                protocolLibAvailable = true;
                
                // Reference to the plugin for use in inner class
                final ItemRemapperPlugin pluginRef = plugin;
                
                // Listen for SYSTEM_CHAT packets (1.19+) - optimized
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.SYSTEM_CHAT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        try {
                            PacketContainer packet = event.getPacket();
                            
                            // Fast pre-check - get component without full parsing
                            WrappedChatComponent component = packet.getChatComponents().read(0);
                            if (component == null) return;
                            
                            String json = component.getJson();
                            
                            // Ultra-fast vanilla jukebox detection - check JSON directly
                            if (json.contains("record.nowPlaying") || 
                                (json.contains("translate") && json.contains("record."))) {
                                
                                // Cancel vanilla message immediately
                                event.setCancelled(true);
                                
                                if (pluginRef.isDebugMode()) {
                                    pluginRef.debug("Intercepted vanilla SYSTEM_CHAT jukebox message");
                                }
                                
                                // Schedule replacement message (minimal delay)
                                Player player = event.getPlayer();
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    sendReplacementJukeboxMessage(player);
                                }, 1L);
                            }
                        } catch (Exception e) {
                            if (pluginRef.isDebugMode()) {
                                pluginRef.debug("Error in SYSTEM_CHAT packet handler: " + e.getMessage());
                            }
                        }
                    }
                });
                
                // Listen for SET_ACTION_BAR_TEXT packets (action bar) - optimized
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.SET_ACTION_BAR_TEXT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        try {
                            PacketContainer packet = event.getPacket();
                            
                            // Fast component check
                            WrappedChatComponent component = packet.getChatComponents().read(0);
                            if (component == null) return;
                            
                            String json = component.getJson();
                            
                            // Fast vanilla jukebox detection
                            if (json.contains("record.nowPlaying") || 
                                (json.contains("translate") && json.contains("record."))) {
                                
                                // Cancel vanilla message
                                event.setCancelled(true);
                                
                                if (pluginRef.isDebugMode()) {
                                    pluginRef.debug("Intercepted vanilla ACTION_BAR jukebox message");
                                }
                                
                                // Schedule replacement
                                Player player = event.getPlayer();
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    sendReplacementJukeboxMessage(player);
                                }, 1L);
                            }
                        } catch (Exception e) {
                            if (pluginRef.isDebugMode()) {
                                pluginRef.debug("Error in ACTION_BAR packet handler: " + e.getMessage());
                            }
                        }
                    }
                });
                
                plugin.getLogger().info("ProtocolLib integration enabled - Jukebox messages will be customized");
            } else {
                plugin.getLogger().warning("ProtocolLib not found - Jukebox name customization disabled");
                plugin.getLogger().warning("Install ProtocolLib to enable custom jukebox messages: https://www.spigotmc.org/resources/protocollib.1997/");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to setup ProtocolLib: " + e.getMessage());
            protocolLibAvailable = false;
        }
    }

    /**
     * Handles when a player interacts with a jukebox
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) {
            return;
        }
        
        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().startsWith("MUSIC_DISC_")) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Schedule a check for the next tick after the disc is inserted
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (block.getState() instanceof Jukebox) {
                Jukebox jukebox = (Jukebox) block.getState();
                sendCustomJukeboxMessage(jukebox, player);
            }
        }, 2L);
    }

    /**
     * Handles when items are moved by hoppers or other automation into jukeboxes
     * This event is mainly used for debugging - the actual detection is handled by the jukebox scanner
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        // Check if the destination is a jukebox
        if (event.getDestination().getHolder() instanceof Jukebox) {
            Jukebox jukebox = (Jukebox) event.getDestination().getHolder();
            ItemStack item = event.getItem();
            
            // Check if it's a music disc
            if (item != null && item.getType().name().startsWith("MUSIC_DISC_")) {
                plugin.debug("InventoryMoveItemEvent: Detected intent to insert " + item.getType().name() + 
                           " into jukebox at " + jukebox.getLocation());
                plugin.debug("Note: Actual insertion will be detected by the jukebox scanner");
            }
        }
    }

    /**
     * Handles redstone events that might trigger jukebox state changes
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        
        // Check if redstone is activating a jukebox or nearby blocks
        if (event.getNewCurrent() > 0 && event.getOldCurrent() == 0) {
            // Check the block itself and nearby blocks for jukeboxes
            checkNearbyJukeboxes(block.getLocation(), 2);
        }
    }
    
    /**
     * Checks for jukeboxes near a location and sends custom messages if needed
     */
    private void checkNearbyJukeboxes(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return;
        
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int y = centerY - radius; y <= centerY + radius; y++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    
                    if (block.getType() == Material.JUKEBOX && block.getState() instanceof Jukebox) {
                        Jukebox jukebox = (Jukebox) block.getState();
                        ItemStack record = jukebox.getRecord();
                        
                        // Check if jukebox has a disc and isn't already tracked
                        if (record != null && record.getType() != Material.AIR) {
                            Location jukeboxLoc = jukebox.getLocation();
                            String currentDisc = activeJukeboxes.get(jukeboxLoc);
                            String newDisc = record.getType().name();
                            
                            // Only send message if this is a new disc or not tracked
                            if (!newDisc.equals(currentDisc)) {
                                plugin.debug("Detected jukebox state change via redstone: " + newDisc);
                                
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    sendCustomJukeboxMessageToNearbyPlayers(jukebox);
                                }, 2L);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a message is a vanilla jukebox "Now playing" message
     */
    private boolean isVanillaJukeboxMessage(String json, String plainText) {
        // First check if this is one of our own custom messages
        if (isOurCustomMessage(plainText)) {
            return false;
        }
        
        // Check for vanilla translation keys (most reliable method)
        if (json.contains("record.nowPlaying") || json.contains("\"translate\":\"record.")) {
            return true;
        }
        
        // Check for vanilla "Music Disc" pattern (but not our custom format)
        if (plainText.contains("Music Disc") && json.contains("translate")) {
            return true;
        }
        
        // Additional check for vanilla patterns that don't contain our custom formatting
        if (plainText.contains("Now playing:") && !plainText.contains("§")) {
            return true; // Vanilla messages don't have color codes
        }
        
        return false;
    }
    
    /**
     * Checks if a message is one of our own custom messages
     */
    private boolean isOurCustomMessage(String plainText) {
        // Our custom messages always start with "§7Now playing: " and contain color codes
        if (plainText.startsWith("§7Now playing: ") && plainText.contains("§")) {
            return true;
        }
        
        // Check if we recently sent a similar message
        long currentTime = System.currentTimeMillis();
        String messageKey = plainText.replaceAll("§[0-9a-fk-or]", ""); // Remove color codes for comparison
        
        Long lastSentTime = sentCustomMessages.get(messageKey);
        if (lastSentTime != null && (currentTime - lastSentTime) < MESSAGE_COOLDOWN) {
            return true; // Recently sent by us
        }
        
        return false;
    }

    /**
     * Optimized replacement message sender - finds nearest jukebox efficiently
     */
    private void sendReplacementJukeboxMessage(Player player) {
        if (!protocolLibAvailable) return;
        
        // Use known jukeboxes first for better performance
        Jukebox nearestJukebox = findNearestKnownJukebox(player);
        if (nearestJukebox == null) {
            // Fallback to full search only if no known jukeboxes found
            nearestJukebox = findNearestPlayingJukebox(player);
        }
        
        if (nearestJukebox != null) {
            ItemStack record = nearestJukebox.getRecord();
            if (record != null && record.getType() != Material.AIR) {
                String materialName = record.getType().name();
                ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(materialName);
                
                if (remap != null && remap.hasDisplayName()) {
                    String customName = remap.getDisplayName();
                    String message = "§7Now playing: " + customName;
                    
                    // Anti-spam with optimized key
                    String messageKey = customName;
                    long currentTime = System.currentTimeMillis();
                    Long lastSent = sentCustomMessages.get(messageKey);
                    if (lastSent != null && (currentTime - lastSent) < MESSAGE_COOLDOWN) {
                        return; // Skip if sent recently
                    }
                    
                    sentCustomMessages.put(messageKey, currentTime);
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Sent replacement message to " + player.getName() + ": " + customName);
                    }
                    
                    activeJukeboxes.put(nearestJukebox.getLocation(), materialName);
                }
            }
        }
    }
    
    /**
     * Fast lookup using known jukebox locations
     */
    private Jukebox findNearestKnownJukebox(Player player) {
        Location playerLoc = player.getLocation();
        Jukebox nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        double maxDistanceSquared = 64 * 64; // 64 block radius
        
        for (Location loc : knownJukeboxes) {
            if (!loc.getWorld().equals(playerLoc.getWorld())) continue;
            
            double distanceSquared = playerLoc.distanceSquared(loc);
            if (distanceSquared <= maxDistanceSquared && distanceSquared < nearestDistanceSquared) {
                Block block = loc.getBlock();
                if (block.getType() == Material.JUKEBOX && block.getState() instanceof Jukebox) {
                    Jukebox jukebox = (Jukebox) block.getState();
                    ItemStack record = jukebox.getRecord();
                    if (record != null && record.getType() != Material.AIR) {
                        nearest = jukebox;
                        nearestDistanceSquared = distanceSquared;
                    }
                }
            }
        }
        
        return nearest;
    }

    /**
     * Finds the nearest playing jukebox to a player within a reasonable range
     */
    private Jukebox findNearestPlayingJukebox(Player player) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        
        if (world == null) return null;
        
        Jukebox nearestJukebox = null;
        double nearestDistance = Double.MAX_VALUE;
        
        // Search in a 64-block radius (same as vanilla message range)
        int radius = 64;
        int playerX = playerLoc.getBlockX();
        int playerY = playerLoc.getBlockY();
        int playerZ = playerLoc.getBlockZ();
        
        for (int x = playerX - radius; x <= playerX + radius; x++) {
            for (int y = Math.max(world.getMinHeight(), playerY - radius); y <= Math.min(world.getMaxHeight(), playerY + radius); y++) {
                for (int z = playerZ - radius; z <= playerZ + radius; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    
                    if (block.getType() == Material.JUKEBOX && block.getState() instanceof Jukebox) {
                        Jukebox jukebox = (Jukebox) block.getState();
                        ItemStack record = jukebox.getRecord();
                        
                        // Check if jukebox is playing (has a disc)
                        if (record != null && record.getType() != Material.AIR) {
                            double distance = playerLoc.distance(block.getLocation());
                            if (distance <= 64 && distance < nearestDistance) {
                                nearestDistance = distance;
                                nearestJukebox = jukebox;
                            }
                        }
                    }
                }
            }
        }
        
        return nearestJukebox;
    }

    /**
     * Checks if jukebox has the expected disc and sends message if so
     * Returns true if message was sent successfully
     * (Legacy method - mainly used by player interaction now)
     */
    private boolean checkAndSendJukeboxMessage(Jukebox jukebox, String expectedDiscType, String source) {
        ItemStack record = jukebox.getRecord();
        
        plugin.debug("Checking jukebox from " + source + ", expected: " + expectedDiscType + ", actual: " + 
                    (record != null ? record.getType().name() : "null"));
        
        if (record != null && record.getType() != Material.AIR) {
            String actualDiscType = record.getType().name();
            
            // Check if it matches the expected disc type
            if (actualDiscType.equals(expectedDiscType)) {
                plugin.debug("Disc matches expectation, sending custom message from " + source);
                sendCustomJukeboxMessageToNearbyPlayers(jukebox);
                return true;
            } else {
                plugin.debug("Disc type mismatch - expected: " + expectedDiscType + ", got: " + actualDiscType);
            }
        } else {
            plugin.debug("No disc found in jukebox yet (" + source + ")");
        }
        
        return false;
    }
    
    /**
     * Sends custom jukebox message to nearby players (called when player interacts)
     */
    private void sendCustomJukeboxMessage(Jukebox jukebox, Player triggeringPlayer) {
        sendCustomJukeboxMessageToNearbyPlayers(jukebox);
    }
    
    /**
     * Optimized jukebox message sending to nearby players
     */
    private void sendCustomJukeboxMessageToNearbyPlayers(Jukebox jukebox) {
        ItemStack record = jukebox.getRecord();
        
        if (record == null || record.getType() == Material.AIR) {
            return; // Early exit for empty jukeboxes
        }
        
        String materialName = record.getType().name();
        
        // Fast lookup for custom name
        ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(materialName);
        if (remap == null || !remap.hasDisplayName() || !protocolLibAvailable) {
            return; // Early exit if no custom message needed
        }
        
        String customName = remap.getDisplayName();
        String message = "§7Now playing: " + customName;
        
        // Anti-spam protection with optimized key generation
        String messageKey = customName; // Use customName directly instead of regex
        long currentTime = System.currentTimeMillis();
        Long lastSent = sentCustomMessages.get(messageKey);
        if (lastSent != null && (currentTime - lastSent) < MESSAGE_COOLDOWN) {
            return; // Skip if sent recently
        }
        
        sentCustomMessages.put(messageKey, currentTime);
        activeJukeboxes.put(jukebox.getLocation(), materialName);
        
        // Optimized player lookup - get nearby players directly
        Location jukeboxLoc = jukebox.getLocation();
        Collection<Player> nearbyPlayers = jukeboxLoc.getWorld().getNearbyEntities(
            jukeboxLoc, 64, 64, 64, 
            entity -> entity instanceof Player
        ).stream()
        .map(entity -> (Player) entity)
        .toList();
        
        // Send messages efficiently
        TextComponent textComponent = new TextComponent(message);
        int sentCount = 0;
        for (Player player : nearbyPlayers) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, textComponent);
            sentCount++;
        }
        
        // Minimal debug logging
        if (plugin.isDebugMode() && sentCount > 0) {
            plugin.debug("Sent jukebox message '" + customName + "' to " + sentCount + " players");
        }
    }

    /**
     * Periodically clean up the jukebox cache and scan for jukebox changes
     */
    public void startCacheCleanupTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Clean up old custom message entries
            long currentTime = System.currentTimeMillis();
            sentCustomMessages.entrySet().removeIf(entry -> 
                (currentTime - entry.getValue()) > MESSAGE_COOLDOWN * 2
            );
        }, 100L, 100L); // Run every 5 seconds (100 ticks)
    }
    
    /**
     * Starts optimized jukebox monitoring system
     */
    public void startJukeboxScanner() {
        // High-frequency scanner for known jukeboxes only (every 0.5s)
        Bukkit.getScheduler().runTaskTimer(plugin, this::processKnownJukeboxes, 20L, 10L);
        
        // Low-frequency discovery scanner for new jukeboxes (every 5 seconds)
        Bukkit.getScheduler().runTaskTimer(plugin, this::discoverNewJukeboxes, 100L, 100L);
        
        // Batch processor for queued jukebox checks (every tick)
        Bukkit.getScheduler().runTaskTimer(plugin, this::processBatchedJukeboxes, 1L, 1L);
    }
    
    /**
     * Fast processing of known jukebox locations only
     */
    private void processKnownJukeboxes() {
        if (knownJukeboxes.isEmpty()) return;
        
        // Only check known jukeboxes if players are nearby
        Iterator<Location> iterator = knownJukeboxes.iterator();
        while (iterator.hasNext()) {
            Location loc = iterator.next();
            
            // Quick validation - remove if not a jukebox anymore
            if (loc.getBlock().getType() != Material.JUKEBOX) {
                iterator.remove();
                activeJukeboxes.remove(loc);
                continue;
            }
            
            // Only process if players are nearby (64 block radius)
            if (hasNearbyPlayers(loc, 64)) {
                if (loc.getBlock().getState() instanceof Jukebox) {
                    jukeboxesToCheck.offer((Jukebox) loc.getBlock().getState());
                }
            }
        }
    }
    
    /**
     * Discovers new jukeboxes periodically (low frequency)
     */
    private void discoverNewJukeboxes() {
        scannedChunksThisTick.clear();
        
        for (World world : Bukkit.getWorlds()) {
            Collection<Player> players = world.getPlayers();
            if (players.isEmpty()) continue;
            
            // Use TileEntity cache for much better performance
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                String chunkKey = world.getName() + "_" + chunk.getX() + "_" + chunk.getZ();
                
                // Skip if already scanned this tick
                if (scannedChunksThisTick.contains(chunkKey)) continue;
                
                // Only scan chunks with nearby players
                if (hasPlayersInChunkRange(chunk, players, 4)) { // 4 chunk radius
                    scanChunkForNewJukeboxes(chunk);
                    scannedChunksThisTick.add(chunkKey);
                }
            }
        }
    }
    
    /**
     * Process batched jukebox checks to avoid lag spikes
     */
    private void processBatchedJukeboxes() {
        int processed = 0;
        while (!jukeboxesToCheck.isEmpty() && processed < MAX_JUKEBOXES_PER_TICK) {
            Jukebox jukebox = jukeboxesToCheck.poll();
            if (jukebox != null) {
                checkJukeboxForChanges(jukebox);
                processed++;
            }
        }
    }
    
    /**
     * Efficiently scan chunk for new jukeboxes using TileEntity data
     */
    private void scanChunkForNewJukeboxes(org.bukkit.Chunk chunk) {
        // Use TileEntity array for much better performance than block iteration
        for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
            if (state instanceof Jukebox) {
                Location loc = state.getLocation();
                if (!knownJukeboxes.contains(loc)) {
                    knownJukeboxes.add(loc);
                    if (plugin.isDebugMode()) {
                        plugin.debug("Discovered new jukebox at " + loc);
                    }
                }
            }
        }
    }
    
    /**
     * Fast check if any players are in chunk range
     */
    private boolean hasPlayersInChunkRange(org.bukkit.Chunk chunk, Collection<Player> players, int chunkRadius) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        
        for (Player player : players) {
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
     * Fast check for nearby players using distance squared (avoids sqrt)
     */
    private boolean hasNearbyPlayers(Location loc, int radius) {
        World world = loc.getWorld();
        if (world == null) return false;
        
        double radiusSquared = radius * radius;
        
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(loc) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Optimized jukebox content change detection
     */
    private void checkJukeboxForChanges(Jukebox jukebox) {
        Location loc = jukebox.getLocation();
        ItemStack record = jukebox.getRecord();
        
        String currentDiscType = null;
        if (record != null && record.getType() != Material.AIR) {
            currentDiscType = record.getType().name();
        }
        
        String cachedDiscType = activeJukeboxes.get(loc);
        
        // Fast equality check - most jukeboxes won't have changed
        if (Objects.equals(currentDiscType, cachedDiscType)) {
            return; // No change, exit early
        }
        
        // Change detected - only log if debug is enabled to reduce I/O
        if (plugin.isDebugMode()) {
            plugin.debug("Jukebox scanner detected change at " + loc + 
                        ": " + cachedDiscType + " -> " + currentDiscType);
        }
        
        if (currentDiscType != null) {
            // A new disc was inserted - check for custom name
            ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(currentDiscType);
            if (remap != null && remap.hasDisplayName()) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Scanner triggering custom message for: " + currentDiscType);
                }
                sendCustomJukeboxMessageToNearbyPlayers(jukebox);
            }
            
            // Update cache
            activeJukeboxes.put(loc, currentDiscType);
        } else {
            // Disc was removed
            activeJukeboxes.remove(loc);
            if (plugin.isDebugMode()) {
                plugin.debug("Scanner detected disc removal from jukebox at " + loc);
            }
        }
    }

    /**
     * Cleans up ProtocolLib listeners and caches
     */
    public void cleanup() {
        if (protocolLibAvailable && protocolManager != null) {
            protocolManager.removePacketListeners(plugin);
        }
        
        // Clear all caches
        activeJukeboxes.clear();
        sentCustomMessages.clear();
        knownJukeboxes.clear();
        scannedChunksThisTick.clear();
        jukeboxesToCheck.clear();
    }
}
