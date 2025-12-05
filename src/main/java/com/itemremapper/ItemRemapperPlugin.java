package com.itemremapper;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class ItemRemapperPlugin extends JavaPlugin {

    private final Map<String, ItemRemap> itemRemaps = new HashMap<>();
    private boolean debugMode = false;
    private JukeboxListener jukeboxListener;
    
    @Override
    public void onLoad() {
        // Initialize jukebox listener early (ProtocolLib should be initialized in onLoad)
        jukeboxListener = new JukeboxListener(this);
    }
    
    /**
     * Inner class to hold item remap data (name, lore, sound, and duration)
     */
    public static class ItemRemap {
        private final String displayName;
        private final List<String> lore;
        private final String customSound;
        private final int duration; // Duration in seconds
        
        public ItemRemap(String displayName, List<String> lore, String customSound, int duration) {
            this.displayName = displayName;
            this.lore = lore;
            this.customSound = customSound;
            this.duration = duration;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public List<String> getLore() {
            return lore;
        }
        
        public String getCustomSound() {
            return customSound;
        }
        
        public int getDuration() {
            return duration;
        }
        
        public boolean hasDisplayName() {
            return displayName != null;
        }
        
        public boolean hasLore() {
            return lore != null && !lore.isEmpty();
        }
        
        public boolean hasCustomSound() {
            return customSound != null && !customSound.isEmpty();
        }
    }

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Load configuration
        loadConfiguration();
        
        // Register event listener
        getServer().getPluginManager().registerEvents(new ItemRemapListener(this), this);
        
        // Register jukebox listener and setup ProtocolLib
        if (jukeboxListener == null) {
            jukeboxListener = new JukeboxListener(this);
        }
        jukeboxListener.setupProtocolLib();
        jukeboxListener.startCacheCleanupTask();
        jukeboxListener.startJukeboxScanner();
        getServer().getPluginManager().registerEvents(jukeboxListener, this);
        
        // Register command
        ItemRemapperCommand commandHandler = new ItemRemapperCommand(this);
        getCommand("itemremapper").setExecutor(commandHandler);
        getCommand("itemremapper").setTabCompleter(commandHandler);
        
        // Log server type
        if (isFolia()) {
            getLogger().info("ItemRemapper has been enabled on Folia!");
        } else {
            getLogger().info("ItemRemapper has been enabled on Paper/Spigot!");
        }
        getLogger().info("Loaded " + itemRemaps.size() + " item remappings.");
    }

    @Override
    public void onDisable() {
        // Clean up jukebox listener
        if (jukeboxListener != null) {
            jukeboxListener.cleanup();
        }
        
        getLogger().info("ItemRemapper has been disabled!");
    }

    /**
     * Loads the item remapping configuration from config.yml
     */
    private void loadConfiguration() {
        itemRemaps.clear();
        
        // Load debug mode
        debugMode = getConfig().getBoolean("debug", false);
        
        // Load item remaps from config
        if (getConfig().isConfigurationSection("item-remaps")) {
            var remapsSection = getConfig().getConfigurationSection("item-remaps");
            if (remapsSection != null) {
                for (String key : remapsSection.getKeys(false)) {
                    String materialKey = key.toUpperCase();
                    
                    // Check if it's a simple string format or complex format
                    if (remapsSection.isString(key)) {
                        // Simple format: MATERIAL: "Display Name"
                        String displayName = remapsSection.getString(key);
                        itemRemaps.put(materialKey, new ItemRemap(displayName, null, null, 0));
                    } else if (remapsSection.isConfigurationSection(key)) {
                        // Complex format with name and/or lore
                        var itemSection = remapsSection.getConfigurationSection(key);
                        if (itemSection != null) {
                            String displayName = itemSection.getString("name");
                            List<String> lore = itemSection.getStringList("lore");
                            String customSound = itemSection.getString("sound");
                            int duration = itemSection.getInt("duration", 0);
                            
                            // Only add if at least name or lore is present
                            if (displayName != null || (lore != null && !lore.isEmpty())) {
                                itemRemaps.put(materialKey, new ItemRemap(displayName, lore, customSound, duration));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reloads the plugin configuration
     */
    public void reloadPluginConfig() {
        reloadConfig();
        loadConfiguration();
        getLogger().info("Configuration reloaded! Loaded " + itemRemaps.size() + " item remappings.");
    }

    /**
     * Gets the item remap configuration for a material type
     * 
     * @param materialName The material name (e.g., "DIAMOND")
     * @return The ItemRemap object, or null if no remap exists
     */
    public ItemRemap getItemRemap(String materialName) {
        return itemRemaps.get(materialName.toUpperCase());
    }

    /**
     * Gets the number of configured item remaps
     * 
     * @return The count of item remappings
     */
    public int getRemapCount() {
        return itemRemaps.size();
    }

    /**
     * Checks if debug mode is enabled
     * 
     * @return true if debug mode is enabled
     */
    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * Logs a debug message if debug mode is enabled
     * 
     * @param message The message to log
     */
    public void debug(String message) {
        if (debugMode) {
            getLogger().log(Level.INFO, "[DEBUG] " + message);
        }
    }

    /**
     * Checks if the server is running Folia
     * 
     * @return true if running on Folia, false otherwise
     */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
