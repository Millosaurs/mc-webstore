package com.yourserver.webstore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class WebstoreIntegrationPlugin extends JavaPlugin implements Listener {
    private HttpServer httpServer;
    private String secret;
    private List<String> allowedCommands;
    private boolean queueOfflineItems;
    private PendingQueue pendingQueue;
    private Gson gson = new Gson();

    @Override
    public void onEnable() {
        // Display ASCII art banner
        displayBanner();

        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Load configuration
        loadConfiguration();

        // Initialize pending queue
        pendingQueue = new PendingQueue(this);
        pendingQueue.load();

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(this, this);

        // Start HTTP server
        startHttpServer();

        // Log success message
        getLogger().info("Webstore Integration Plugin enabled successfully!");
        getLogger().info("HTTP server listening on port " + getConfig().getInt("port", 8123));
        getLogger().info("Secret key configured: " + (secret.equals("change-me-super-secret-key") ? "DEFAULT (CHANGE IT!)" : "Custom"));
        getLogger().info("Offline item queueing: " + (queueOfflineItems ? "Enabled" : "Disabled"));
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
            getLogger().info("HTTP server stopped");
        }
        getLogger().info("Webstore Integration Plugin disabled");
    }

    private void displayBanner() {
        String version = getDescription().getVersion();
        getLogger().info("");
        getLogger().info("███╗   ███╗ ██████╗      ██╗    ██╗███████╗██████╗ ███████╗████████╗ ██████╗ ██████╗ ███████╗");
        getLogger().info("████╗ ████║██╔════╝      ██║    ██║██╔════╝██╔══██╗██╔════╝╚══██╔══╝██╔═══██╗██╔══██╗██╔════╝");
        getLogger().info("██╔████╔██║██║   ███████╗██║ █╗ ██║█████╗  ██████╔╝███████╗   ██║   ██║   ██║██████╔╝█████╗");
        getLogger().info("██║╚██╔╝██║██║   ╚══════╝██║███╗██║██╔══╝  ██╔══██╗╚════██║   ██║   ██║   ██║██╔══██╗██╔══╝");
        getLogger().info("██║ ╚═╝ ██║╚██████╗      ╚███╔███╔╝███████╗██████╔╝███████║   ██║   ╚██████╔╝██║  ██║███████╗");
        getLogger().info("╚═╝     ╚═╝ ╚═════╝       ╚══╝╚══╝ ╚══════╝╚═════╝ ╚══════╝   ╚═╝    ╚═════╝ ╚═╝  ╚═╝╚══════╝");
        getLogger().info("");
        getLogger().info("                                Author: Millosaurs");
        getLogger().info("                                Version: " + version);
        getLogger().info("");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName().toLowerCase();

        List<PendingItem> pendingItems = pendingQueue.getItems(playerName);
        if (pendingItems.isEmpty()) {
            return;
        }

        getLogger().info("Delivering " + pendingItems.size() + " queued items to " + player.getName());

        Bukkit.getScheduler().runTaskLater(this, () -> {
            int delivered = 0;
            for (PendingItem item : pendingItems) {
                try {
                    ItemStack itemStack = new ItemStack(item.material, item.amount);
                    player.getInventory().addItem(itemStack);
                    delivered++;
                    getLogger().info("Delivered " + item.amount + "x " + item.material.name() + " to " + player.getName() +
                            (item.note != null ? " (note: " + item.note + ")" : ""));
                } catch (Exception e) {
                    getLogger().warning("Failed to deliver item " + item.material.name() + " to " + player.getName() + ": " + e.getMessage());
                }
            }

            if (delivered > 0) {
                pendingQueue.removePlayer(playerName);
                pendingQueue.save();
                player.sendMessage("You received " + delivered + " queued item(s) from the webstore!");
            }
        }, 20L); // Delay by 1 second to ensure player is fully loaded
    }

    private void loadConfiguration() {
        FileConfiguration config = getConfig();

        this.secret = config.getString("secret", "change-me-super-secret-key");
        this.allowedCommands = config.getStringList("allowedCommands");
        this.queueOfflineItems = config.getBoolean("advanced.queueOfflineItems", true);

        if ("change-me-super-secret-key".equals(this.secret)) {
            getLogger().warning("WARNING: Using default secret key! Change it in config.yml for security!");
        }

        getLogger().info("Loaded " + allowedCommands.size() + " allowed command prefixes");
    }

    private void startHttpServer() {
        try {
            int port = getConfig().getInt("port", 8123);
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);

            // Add endpoints
            httpServer.createContext("/deliver", new DeliveryHandler());
            httpServer.createContext("/health", new HealthHandler());

            // Start server
            httpServer.setExecutor(null);
            httpServer.start();

            getLogger().info("HTTP server started successfully on port " + port);

        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to start HTTP server! Check if port is available.", e);
        }
    }

    private class DeliveryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Enable CORS for web requests
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");

            // Handle preflight OPTIONS request
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }

            // Only allow POST requests
            if (!"POST".equals(exchange.getRequestMethod())) {
                getLogger().warning("Invalid request method: " + exchange.getRequestMethod());
                sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }

            // Verify authorization header
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + secret)) {
                String clientIP = exchange.getRemoteAddress().getHostString();
                getLogger().warning("Unauthorized delivery request from " + clientIP);
                sendResponse(exchange, 403, createErrorResponse("Unauthorized"));
                return;
            }

            // Read request body
            String requestBody;
            try (InputStream is = exchange.getRequestBody()) {
                requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            getLogger().info("Received delivery request: " + requestBody);

            // Parse JSON request
            DeliveryRequest request;
            try {
                request = gson.fromJson(requestBody, DeliveryRequest.class);
            } catch (Exception e) {
                getLogger().warning("Invalid JSON in delivery request: " + e.getMessage());
                sendResponse(exchange, 400, createErrorResponse("Invalid JSON format"));
                return;
            }

            // Validate required fields
            if (request.orderId == null || request.minecraftUsername == null ||
                    request.commands == null || request.commands.isEmpty()) {
                getLogger().warning("Missing required fields in delivery request");
                sendResponse(exchange, 400, createErrorResponse("Missing required fields: orderId, minecraftUsername, commands"));
                return;
            }

            getLogger().info("Processing delivery for order " + request.orderId + " to player " + request.minecraftUsername);

            // Process delivery asynchronously
            CompletableFuture<DeliveryResult> future = processDelivery(request);

            future.thenAccept(result -> {
                try {
                    sendResponse(exchange, result.success ? 200 : 500, gson.toJson(result));

                    if (result.success) {
                        getLogger().info("Delivery completed successfully for order " + request.orderId +
                                " (player: " + request.minecraftUsername + ")");
                    } else {
                        getLogger().warning("Delivery failed for order " + request.orderId +
                                ": " + result.error);
                    }
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "Error sending HTTP response", e);
                }
            });
        }
    }

    private CompletableFuture<DeliveryResult> processDelivery(DeliveryRequest request) {
        CompletableFuture<DeliveryResult> future = new CompletableFuture<>();

        // Execute on main server thread (required for Bukkit commands)
        Bukkit.getScheduler().runTask(this, () -> {
            DeliveryResult result = new DeliveryResult();
            result.orderId = request.orderId;
            result.minecraftUsername = request.minecraftUsername;
            result.executedCommands = new java.util.ArrayList<>();
            result.failedCommands = new java.util.ArrayList<>();
            result.queuedCommands = new java.util.ArrayList<>();

            getLogger().info("Executing " + request.commands.size() + " commands for " + request.minecraftUsername);

            for (String command : request.commands) {
                // Replace placeholders in command
                String finalCommand = command
                        .replace("{player}", request.minecraftUsername)
                        .replace("{order_id}", request.orderId.toString());

                getLogger().info("Processing command: " + finalCommand);

                // Check if command is allowed (if whitelist is enabled)
                if (!allowedCommands.isEmpty() && !isCommandAllowed(finalCommand)) {
                    String error = finalCommand + " (not in whitelist)";
                    result.failedCommands.add(error);
                    getLogger().warning("Command blocked by whitelist: " + finalCommand);
                    continue;
                }

                // Check if this is a give command and handle offline delivery
                if (isGiveCommand(finalCommand)) {
                    handleGiveCommand(finalCommand, request.minecraftUsername, result);
                } else {
                    // Execute non-give commands normally
                    executeRegularCommand(finalCommand, result);
                }
            }

            // Determine overall success
            result.success = result.failedCommands.isEmpty();
            if (!result.success) {
                result.error = "Some commands failed: " + String.join(", ", result.failedCommands);
            }

            getLogger().info("Delivery summary - Success: " + result.success +
                    ", Executed: " + result.executedCommands.size() +
                    ", Failed: " + result.failedCommands.size() +
                    ", Queued: " + result.queuedCommands.size());

            future.complete(result);
        });

        return future;
    }

    private boolean isGiveCommand(String command) {
        String lowerCommand = command.toLowerCase().trim();
        return lowerCommand.startsWith("give ") || lowerCommand.startsWith("minecraft:give ");
    }

    private void handleGiveCommand(String command, String targetPlayer, DeliveryResult result) {
        try {
            // Parse give command: give <player> <material> [amount]
            String[] parts = command.trim().split("\\s+");
            if (parts.length < 3) {
                result.failedCommands.add(command + " (invalid give command format)");
                return;
            }

            String materialName = parts[2];
            int amount = 1;

            if (parts.length > 3) {
                try {
                    amount = Integer.parseInt(parts[3]);
                } catch (NumberFormatException e) {
                    result.failedCommands.add(command + " (invalid amount: " + parts[3] + ")");
                    return;
                }
            }

            // Validate material
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                result.failedCommands.add(command + " (invalid material: " + materialName + ")");
                getLogger().warning("Invalid material in give command: " + materialName);
                return;
            }

            // Check if player is online
            Player onlinePlayer = Bukkit.getPlayerExact(targetPlayer);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                // Player is online, give directly via API
                try {
                    ItemStack itemStack = new ItemStack(material, amount);
                    onlinePlayer.getInventory().addItem(itemStack);
                    result.executedCommands.add(command + " (delivered via API)");
                    getLogger().info("Delivered " + amount + "x " + material.name() + " directly to online player " + targetPlayer);
                } catch (Exception e) {
                    result.failedCommands.add(command + " (delivery failed: " + e.getMessage() + ")");
                    getLogger().warning("Failed to deliver item to online player " + targetPlayer + ": " + e.getMessage());
                }
            } else if (queueOfflineItems) {
                // Player is offline, queue the item
                String note = "order " + result.orderId;
                pendingQueue.addItem(targetPlayer.toLowerCase(), material, amount, note);
                result.queuedCommands.add(command + " (queued for offline player)");
                getLogger().info("Queued " + amount + "x " + material.name() + " for offline player " + targetPlayer);
            } else {
                // Offline queueing disabled, execute command normally
                executeRegularCommand(command, result);
            }

        } catch (Exception e) {
            result.failedCommands.add(command + " (processing error: " + e.getMessage() + ")");
            getLogger().warning("Error processing give command: " + command + " - " + e.getMessage());
        }
    }

    private void executeRegularCommand(String command, DeliveryResult result) {
        try {
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

            if (success) {
                result.executedCommands.add(command);
                getLogger().info("Command executed successfully: " + command);
            } else {
                String error = command + " (execution returned false)";
                result.failedCommands.add(error);
                getLogger().warning("Command execution failed: " + command);
            }
        } catch (Exception e) {
            String error = command + " (exception: " + e.getMessage() + ")";
            result.failedCommands.add(error);
            getLogger().log(Level.WARNING, "Exception executing command: " + command, e);
        }
    }

    private boolean isCommandAllowed(String command) {
        if (allowedCommands.isEmpty()) {
            return true; // No whitelist = allow all
        }

        // Normalize the command (lowercase, trim whitespace)
        String normalizedCommand = command.toLowerCase().trim();

        // Check if any allowed prefix matches the start of the command
        return allowedCommands.stream()
                .anyMatch(allowed -> {
                    String normalizedAllowed = allowed.toLowerCase().trim();
                    return normalizedCommand.startsWith(normalizedAllowed);
                });
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            if ("GET".equals(exchange.getRequestMethod())) {
                JsonObject response = new JsonObject();
                response.addProperty("status", "healthy");
                response.addProperty("server", Bukkit.getServer().getName());
                response.addProperty("online_players", Bukkit.getOnlinePlayers().size());
                response.addProperty("plugin_version", getDescription().getVersion());
                response.addProperty("minecraft_version", Bukkit.getVersion());
                response.addProperty("pending_queue_size", pendingQueue.getTotalQueuedItems());

                getLogger().info("Health check requested - Server healthy, " +
                        Bukkit.getOnlinePlayers().size() + " players online");

                sendResponse(exchange, 200, gson.toJson(response));
            } else {
                sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private String createErrorResponse(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        return gson.toJson(error);
    }

    // Data classes for JSON serialization
    private static class DeliveryRequest {
        Integer orderId;
        String minecraftUsername;
        List<String> commands;
    }

    private static class DeliveryResult {
        Integer orderId;
        String minecraftUsername;
        boolean success;
        String error;
        List<String> executedCommands;
        List<String> failedCommands;
        List<String> queuedCommands;
    }
}