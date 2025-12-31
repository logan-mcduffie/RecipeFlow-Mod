package com.recipeflow.mod.v120plus.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.recipeflow.mod.core.auth.AuthResult;
import com.recipeflow.mod.core.auth.AuthToken;
import com.recipeflow.mod.core.auth.DeviceFlowClient;
import com.recipeflow.mod.v120plus.auth.AuthProvider;
import com.recipeflow.mod.v120plus.config.ForgeConfig120;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements /recipeflow login and /recipeflow logout commands.
 * Uses OAuth 2.0 Device Authorization Grant for authentication.
 */
public class AuthCommand {

    private static final Logger LOGGER = LogManager.getLogger();

    // Prevent concurrent login operations
    private static final AtomicBoolean loginInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private AuthCommand() {
        // Utility class
    }

    /**
     * Register auth commands with the dispatcher.
     * Called from SyncCommand.register().
     */
    public static void registerSubcommands(com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("login")
                .executes(AuthCommand::executeLogin));

        parent.then(Commands.literal("logout")
                .executes(AuthCommand::executeLogout));
    }

    /**
     * Execute the login command.
     * Starts device flow authentication.
     */
    private static int executeLogin(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // Check if login already in progress
        if (!loginInProgress.compareAndSet(false, true)) {
            sendError(source, "Login already in progress. Please wait or restart the game to cancel.");
            return 0;
        }

        cancelRequested.set(false);

        // Check if server URL is configured
        String serverUrl = ForgeConfig120.INSTANCE.getServerUrl();
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            loginInProgress.set(false);
            sendError(source, "Server URL not configured. Set 'url' in config/recipeflow-common.toml first.");
            return 0;
        }

        // Check if already authenticated
        if (AuthProvider.INSTANCE.isDeviceFlowAuthenticated()) {
            String expInfo = AuthProvider.INSTANCE.getTokenExpirationInfo();
            sendMessage(source, "Already logged in (" + expInfo + "). Use '/recipeflow logout' first to re-authenticate.");
            loginInProgress.set(false);
            return Command.SINGLE_SUCCESS;
        }

        sendMessage(source, "Requesting login code...");

        // Run async
        CompletableFuture.runAsync(() -> {
            try {
                executeLoginAsync(source, serverUrl);
            } catch (Exception e) {
                LOGGER.error("RecipeFlow: Login failed with exception", e);
                sendError(source, "Login failed: " + e.getMessage());
            } finally {
                loginInProgress.set(false);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Async login execution with device flow.
     */
    private static void executeLoginAsync(CommandSourceStack source, String serverUrl) {
        ForgeConfig120 config = ForgeConfig120.INSTANCE;
        DeviceFlowClient client = new DeviceFlowClient(
                serverUrl,
                config.getTimeoutMs(),
                config.isDebugLogging()
        );

        try {
            // Step 1: Request device code
            DeviceFlowClient.DeviceCodeResponse deviceCode = client.requestDeviceCode();

            // Step 2: Display clickable link
            sendMessage(source, "");
            sendLoginLink(source, deviceCode);
            sendMessage(source, "");
            sendMessage(source, "Or visit: " + deviceCode.getVerificationUri());
            sendMessage(source, "And enter code: " + deviceCode.getUserCode());
            sendMessage(source, "");
            sendMessage(source, "Waiting for authorization... (expires in " +
                    (deviceCode.getExpiresIn() / 60) + " minutes)");

            // Step 3: Poll for token
            int pollInterval = deviceCode.getInterval() * 1000; // Convert to ms
            long expiresAt = System.currentTimeMillis() + (deviceCode.getExpiresIn() * 1000L);

            while (System.currentTimeMillis() < expiresAt && !cancelRequested.get()) {
                Thread.sleep(pollInterval);

                if (cancelRequested.get()) {
                    sendMessage(source, "Login cancelled.");
                    return;
                }

                AuthResult result = client.pollForToken(deviceCode.getDeviceCode());

                if (result.isSuccess()) {
                    // Save token
                    AuthToken token = result.getToken();
                    AuthProvider.INSTANCE.saveToken(token);

                    sendSuccess(source, "Successfully logged in!");
                    String expInfo = AuthProvider.INSTANCE.getTokenExpirationInfo();
                    if (expInfo != null) {
                        sendMessage(source, "Token " + expInfo);
                    }
                    return;
                } else if (result.isSlowDown()) {
                    // Increase poll interval
                    pollInterval = Math.min(pollInterval * 2, 30000);
                    LOGGER.debug("Slowing down poll interval to {}ms", pollInterval);
                } else if (result.isPending()) {
                    // Continue polling
                    continue;
                } else if (result.isExpired()) {
                    sendError(source, "Login expired. Please try again.");
                    return;
                } else if (result.isDenied()) {
                    sendError(source, "Login was denied. Please try again.");
                    return;
                } else {
                    // Other error
                    sendError(source, result.getSummary());
                    return;
                }
            }

            if (cancelRequested.get()) {
                sendMessage(source, "Login cancelled.");
            } else {
                sendError(source, "Login timed out. Please try again.");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendMessage(source, "Login cancelled.");
        } catch (Exception e) {
            LOGGER.error("Device flow login failed", e);
            sendError(source, "Login failed: " + e.getMessage());
        }
    }

    /**
     * Execute the logout command.
     */
    private static int executeLogout(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // Cancel any in-progress login
        if (loginInProgress.get()) {
            cancelRequested.set(true);
        }

        if (!AuthProvider.INSTANCE.hasStoredToken()) {
            sendMessage(source, "Not logged in via device flow.");

            // Check if using config token
            String configToken = ForgeConfig120.INSTANCE.getConfigAuthToken();
            if (configToken != null && !configToken.trim().isEmpty()) {
                sendMessage(source, "Note: Auth token is set in config file. Edit config/recipeflow-common.toml to remove it.");
            }
            return Command.SINGLE_SUCCESS;
        }

        AuthProvider.INSTANCE.clearToken();
        sendSuccess(source, "Logged out successfully.");

        // Remind about config token if present
        String configToken = ForgeConfig120.INSTANCE.getConfigAuthToken();
        if (configToken != null && !configToken.trim().isEmpty()) {
            sendMessage(source, "Note: Config file token still exists. Using that for authentication.");
        }

        return Command.SINGLE_SUCCESS;
    }

    // === Helper Methods ===

    private static void sendLoginLink(CommandSourceStack source, DeviceFlowClient.DeviceCodeResponse deviceCode) {
        String url = deviceCode.getVerificationUriComplete();

        MutableComponent linkText = Component.literal("[Click here to login]");
        linkText.setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Open " + url + " in your browser"))));

        MutableComponent message = Component.literal("[RecipeFlow] ").append(linkText);

        try {
            source.sendSystemMessage(message);
        } catch (Exception e) {
            // Fallback to plain text
            sendMessage(source, "Login URL: " + url);
        }
    }

    private static void sendMessage(CommandSourceStack source, String message) {
        try {
            source.sendSystemMessage(Component.literal("[RecipeFlow] " + message));
        } catch (Exception e) {
            LOGGER.info("[RecipeFlow] " + message);
        }
    }

    private static void sendSuccess(CommandSourceStack source, String message) {
        try {
            source.sendSuccess(() -> Component.literal("[RecipeFlow] " + message), true);
        } catch (Exception e) {
            LOGGER.info("[RecipeFlow] " + message);
        }
    }

    private static void sendError(CommandSourceStack source, String message) {
        try {
            source.sendFailure(Component.literal("[RecipeFlow] " + message));
        } catch (Exception e) {
            LOGGER.error("[RecipeFlow] " + message);
        }
    }
}
