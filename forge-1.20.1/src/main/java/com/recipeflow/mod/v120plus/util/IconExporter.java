package com.recipeflow.mod.v120plus.util;

import com.recipeflow.mod.core.export.IconMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exports item icons from the game as image files.
 * Supports static PNG and animated WebP formats.
 * Uses async file writing for improved performance.
 */
@OnlyIn(Dist.CLIENT)
public class IconExporter {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int PROGRESS_PERCENT_INTERVAL = 10; // Log progress every N percent
    private static final int WRITE_THREAD_COUNT = 4; // Number of threads for async file writing

    private final AnimatedIconRenderer renderer;
    private final FluidIconRenderer fluidRenderer;

    public IconExporter() {
        this.renderer = new AnimatedIconRenderer();
        this.fluidRenderer = new FluidIconRenderer();
    }

    /**
     * Export all item icons to the specified directory.
     * Uses batch rendering and async file writing for significantly improved performance.
     *
     * @param outputDir The directory to save icons to
     * @param callback Progress callback (may be null)
     * @return IconMetadata containing information about all exported icons
     */
    public IconMetadata exportAllIcons(Path outputDir, ExportCallback callback) {
        IconMetadata metadata = new IconMetadata();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to create output directory: {}", e.getMessage());
            return metadata;
        }

        // Collect all items into lists for batch processing
        List<ResourceLocation> itemIds = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        List<Boolean> isAnimated = new ArrayList<>();

        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            ResourceLocation itemId = entry.getKey().location();
            Item item = entry.getValue();
            ItemStack stack = new ItemStack(item, 1);

