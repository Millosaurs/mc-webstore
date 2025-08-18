package com.yourserver.webstore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
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

public class WebstoreIntegrationPlugin extends JavaPlugin {
    private HttpServer httpServer;
    private String secret;
    private List<String> allowedCommands;
    private Gson gson = new Gson();


    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Load configuration
        loadConfiguration();

        // Start HTTP server
        startHttpServer();

        int port = getConfig().getInt("port", 8123);
        boolean usingDefaultSecret = "change-me-super-secret-key".equals(secret);

        // Startup summary logs
        getLogger().info("Webstore Integration Plugin enabled successfully.");
        getLogger().info("HTTP server listening on port " + port);
        getLogger().info("Secret key status: " + (usingDefaultSecret ? "DEFAULT (CHANGE IT!)" : "Custom"));

        if (usingDefaultSecret) {
            getLogger().warning("WARNING: Using default secret key! Change it in config.yml for security.");
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
            getLogger().info("HTTP server stopped");
        }
        getLogger().info("Webstore Integration Plugin disabled");
    }

    private void loadConfiguration() {
        FileConfiguration config = getConfig();

        this.secret = config.getString("secret", "change-me-super-secret-key");
        this.allowedCommands = config.getStringList("allowedCommands");

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
            getLogger().log(Level.SEVERE, "Failed to start HTTP server. Check if the port is available.", e);
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
                getLogger().warning("🚫 Invalid request method: " + exchange.getRequestMethod());
                sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }

            // Verify authorization header
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + secret)) {
                String clientIP = exchange.getRemoteAddress().getHostString();
                getLogger().warning("🚫 Unauthorized delivery request from " + clientIP);
                sendResponse(exchange, 403, createErrorResponse("Unauthorized"));
                return;
            }

            // Read request body
            String requestBody;
            try (InputStream is = exchange.getRequestBody()) {
                requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            getLogger().info("📨 Received delivery request: " + requestBody);

            // Parse JSON request
            DeliveryRequest request;
            try {
                request = gson.fromJson(requestBody, DeliveryRequest.class);
            } catch (Exception e) {
                getLogger().warning("❌ Invalid JSON in delivery request: " + e.getMessage());
                sendResponse(exchange, 400, createErrorResponse("Invalid JSON format"));
                return;
            }

            // Validate required fields
            if (request.orderId == null || request.minecraftUsername == null ||
                    request.commands == null || request.commands.isEmpty()) {
                getLogger().warning("❌ Missing required fields in delivery request");
                sendResponse(exchange, 400, createErrorResponse("Missing required fields: orderId, minecraftUsername, commands"));
                return;
            }

            getLogger().info("🎮 Processing delivery for order " + request.orderId + " to player " + request.minecraftUsername);

            // Process delivery asynchronously
            CompletableFuture<DeliveryResult> future = processDelivery(request);

            future.thenAccept(result -> {
                try {
                    sendResponse(exchange, result.success ? 200 : 500, gson.toJson(result));

                    if (result.success) {
                        getLogger().info("✅ Delivery completed successfully for order " + request.orderId +
                                " (player: " + request.minecraftUsername + ")");
                    } else {
                        getLogger().warning("❌ Delivery failed for order " + request.orderId +
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

            getLogger().info("🔄 Executing " + request.commands.size() + " commands for " + request.minecraftUsername);

            for (String command : request.commands) {
                // Replace placeholders in command
                String finalCommand = command
                        .replace("{player}", request.minecraftUsername)
                        .replace("{order_id}", request.orderId.toString());

                getLogger().info("⚡ Executing command: " + finalCommand);

                // Check if command is allowed (if whitelist is enabled)
                if (!allowedCommands.isEmpty() && !isCommandAllowed(finalCommand)) {
                    String error = finalCommand + " (not in whitelist)";
                    result.failedCommands.add(error);
                    getLogger().warning("🚫 Command blocked by whitelist: " + finalCommand);
                    continue;
                }

                try {
                    // Execute command as console
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);

                    if (success) {
                        result.executedCommands.add(finalCommand);
                        getLogger().info("✅ Command executed successfully: " + finalCommand);
                    } else {
                        String error = finalCommand + " (execution returned false)";
                        result.failedCommands.add(error);
                        getLogger().warning("❌ Command execution failed: " + finalCommand);
                    }
                } catch (Exception e) {
                    String error = finalCommand + " (exception: " + e.getMessage() + ")";
                    result.failedCommands.add(error);
                    getLogger().log(Level.WARNING, "❌ Exception executing command: " + finalCommand, e);
                }
            }

            // Determine overall success
            result.success = result.failedCommands.isEmpty();
            if (!result.success) {
                result.error = "Some commands failed: " + String.join(", ", result.failedCommands);
            }

            getLogger().info("📊 Delivery summary - Success: " + result.success +
                    ", Executed: " + result.executedCommands.size() +
                    ", Failed: " + result.failedCommands.size());

            future.complete(result);
        });

        return future;
    }

    private boolean isCommandAllowed(String command) {
        if (allowedCommands.isEmpty()) {
            return true; // No whitelist = allow all
        }

        String baseCommand = command.split(" ")[0].toLowerCase();
        return allowedCommands.stream()
                .anyMatch(allowed -> baseCommand.startsWith(allowed.toLowerCase()));
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

                getLogger().info("💓 Health check requested - Server healthy, " +
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
    }
}