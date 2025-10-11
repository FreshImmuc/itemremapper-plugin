package com.itemremapper;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Listener that handles item remapping when items enter player inventories
 */
public class ItemRemapListener implements Listener {

    private final ItemRemapperPlugin plugin;
    private final NamespacedKey remappedKey;

    public ItemRemapListener(ItemRemapperPlugin plugin) {
        this.plugin = plugin;
        // Create a persistent key to mark items as remapped by this plugin
        this.remappedKey = new NamespacedKey(plugin, "remapped");
    }

    /**
     * Handles when a player picks up an item from the ground
     * This is the primary event for item pickup
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        // Only process if the entity is a player
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        ItemStack item = event.getItem().getItemStack();
        remapItem(item);
    }

    /**
     * Handles when items are moved in inventories via clicking
     * This catches items being moved around in inventories
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // Only process for players
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        // Check current item (the item being clicked on)
        ItemStack currentItem = event.getCurrentItem();
        if (currentItem != null && currentItem.getType() != Material.AIR) {
            remapItem(currentItem);
        }

        // Check cursor item (the item being moved with the cursor)
        ItemStack cursorItem = event.getCursor();
        if (cursorItem != null && cursorItem.getType() != Material.AIR) {
            remapItem(cursorItem);
        }
    }

    /**
     * Handles when items are dragged across inventory slots
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        // Only process for players
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        // Check the old cursor (item being dragged)
        ItemStack draggedItem = event.getOldCursor();
        if (draggedItem != null && draggedItem.getType() != Material.AIR) {
            remapItem(draggedItem);
        }
    }

    /**
     * Remaps an item's display name and lore if a mapping exists
     * 
     * @param item The ItemStack to potentially remap
     */
    private void remapItem(ItemStack item) {
        // Null check
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        // Get the material name
        String materialName = item.getType().name();

        // Check if this material has a remapping
        ItemRemapperPlugin.ItemRemap remap = plugin.getItemRemap(materialName);
        if (remap == null) {
            return; // No remapping for this item
        }

        // Get or create item meta
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return; // Cannot modify items without meta
        }

        // Check if item was remapped by a player (has custom name/lore but no plugin marker)
        PersistentDataContainer dataContainer = meta.getPersistentDataContainer();
        boolean isPluginRemapped = dataContainer.has(remappedKey, PersistentDataType.BYTE);
        boolean hasCustomData = meta.hasDisplayName() || meta.hasLore();

        // If item has custom data but NO plugin marker, it was renamed by a player - don't touch it
        if (hasCustomData && !isPluginRemapped) {
            plugin.debug("Item " + materialName + " was renamed by player, skipping remap.");
            return;
        }

        // Check if the item is already up-to-date with current config
        boolean nameMatches = true;
        boolean loreMatches = true;
        
        if (remap.hasDisplayName()) {
            nameMatches = meta.hasDisplayName() && meta.getDisplayName().equals(remap.getDisplayName());
        } else {
            nameMatches = !meta.hasDisplayName();
        }
        
        if (remap.hasLore()) {
            loreMatches = meta.hasLore() && meta.getLore().equals(remap.getLore());
        } else {
            loreMatches = !meta.hasLore();
        }
        
        // If already up-to-date, skip remapping
        if (nameMatches && loreMatches && isPluginRemapped) {
            plugin.debug("Item " + materialName + " already up-to-date, skipping remap.");
            return;
        }

        // If item was previously remapped by plugin OR has no custom data, we can remap it
        boolean modified = false;
        StringBuilder debugMsg = new StringBuilder("Remapped " + materialName);

        // Apply the remapped name if present
        if (remap.hasDisplayName()) {
            meta.setDisplayName(remap.getDisplayName());
            debugMsg.append(" name to '").append(remap.getDisplayName()).append("'");
            modified = true;
        } else if (meta.hasDisplayName()) {
            // Remove display name if config no longer has one
            meta.setDisplayName(null);
            debugMsg.append(" (removed name)");
            modified = true;
        }

        // Apply the remapped lore if present
        if (remap.hasLore()) {
            meta.setLore(remap.getLore());
            debugMsg.append(" with ").append(remap.getLore().size()).append(" lore line(s)");
            modified = true;
        } else if (meta.hasLore()) {
            // Remove lore if config no longer has one
            meta.setLore(null);
            debugMsg.append(" (removed lore)");
            modified = true;
        }

        // Mark this item as remapped by the plugin
        if (modified) {
            dataContainer.set(remappedKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            plugin.debug(debugMsg.toString());
        }
    }
}