            itemIds.add(itemId);
            stacks.add(stack);
            isAnimated.add(renderer.isAnimated(stack));
        }

        int total = stacks.size();
        LOGGER.info("RecipeFlow: Starting batched icon export for {} items", total);

        // Separate animated and non-animated items
        List<ItemStack> staticStacks = new ArrayList<>();
        Map<Integer, Integer> globalToStaticIndex = new java.util.HashMap<>();

        for (int i = 0; i < total; i++) {
            if (!isAnimated.get(i)) {
                globalToStaticIndex.put(i, staticStacks.size());
                staticStacks.add(stacks.get(i));
            }
        }

        LOGGER.info("RecipeFlow: Rendering {} static items in batch...", staticStacks.size());
        long renderStart = System.currentTimeMillis();

        // Batch render ALL non-animated items at once
        Map<Integer, BufferedImage> renderedImages = renderer.renderItemBatch(staticStacks);

        long renderTime = System.currentTimeMillis() - renderStart;
        LOGGER.info("RecipeFlow: Batch rendering complete in {}ms", renderTime);

        // Create thread pool for async file writing
        ExecutorService writeExecutor = Executors.newFixedThreadPool(WRITE_THREAD_COUNT);

        // Thread-safe collections for tracking progress and results
        ConcurrentHashMap<String, IconMetadata.IconEntry> iconEntries = new ConcurrentHashMap<>();
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger lastLoggedPercent = new AtomicInteger(-1);

        LOGGER.info("RecipeFlow: Saving icons to disk with {} threads...", WRITE_THREAD_COUNT);
        long saveStart = System.currentTimeMillis();

        // Queue all file writes
        for (int i = 0; i < total; i++) {
            final int index = i;
            final ResourceLocation itemId = itemIds.get(i);
            final ItemStack stack = stacks.get(i);
            final boolean animated = isAnimated.get(i);

            if (animated) {
                // Animated items are rendered individually (they need frame extraction)
                // Do this on the main thread since it requires OpenGL
                try {
                    IconMetadata.IconEntry iconEntry = exportAnimatedIcon(stack, itemId.getNamespace(), itemId.getPath(), outputDir);
                    if (iconEntry != null) {
                        iconEntries.put(itemId.toString(), iconEntry);
                    }
                } catch (Exception e) {
                    LOGGER.warn("RecipeFlow: Failed to export animated icon for {}: {}", itemId, e.getMessage());
                }

                int completed = completedCount.incrementAndGet();
                logProgress(completed, total, lastLoggedPercent);
                if (callback != null) {
                    callback.onProgress(completed, total, itemId.toString());
                }
            } else {
                // Get the batch-rendered image using the static index
                Integer staticIndex = globalToStaticIndex.get(index);
                BufferedImage image = staticIndex != null ? renderedImages.get(staticIndex) : null;

                if (image != null) {
                    // Queue async file write
                    final BufferedImage finalImage = image;
                    writeExecutor.submit(() -> {
                        try {
                            IconMetadata.IconEntry iconEntry = saveStaticIconAsync(
                                finalImage, itemId.getNamespace(), itemId.getPath(), outputDir
                            );
                            if (iconEntry != null) {
                                iconEntries.put(itemId.toString(), iconEntry);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("RecipeFlow: Failed to save icon for {}: {}", itemId, e.getMessage());
                        }

                        int completed = completedCount.incrementAndGet();
                        logProgress(completed, total, lastLoggedPercent);
                        if (callback != null) {
                            callback.onProgress(completed, total, itemId.toString());
                        }
                    });
                } else {
                    int completed = completedCount.incrementAndGet();
                    logProgress(completed, total, lastLoggedPercent);
                }
            }
        }

        // Wait for all writes to complete
        writeExecutor.shutdown();
        try {
            boolean finished = writeExecutor.awaitTermination(5, TimeUnit.MINUTES);
            if (!finished) {
                LOGGER.warn("RecipeFlow: File writing timed out, some icons may not be saved");
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("RecipeFlow: File writing interrupted");
            writeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        long saveTime = System.currentTimeMillis() - saveStart;
        LOGGER.info("RecipeFlow: File saving complete in {}ms", saveTime);

        // Build final metadata from concurrent results
        for (Map.Entry<String, IconMetadata.IconEntry> entry : iconEntries.entrySet()) {
            metadata.addIcon(entry.getKey(), entry.getValue());
        }

        // Save metadata file
        try {
            Path metadataPath = outputDir.resolve("icon-metadata.json");
            Files.writeString(metadataPath, metadata.toJson());
            LOGGER.info("RecipeFlow: Saved icon metadata to {}", metadataPath);
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to save icon metadata: {}", e.getMessage());
        }

        LOGGER.info("RecipeFlow: Icon export complete. {} icons exported.", metadata.size());

        // Clean up renderer
        renderer.dispose();

        return metadata;
    }

    /**
     * Log progress at regular intervals (thread-safe).
     */
    private void logProgress(int completed, int total, AtomicInteger lastLoggedPercent) {
        int currentPercent = (completed * 100) / total;
        int percentBucket = currentPercent / PROGRESS_PERCENT_INTERVAL * PROGRESS_PERCENT_INTERVAL;
        int lastLogged = lastLoggedPercent.get();

        if (percentBucket > lastLogged && lastLoggedPercent.compareAndSet(lastLogged, percentBucket)) {
            LOGGER.info("RecipeFlow: Saved {}/{} icons ({}%)", completed, total, currentPercent);
        }
    }

    /**
     * Save a pre-rendered static icon to disk (async-safe version).
     */
    private IconMetadata.IconEntry saveStaticIconAsync(BufferedImage image, String namespace, String itemName, Path outputDir) {
        String safeNamespace = namespace.replace("/", "_").replace("\\", "_");
        String safeItemName = itemName.replace("/", "_").replace("\\", "_");
        String filename = safeNamespace + "_" + safeItemName + ".png";
        Path filePath = outputDir.resolve(filename);

        try {
            ImageIO.write(image, "PNG", filePath.toFile());
            return IconMetadata.IconEntry.staticIcon(filename);
        } catch (IOException e) {
            LOGGER.warn("RecipeFlow: Failed to save PNG {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Export a single item's icon.
     *
     * @param stack The item stack to export
     * @param itemId The item's resource location
     * @param outputDir The output directory
     * @return IconEntry with file information, or null on failure
     */
    public IconMetadata.IconEntry exportIcon(ItemStack stack, ResourceLocation itemId, Path outputDir) {
        if (stack.isEmpty()) {
            return null;
        }

        String namespace = itemId.getNamespace();
        String itemName = itemId.getPath();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOGGER.warn("RecipeFlow: Failed to create directory {}: {}", outputDir, e.getMessage());
            return null;
        }

        boolean animated = renderer.isAnimated(stack);

        if (animated) {
            return exportAnimatedIcon(stack, namespace, itemName, outputDir);
        } else {
            return exportStaticIcon(stack, namespace, itemName, outputDir);
        }
    }

    /**
     * Export a static (non-animated) icon as PNG.
     */
    private IconMetadata.IconEntry exportStaticIcon(ItemStack stack, String namespace, String itemName, Path outputDir) {
        // Replace slashes and backslashes with underscores to create flat filenames
        String safeNamespace = namespace.replace("/", "_").replace("\\", "_");
        String safeItemName = itemName.replace("/", "_").replace("\\", "_");
        String filename = safeNamespace + "_" + safeItemName + ".png";
        Path filePath = outputDir.resolve(filename);

        try {
            BufferedImage image = renderer.renderItem(stack);
            ImageIO.write(image, "PNG", filePath.toFile());

            return IconMetadata.IconEntry.staticIcon(filename);
        } catch (IOException e) {
            LOGGER.warn("RecipeFlow: Failed to save PNG {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Export an animated icon as GIF with proper per-frame timing.
     */
    private IconMetadata.IconEntry exportAnimatedIcon(ItemStack stack, String namespace, String itemName, Path outputDir) {
        // Replace slashes and backslashes with underscores to create flat filenames
        String safeNamespace = namespace.replace("/", "_").replace("\\", "_");
        String safeItemName = itemName.replace("/", "_").replace("\\", "_");

        // Use different rendering approaches based on the item type:
        // - GTCEu machines: Use universal atlas tick method (works with custom IRenderer)
        // - Other 3D blocks: Use full-block sprite-specific method
        // - 2D items: Use simpler sprite-extraction method
        AnimationSequence sequence;
        if (isBlockItem(stack)) {
            if (GTCEuIconHelper.isGTCEuMachine(stack)) {
                // GTCEu machines use custom IRenderer that bypasses standard model quads.
                // Use the universal method that ticks ALL animated textures in the atlas.
                LOGGER.debug("RecipeFlow: Using universal animation rendering for GTCEu machine {}:{}", namespace, itemName);
                sequence = renderer.renderAnimationSequenceUniversal(stack);
            } else {
                LOGGER.debug("RecipeFlow: Using full-block animation rendering for {}:{}", namespace, itemName);
                sequence = renderer.renderAnimationSequenceFullBlock(stack);
            }
        } else {
            sequence = renderer.renderAnimationSequence(stack);
        }
        List<BufferedImage> frames = sequence.frames();
        List<Integer> frameDurations = sequence.frameDurationsMs();
        int frameCount = frames.size();

        // Calculate average frame time for metadata (for backwards compatibility)
        int avgFrameTimeMs = frameDurations.stream().mapToInt(Integer::intValue).sum() / Math.max(1, frameCount);

        // Save as animated GIF with per-frame timing
        String filename = safeNamespace + "_" + safeItemName + ".gif";
        Path filePath = outputDir.resolve(filename);

        try {
            saveAnimatedGif(frames, frameDurations, filePath);
            LOGGER.debug("RecipeFlow: Saved animated GIF for {}:{} with {} frames (per-frame timing)",
                    namespace, itemName, frameCount);
            return IconMetadata.IconEntry.animatedIcon(
                    filename,
                    frameCount,
                    avgFrameTimeMs
            );
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to save animated GIF for {}:{} - {}", namespace, itemName, e.getMessage());
        }

        // Fallback: save red error square to make failures visible
        String pngFilename = safeNamespace + "_" + safeItemName + ".png";
        Path pngFilePath = outputDir.resolve(pngFilename);

        try {
            BufferedImage errorImage = createErrorImage(128);
            ImageIO.write(errorImage, "PNG", pngFilePath.toFile());
            LOGGER.warn("RecipeFlow: Saved error placeholder for animated icon {}:{}", namespace, itemName);
            return IconMetadata.IconEntry.animatedIcon(
                    pngFilename,
                    frameCount,
                    avgFrameTimeMs
            );
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to save error placeholder {}: {}", pngFilePath, e.getMessage());
            return null;
        }
    }

    /**
     * Create a red error image to indicate failed icon export.
     */
    private BufferedImage createErrorImage(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int red = 0xFFFF0000; // Solid red
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, red);
            }
        }
        return image;
    }

    /**
     * Save frames as an animated GIF file with per-frame timing.
     * Uses Java's built-in GIF writer with proper metadata for animation.
     *
     * @param frames List of frame images
     * @param frameDurationsMs List of durations in milliseconds for each frame
     * @param filePath Path to save the GIF
     */
    private void saveAnimatedGif(List<BufferedImage> frames, List<Integer> frameDurationsMs, Path filePath)
            throws IOException {
        if (frames.isEmpty()) {
            throw new IOException("No frames to save");
        }

        if (frames.size() != frameDurationsMs.size()) {
            throw new IOException("Frame count (" + frames.size() + ") doesn't match duration count (" + frameDurationsMs.size() + ")");
        }

        // Get GIF writer
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        if (!writers.hasNext()) {
            throw new IOException("GIF writer not available");
        }

        ImageWriter writer = writers.next();

        try (ImageOutputStream output = ImageIO.createImageOutputStream(filePath.toFile())) {
            writer.setOutput(output);

            // Get default write param
            ImageWriteParam writeParam = writer.getDefaultWriteParam();

            // Start writing sequence
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                BufferedImage frame = frames.get(i);
                int frameTimeMs = frameDurationsMs.get(i);

                // Convert ARGB to indexed color for GIF (GIF doesn't support true alpha)
                BufferedImage gifFrame = convertToGifCompatible(frame);

                // Get metadata for this frame
                ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromRenderedImage(gifFrame);
                IIOMetadata frameMetadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);

                // Configure with this frame's specific timing
                configureGifMetadata(frameMetadata, frameTimeMs, 0);

                writer.writeToSequence(new IIOImage(gifFrame, null, frameMetadata), writeParam);
            }

            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Configure GIF metadata for animation.
     */
    private void configureGifMetadata(IIOMetadata metadata, int frameTimeMs, int loopCount) throws IOException {
        String metaFormatName = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metaFormatName);

        // Find or create GraphicControlExtension node
        IIOMetadataNode graphicsControlExtension = getOrCreateNode(root, "GraphicControlExtension");

        // GIF delay is in centiseconds (1/100th of a second)
        int delayCs = Math.max(1, frameTimeMs / 10);
        graphicsControlExtension.setAttribute("delayTime", String.valueOf(delayCs));
        graphicsControlExtension.setAttribute("disposalMethod", "restoreToBackgroundColor");
        graphicsControlExtension.setAttribute("userInputFlag", "FALSE");
        graphicsControlExtension.setAttribute("transparentColorFlag", "TRUE");
        graphicsControlExtension.setAttribute("transparentColorIndex", "0");

        // Find or create ApplicationExtensions for looping
        IIOMetadataNode appExtensionsNode = getOrCreateNode(root, "ApplicationExtensions");
        IIOMetadataNode appExtension = new IIOMetadataNode("ApplicationExtension");
        appExtension.setAttribute("applicationID", "NETSCAPE");
        appExtension.setAttribute("authenticationCode", "2.0");

        // Loop count: 0 = loop forever
        byte[] loopBytes = new byte[]{0x1, (byte) (loopCount & 0xFF), (byte) ((loopCount >> 8) & 0xFF)};
        appExtension.setUserObject(loopBytes);
        appExtensionsNode.appendChild(appExtension);

        metadata.setFromTree(metaFormatName, root);
    }

    /**
     * Get or create a child node with the given name.
     */
    private IIOMetadataNode getOrCreateNode(IIOMetadataNode root, String nodeName) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(nodeName)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(nodeName);
        root.appendChild(node);
        return node;
    }

    /**
     * Convert an ARGB image to GIF-compatible indexed color.
     * GIF only supports 256 colors with 1-bit transparency.
     */
    private BufferedImage convertToGifCompatible(BufferedImage source) {
        // Create indexed color image
        BufferedImage result = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );

        // Copy pixels, treating fully transparent pixels specially
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;

                if (alpha < 128) {
                    // Treat as fully transparent
                    result.setRGB(x, y, 0x00000000);
                } else {
                    // Fully opaque
                    result.setRGB(x, y, argb | 0xFF000000);
                }
            }
        }

        return result;
    }

    /**
     * Callback interface for export progress.
     */
    public interface ExportCallback {
        /**
         * Called after each item is processed.
         *
         * @param current Current item number (1-based)
         * @param total Total number of items
         * @param itemId The item ID that was just processed
         */
        void onProgress(int current, int total, String itemId);

        /**
         * Called when an error occurs processing an item.
         *
         * @param itemId The item ID that failed
         * @param error The error that occurred
         */
        default void onError(String itemId, Exception error) {
            // Default: do nothing
        }
    }

    /**
     * Check if an ItemStack represents a block item (renders as a 3D block).
     * This is used to determine whether to use full-block animation rendering
     * which captures the complete composited result with all overlays.
     *
     * @param stack The item stack to check
     * @return true if the item is a block or renders as a 3D model
     */
    private boolean isBlockItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        // Check if it's a BlockItem
        if (stack.getItem() instanceof BlockItem) {
            return true;
        }

        // Also check if the model is 3D (some items render as 3D blocks)
        try {
            BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(stack, null, null, 0);
            return model.isGui3d();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Export all fluid icons to the specified directory.
     *
     * @param outputDir The directory to save icons to
     * @param callback Progress callback (may be null)
     * @return IconMetadata containing information about all exported fluid icons
     */
    public IconMetadata exportAllFluidIcons(Path outputDir, ExportCallback callback) {
        IconMetadata metadata = new IconMetadata();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to create output directory: {}", e.getMessage());
            return metadata;
        }

        // Collect all fluids
        List<ResourceLocation> fluidIds = new ArrayList<>();
        List<Fluid> fluids = new ArrayList<>();

        for (var entry : ForgeRegistries.FLUIDS.getEntries()) {
            ResourceLocation fluidId = entry.getKey().location();
            Fluid fluid = entry.getValue();

            // Skip empty fluid
            if (fluid == Fluids.EMPTY) {
                continue;
            }

            fluidIds.add(fluidId);
            fluids.add(fluid);
        }

        int total = fluids.size();
        LOGGER.info("RecipeFlow: Starting fluid icon export for {} fluids", total);

        for (int i = 0; i < total; i++) {
            ResourceLocation fluidId = fluidIds.get(i);
            Fluid fluid = fluids.get(i);

            try {
                IconMetadata.IconEntry iconEntry = exportFluidIcon(fluid, fluidId, outputDir);
                if (iconEntry != null) {
                    metadata.addIcon(fluidId.toString(), iconEntry);
                }
            } catch (Exception e) {
                LOGGER.warn("RecipeFlow: Failed to export fluid icon for {}: {}", fluidId, e.getMessage());
                if (callback != null) {
                    callback.onError(fluidId.toString(), e);
                }
            }

            if (callback != null) {
                callback.onProgress(i + 1, total, fluidId.toString());
            }

            // Log progress
            int percent = ((i + 1) * 100) / total;
            if (percent % PROGRESS_PERCENT_INTERVAL == 0) {
                LOGGER.info("RecipeFlow: Exported {}/{} fluid icons ({}%)", i + 1, total, percent);
            }
        }

        LOGGER.info("RecipeFlow: Fluid icon export complete. {} icons exported.", metadata.size());
        return metadata;
    }

    /**
     * Export a single fluid's icon.
     *
     * @param fluid The fluid to export
     * @param fluidId The fluid's resource location
     * @param outputDir The output directory
     * @return IconEntry with file information, or null on failure
     */
    public IconMetadata.IconEntry exportFluidIcon(Fluid fluid, ResourceLocation fluidId, Path outputDir) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return null;
        }

        String namespace = fluidId.getNamespace();
        String fluidName = fluidId.getPath();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOGGER.warn("RecipeFlow: Failed to create directory {}: {}", outputDir, e.getMessage());
            return null;
        }

        boolean animated = fluidRenderer.isAnimated(fluid);

        if (animated) {
            return exportAnimatedFluidIcon(fluid, namespace, fluidName, outputDir);
        } else {
            return exportStaticFluidIcon(fluid, namespace, fluidName, outputDir);
        }
    }

    /**
     * Export a static (non-animated) fluid icon as PNG.
     */
    private IconMetadata.IconEntry exportStaticFluidIcon(Fluid fluid, String namespace, String fluidName, Path outputDir) {
        String safeNamespace = namespace.replace("/", "_").replace("\\", "_");
        String safeFluidName = fluidName.replace("/", "_").replace("\\", "_");
        String filename = safeNamespace + "_" + safeFluidName + ".png";
        Path filePath = outputDir.resolve(filename);

        try {
            BufferedImage image = fluidRenderer.renderFluid(fluid);
            ImageIO.write(image, "PNG", filePath.toFile());

            return IconMetadata.IconEntry.staticIcon(filename);
        } catch (IOException e) {
            LOGGER.warn("RecipeFlow: Failed to save fluid PNG {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Export an animated fluid icon as GIF with proper per-frame timing.
     */
    private IconMetadata.IconEntry exportAnimatedFluidIcon(Fluid fluid, String namespace, String fluidName, Path outputDir) {
        String safeNamespace = namespace.replace("/", "_").replace("\\", "_");
        String safeFluidName = fluidName.replace("/", "_").replace("\\", "_");

        AnimationSequence sequence = fluidRenderer.renderFluidAnimation(fluid);
        List<BufferedImage> frames = sequence.frames();
        List<Integer> frameDurations = sequence.frameDurationsMs();
        int frameCount = frames.size();

        // Calculate average frame time for metadata
        int avgFrameTimeMs = frameDurations.stream().mapToInt(Integer::intValue).sum() / Math.max(1, frameCount);

        // Save as animated GIF
        String filename = safeNamespace + "_" + safeFluidName + ".gif";
        Path filePath = outputDir.resolve(filename);

        try {
            saveAnimatedGif(frames, frameDurations, filePath);
            LOGGER.debug("RecipeFlow: Saved animated GIF for fluid {}:{} with {} frames",
                    namespace, fluidName, frameCount);
            return IconMetadata.IconEntry.animatedIcon(filename, frameCount, avgFrameTimeMs);
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to save animated GIF for fluid {}:{} - {}",
                    namespace, fluidName, e.getMessage());
        }

        // Fallback: save static PNG
        String pngFilename = safeNamespace + "_" + safeFluidName + ".png";
        Path pngFilePath = outputDir.resolve(pngFilename);

        try {
            BufferedImage staticFrame = fluidRenderer.renderFluid(fluid);
            ImageIO.write(staticFrame, "PNG", pngFilePath.toFile());
            LOGGER.warn("RecipeFlow: Saved fallback PNG for animated fluid {}:{}", namespace, fluidName);
            return IconMetadata.IconEntry.animatedIcon(pngFilename, frameCount, avgFrameTimeMs);
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to save fallback PNG {}: {}", pngFilePath, e.getMessage());
            return null;
        }
    }

    /**
     * Export icons for a specific set of fluid IDs (useful for recipe-based export).
     *
     * @param fluidIds Set of fluid IDs to export (e.g., "minecraft:water")
     * @param outputDir The directory to save icons to
     * @param callback Progress callback (may be null)
     * @return IconMetadata containing information about exported fluid icons
     */
    public IconMetadata exportFluidIcons(java.util.Set<String> fluidIds, Path outputDir, ExportCallback callback) {
        IconMetadata metadata = new IconMetadata();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOGGER.error("RecipeFlow: Failed to create output directory: {}", e.getMessage());
            return metadata;
        }

        int total = fluidIds.size();
        int current = 0;

        LOGGER.info("RecipeFlow: Exporting {} fluid icons", total);

        for (String fluidIdStr : fluidIds) {
            current++;

            ResourceLocation fluidId = ResourceLocation.tryParse(fluidIdStr);
            if (fluidId == null) {
                LOGGER.warn("RecipeFlow: Invalid fluid ID: {}", fluidIdStr);
                continue;
            }

            Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
            if (fluid == null || fluid == Fluids.EMPTY) {
                LOGGER.warn("RecipeFlow: Fluid not found: {}", fluidIdStr);
                continue;
            }

            try {
                IconMetadata.IconEntry iconEntry = exportFluidIcon(fluid, fluidId, outputDir);
                if (iconEntry != null) {
                    metadata.addIcon(fluidIdStr, iconEntry);
                }
            } catch (Exception e) {
                LOGGER.warn("RecipeFlow: Failed to export fluid icon for {}: {}", fluidIdStr, e.getMessage());
                if (callback != null) {
                    callback.onError(fluidIdStr, e);
                }
            }

            if (callback != null) {
                callback.onProgress(current, total, fluidIdStr);
            }
        }

        LOGGER.info("RecipeFlow: Exported {} fluid icons", metadata.size());
        return metadata;
    }
}
