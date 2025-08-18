package me.millosaurs.webstoreApi;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class PendingQueue {
    private final JavaPlugin plugin;
    private final File queueFile;
    private YamlConfiguration queueConfig;
    private final Map<String, List<PendingItem>> pendingItems;

    public PendingQueue(JavaPlugin plugin) {
        this.plugin = plugin;
        this.queueFile = new File(plugin.getDataFolder(), "pending.yml");
        this.pendingItems = new HashMap<>();
    }

    public void load() {
        pendingItems.clear();

        if (!queueFile.exists()) {
            plugin.getLogger().info("No pending queue file found, starting with empty queue");
            queueConfig = new YamlConfiguration();
            return;
        }

        try {
            queueConfig = YamlConfiguration.loadConfiguration(queueFile);

            if (queueConfig.contains("pending")) {
                for (String playerName : queueConfig.getConfigurationSection("pending").getKeys(false)) {
                    List<Map<?, ?>> itemMaps = queueConfig.getMapList("pending." + playerName);
                    List<PendingItem> items = new ArrayList<>();

                    for (Map<?, ?> itemMap : itemMaps) {
                        try {
                            String materialName = (String) itemMap.get("material");
                            int amount = ((Number) itemMap.get("amount")).intValue();
                            String note = (String) itemMap.get("note");

                            Material material = Material.matchMaterial(materialName);
                            if (material != null) {
                                items.add(new PendingItem(material, amount, note));
                            } else {
                                plugin.getLogger().warning("Skipping invalid material in queue: " + materialName);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Error loading pending item for " + playerName + ": " + e.getMessage());
                        }
                    }

                    if (!items.isEmpty()) {
                        pendingItems.put(playerName, items);
                    }
                }
            }

            int totalItems = pendingItems.values().stream().mapToInt(List::size).sum();
            plugin.getLogger().info("Loaded pending queue: " + pendingItems.size() + " players, " + totalItems + " total items");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load pending queue file", e);
            queueConfig = new YamlConfiguration();
        }
    }

    public void save() {
        try {
            // Ensure data folder exists
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            queueConfig = new YamlConfiguration();

            if (!pendingItems.isEmpty()) {
                for (Map.Entry<String, List<PendingItem>> entry : pendingItems.entrySet()) {
                    String playerName = entry.getKey();
                    List<PendingItem> items = entry.getValue();

                    List<Map<String, Object>> itemMaps = new ArrayList<>();
                    for (PendingItem item : items) {
                        Map<String, Object> itemMap = new HashMap<>();
                        itemMap.put("material", item.material.getKey().toString());
                        itemMap.put("amount", item.amount);
                        if (item.note != null) {
                            itemMap.put("note", item.note);
                        }
                        itemMaps.add(itemMap);
                    }

                    queueConfig.set("pending." + playerName, itemMaps);
                }
            }

            queueConfig.save(queueFile);

        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save pending queue file", e);
        }
    }

    public void addItem(String playerName, Material material, int amount, String note) {
        String lowerPlayerName = playerName.toLowerCase();

        pendingItems.computeIfAbsent(lowerPlayerName, k -> new ArrayList<>())
                .add(new PendingItem(material, amount, note));

        save(); // Save immediately to avoid data loss

        plugin.getLogger().info("Added to queue: " + amount + "x " + material.name() + " for " + playerName +
                (note != null ? " (note: " + note + ")" : ""));
    }

    public List<PendingItem> getItems(String playerName) {
        return pendingItems.getOrDefault(playerName.toLowerCase(), new ArrayList<>());
    }

    public void removePlayer(String playerName) {
        String lowerPlayerName = playerName.toLowerCase();
        List<PendingItem> removed = pendingItems.remove(lowerPlayerName);

        if (removed != null && !removed.isEmpty()) {
            save(); // Save immediately after removal
            plugin.getLogger().info("Removed " + removed.size() + " queued items for " + playerName);
        }
    }

    public int getTotalQueuedItems() {
        return pendingItems.values().stream().mapToInt(List::size).sum();
    }

    public int getQueuedPlayersCount() {
        return pendingItems.size();
    }
}