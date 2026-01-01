package com.recipeflow.mod.v120plus.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import com.recipeflow.mod.v120plus.util.SimpleRenderModeHelper;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A screen that displays items in a grid for animation capture.
 *
 * This captures frames by screenshotting the actual game framebuffer while
 * items are rendered normally - ensuring we capture exactly what the player sees,
 * including all mod rendering magic (GTCEu overlays, etc).
 */
@OnlyIn(Dist.CLIENT)
public class AnimationCaptureScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger();

    // Grid configuration - render items LARGE for better capture quality
    // Scale multiplier: 1 = normal (16px), 4 = 64px, 8 = 128px per item
    private static final int RENDER_SCALE = 8;  // Render at 8x size (128px items)
    private static final int SLOT_SIZE = 16 * RENDER_SCALE;  // 128px for items
    private static final int SLOT_PADDING = 2 * RENDER_SCALE;
    private static final int GRID_PADDING = 10;
    private static final int CELL_SIZE = SLOT_SIZE + SLOT_PADDING;

    // Capture configuration
    private static final int CAPTURE_FPS = 20;
    private static final int FRAME_DELAY_MS = 1000 / CAPTURE_FPS;
    private static final int MAX_CAPTURE_DURATION_MS = 10000;  // 10 seconds max

    // Items to capture
    private final List<ItemStack> items;
    private final int gridColumns;
    private final int gridRows;
    private final int captureDurationMs;

    // Grid position (calculated on init)
    private int gridX;
    private int gridY;
    private int gridWidth;
    private int gridHeight;

    // Capture state
    private boolean capturing = false;
    private long captureStartTime;
    private long lastFrameTime;
    private final List<BufferedImage> capturedFrames = new ArrayList<>();
    private Consumer<CaptureResult> onComplete;

    // Status display
    private String statusText = "Press SPACE to start capture, ESC to cancel";
    private int framesCaptures = 0;

    /**
     * Create a capture screen for a list of items.
     *
     * @param items Items to capture (will be arranged in a grid)
     * @param captureDurationMs How long to capture in milliseconds
     */
    public AnimationCaptureScreen(List<ItemStack> items, int captureDurationMs) {
        super(Component.literal("Animation Capture"));
        this.items = new ArrayList<>(items);
        this.captureDurationMs = Math.min(captureDurationMs, MAX_CAPTURE_DURATION_MS);

        // Calculate grid dimensions to fit items
        int itemCount = items.size();
        this.gridColumns = (int) Math.ceil(Math.sqrt(itemCount));
        this.gridRows = (int) Math.ceil((double) itemCount / gridColumns);

        LOGGER.info("RecipeFlow AnimationCapture: Created screen for {} items in {}x{} grid",
                itemCount, gridColumns, gridRows);
    }

    /**
     * Create a capture screen for a single item.
     */
    public AnimationCaptureScreen(ItemStack item, int captureDurationMs) {
        this(List.of(item), captureDurationMs);
    }

    /**
     * Set callback for when capture completes.
     */
    public void setOnComplete(Consumer<CaptureResult> callback) {
        this.onComplete = callback;
    }

    @Override
    protected void init() {
        super.init();

        // Calculate grid dimensions
        gridWidth = gridColumns * CELL_SIZE + GRID_PADDING * 2;
        gridHeight = gridRows * CELL_SIZE + GRID_PADDING * 2;

        // Center the grid
        gridX = (this.width - gridWidth) / 2;
        gridY = (this.height - gridHeight) / 2;

        LOGGER.info("RecipeFlow AnimationCapture: Grid at ({}, {}), size {}x{}",
                gridX, gridY, gridWidth, gridHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Dark background
        this.renderBackground(graphics);

        // Draw grid background
        graphics.fill(gridX - 2, gridY - 2, gridX + gridWidth + 2, gridY + gridHeight + 2, 0xFF000000);
        graphics.fill(gridX, gridY, gridX + gridWidth, gridY + gridHeight, 0xFF1E1E1E);

        // Enable SimpleRenderMode for GTCEu machines during item rendering
        // This makes emissive textures render as normal quads, allowing proper animation capture
        SimpleRenderModeHelper.enable();
        try {
            // Draw items in grid at scaled size
            for (int i = 0; i < items.size(); i++) {
                int col = i % gridColumns;
                int row = i / gridColumns;

                int itemX = gridX + GRID_PADDING + col * CELL_SIZE;
                int itemY = gridY + GRID_PADDING + row * CELL_SIZE;

                // Draw slot background (scaled)
                graphics.fill(itemX - 1, itemY - 1, itemX + SLOT_SIZE + 1, itemY + SLOT_SIZE + 1, 0xFF373737);

                // Draw item at scaled size using pose stack
                // renderItem draws at 16x16, so we scale up
                graphics.pose().pushPose();
                graphics.pose().translate(itemX, itemY, 0);
                graphics.pose().scale(RENDER_SCALE, RENDER_SCALE, 1);
                // renderItem expects position 0,0 after our translation
                graphics.renderItem(items.get(i), 0, 0);
                graphics.pose().popPose();
            }
        } finally {
            SimpleRenderModeHelper.disable();
        }

        // Draw status text
        String status = capturing
                ? String.format("Capturing... %d frames (%.1fs remaining)",
                        framesCaptures,
                        (captureDurationMs - (System.currentTimeMillis() - captureStartTime)) / 1000.0)
                : statusText;

        int textWidth = this.font.width(status);
        graphics.drawString(this.font, status, (this.width - textWidth) / 2, gridY + gridHeight + 10, 0xFFFFFF);

        // If capturing, grab a frame
        if (capturing) {
            captureFrame();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 32 && !capturing) {  // SPACE
            startCapture();
            return true;
        }
        if (keyCode == 256) {  // ESC
            if (capturing) {
                // Cancel capture
                capturing = false;
                statusText = "Capture cancelled. Press SPACE to restart, ESC to close";
                capturedFrames.clear();
                framesCaptures = 0;
            } else {
                this.onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Start capturing frames.
     */
    private void startCapture() {
        LOGGER.info("RecipeFlow AnimationCapture: Starting capture for {}ms", captureDurationMs);
        capturing = true;
        captureStartTime = System.currentTimeMillis();
        lastFrameTime = 0;
        capturedFrames.clear();
        framesCaptures = 0;
    }

    /**
     * Capture a frame if enough time has passed.
     */
    private void captureFrame() {
        long now = System.currentTimeMillis();
        long elapsed = now - captureStartTime;

        // Check if capture is complete
        if (elapsed >= captureDurationMs) {
            finishCapture();
            return;
        }

        // Check if it's time for a new frame
        if (now - lastFrameTime < FRAME_DELAY_MS) {
            return;
        }

        lastFrameTime = now;

        // Capture the grid region from the framebuffer
        BufferedImage frame = captureGridRegion();
        if (frame != null) {
            capturedFrames.add(frame);
            framesCaptures++;
        }
    }

    /**
     * Capture just the grid region from the framebuffer.
     */
    private BufferedImage captureGridRegion() {
        try {
            Minecraft mc = Minecraft.getInstance();

            // Get the actual screen coordinates (accounting for GUI scale)
            double scale = mc.getWindow().getGuiScale();
            int captureX = (int) (gridX * scale);
            int captureY = (int) ((this.height - gridY - gridHeight) * scale);  // Flip Y for OpenGL
            int captureWidth = (int) (gridWidth * scale);
            int captureHeight = (int) (gridHeight * scale);

            // Read pixels from framebuffer
            ByteBuffer buffer = ByteBuffer.allocateDirect(captureWidth * captureHeight * 4);
            buffer.order(ByteOrder.nativeOrder());

            RenderSystem.bindTexture(mc.getMainRenderTarget().getColorTextureId());
            GL11.glReadPixels(captureX, captureY, captureWidth, captureHeight,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            // Convert to BufferedImage (flip vertically)
            BufferedImage image = new BufferedImage(captureWidth, captureHeight, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < captureHeight; y++) {
                for (int x = 0; x < captureWidth; x++) {
                    int srcIndex = ((captureHeight - 1 - y) * captureWidth + x) * 4;
                    int r = buffer.get(srcIndex) & 0xFF;
                    int g = buffer.get(srcIndex + 1) & 0xFF;
                    int b = buffer.get(srcIndex + 2) & 0xFF;
                    int a = buffer.get(srcIndex + 3) & 0xFF;
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    image.setRGB(x, y, argb);
                }
            }

            return image;

        } catch (Exception e) {
            LOGGER.error("RecipeFlow AnimationCapture: Failed to capture frame: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Finish capture and process results.
     */
    private void finishCapture() {
        capturing = false;
        LOGGER.info("RecipeFlow AnimationCapture: Capture complete, {} frames", capturedFrames.size());

        if (capturedFrames.isEmpty()) {
            statusText = "No frames captured! Press SPACE to retry";
            return;
        }

        // Process results
        CaptureResult result = new CaptureResult(
                items,
                new ArrayList<>(capturedFrames),
                gridColumns,
                gridRows,
                CELL_SIZE,
                SLOT_SIZE,  // The actual item render size
                GRID_PADDING,
                Minecraft.getInstance().getWindow().getGuiScale()
        );

        statusText = String.format("Captured %d frames! Processing...", capturedFrames.size());

        // Notify callback
        if (onComplete != null) {
            onComplete.accept(result);
        }

        // Close screen
        this.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;  // Don't pause - we need animations to keep running!
    }

    /**
     * Result of animation capture.
     */
    public static class CaptureResult {
        public final List<ItemStack> items;
        public final List<BufferedImage> frames;
        public final int gridColumns;
        public final int gridRows;
        public final int cellSize;
        public final int slotSize;  // Actual item render size (may be scaled)
        public final int gridPadding;
        public final double guiScale;

        public CaptureResult(List<ItemStack> items, List<BufferedImage> frames,
                             int gridColumns, int gridRows, int cellSize, int slotSize,
                             int gridPadding, double guiScale) {
            this.items = items;
            this.frames = frames;
            this.gridColumns = gridColumns;
            this.gridRows = gridRows;
            this.cellSize = cellSize;
            this.slotSize = slotSize;
            this.gridPadding = gridPadding;
            this.guiScale = guiScale;
        }

        /**
         * Extract frames for a specific item by index.
         *
         * @param itemIndex Index of the item in the grid
         * @return List of frames for that item
         */
        public List<BufferedImage> extractItemFrames(int itemIndex) {
            List<BufferedImage> itemFrames = new ArrayList<>();

            int col = itemIndex % gridColumns;
            int row = itemIndex / gridColumns;

            // Calculate pixel position in captured images
            int scaledCellSize = (int) (cellSize * guiScale);
            int scaledPadding = (int) (gridPadding * guiScale);
            int scaledSlotSize = (int) (slotSize * guiScale);

            int itemX = scaledPadding + col * scaledCellSize;
            int itemY = scaledPadding + row * scaledCellSize;

            LOGGER.debug("RecipeFlow: Extracting item {} at ({}, {}) size {}x{} from frame {}x{}",
                    itemIndex, itemX, itemY, scaledSlotSize, scaledSlotSize,
                    frames.isEmpty() ? 0 : frames.get(0).getWidth(),
                    frames.isEmpty() ? 0 : frames.get(0).getHeight());

            for (BufferedImage frame : frames) {
                try {
                    // Extract the item region
                    BufferedImage itemFrame = frame.getSubimage(
                            itemX, itemY, scaledSlotSize, scaledSlotSize);
                    itemFrames.add(itemFrame);
                } catch (Exception e) {
                    LOGGER.warn("RecipeFlow AnimationCapture: Failed to extract item frame: {}", e.getMessage());
                }
            }

            return itemFrames;
        }

        /**
         * Extract frames for all items.
         *
         * @return Map from item to its frames
         */
        public Map<ItemStack, List<BufferedImage>> extractAllItemFrames() {
            Map<ItemStack, List<BufferedImage>> result = new HashMap<>();
            for (int i = 0; i < items.size(); i++) {
                result.put(items.get(i), extractItemFrames(i));
            }
            return result;
        }
    }
}
