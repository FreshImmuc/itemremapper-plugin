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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener that handles jukebox music disc renaming
 */
public class JukeboxListener implements Listener {

    private final ItemRemapperPlugin plugin;
    private ProtocolManager protocolManager;
    private boolean protocolLibAvailable = false;

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
                
                // Listen for SYSTEM_CHAT packets (1.19+)
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.SYSTEM_CHAT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        try {
                            PacketContainer packet = event.getPacket();
                            
                            // Get the chat component
                            WrappedChatComponent component = packet.getChatComponents().read(0);
                            if (component == null) return;
                            
                            String json = component.getJson();
                            String plainText = component.getHandle().toString();
                            
                            // Only intercept VANILLA messages (they contain "Music Disc" or translation key)
                            // Our custom messages don't contain these, they have the song names directly
                            if (json.contains("record.nowPlaying") || plainText.contains("Music Disc")) {
                                // Check if it's NOT already our custom format (which would have the song name)
                                boolean isVanillaMessage = plainText.contains("Music Disc") || json.contains("translate");
                                
                                if (isVanillaMessage) {
                                    // Cancel the original VANILLA message
                                    event.setCancelled(true);
                                    pluginRef.debug("Intercepted VANILLA jukebox message, cancelling: " + plainText);
                                } else {
                                    pluginRef.debug("Allowing custom jukebox message: " + plainText);
                                }
                            }
                        } catch (Exception e) {
                            pluginRef.debug("Error intercepting SYSTEM_CHAT packet: " + e.getMessage());
                        }
                    }
                });
                
                // Also listen for SET_ACTION_BAR_TEXT packets (action bar messages)
                protocolManager.addPacketListener(new PacketAdapter(
                    plugin,
                    PacketType.Play.Server.SET_ACTION_BAR_TEXT
                ) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        try {
                            PacketContainer packet = event.getPacket();
                            
                            // Get the chat component
                            WrappedChatComponent component = packet.getChatComponents().read(0);
                            if (component == null) return;
                            
                            String json = component.getJson();
                            
                            // Check if this is a "Now playing" message
                            if (json.contains("record.nowPlaying") || json.contains("Now playing") || json.contains("Music Disc")) {
                                // Cancel the original message
                                event.setCancelled(true);
                                
                                pluginRef.debug("Intercepted ACTION_BAR jukebox message: " + json);
                            }
                        } catch (Exception e) {
                            pluginRef.debug("Error intercepting ACTION_BAR packet: " + e.getMessage());
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
     * Sends custom jukebox message to nearby players
     */
    private void sendCustomJukeboxMessage(Jukebox jukebox, Player triggeringPlayer) {
        ItemStack record = jukebox.getRecord();
        
        if (record != null && record.getType() != Material.AIR) {
            String materialName = record.getType().name();
            
            // Check if this disc has a custom name
            ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(materialName);
            
            if (remap != null && remap.hasDisplayName() && protocolLibAvailable) {
                // Send custom action bar message to nearby players
                String customName = remap.getDisplayName();
                String message = "§7Now playing: " + customName;
                
                // Send to all players within 64 blocks
                for (Player nearbyPlayer : jukebox.getWorld().getPlayers()) {
                    if (nearbyPlayer.getLocation().distance(jukebox.getLocation()) <= 64) {
                        nearbyPlayer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
                        plugin.debug("Sent custom jukebox message to " + nearbyPlayer.getName() + ": " + message);
                    }
                }
            }
        }
    }

    /**
     * Cleans up ProtocolLib listeners
     */
    public void cleanup() {
        if (protocolLibAvailable && protocolManager != null) {
            protocolManager.removePacketListeners(plugin);
        }
    }
}
