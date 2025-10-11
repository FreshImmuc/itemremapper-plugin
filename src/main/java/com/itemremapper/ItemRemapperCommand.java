package com.itemremapper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

/**
 * Command handler for ItemRemapper commands
 */
public class ItemRemapperCommand implements CommandExecutor, TabCompleter {

    private final ItemRemapperPlugin plugin;

    public ItemRemapperCommand(ItemRemapperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6ItemRemapper v" + plugin.getDescription().getVersion());
            sender.sendMessage("§7Use /itemremapper reload to reload the configuration");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("itemremapper.admin")) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }

            try {
                plugin.reloadPluginConfig();
                sender.sendMessage("§aItemRemapper configuration reloaded successfully!");
                sender.sendMessage("§7Loaded " + plugin.getRemapCount() + " item remappings.");
            } catch (Exception e) {
                sender.sendMessage("§cError reloading configuration: " + e.getMessage());
                plugin.getLogger().severe("Error reloading config: " + e.getMessage());
            }
            return true;
        }

        sender.sendMessage("§cUnknown subcommand. Use /itemremapper reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("itemremapper.admin")) {
                completions.add("reload");
            }
        }

        return completions;
    }
}
