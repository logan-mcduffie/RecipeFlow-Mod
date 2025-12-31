package com.recipeflow.mod.v120plus.util;

import com.recipeflow.mod.core.config.ModConfig;
import com.recipeflow.mod.core.export.IconMetadata;
import com.recipeflow.mod.core.upload.ChunkedUploader;
import com.recipeflow.mod.core.upload.UploadResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Handles uploading of icon files to the server.
 * Creates a ZIP archive containing all icons and metadata,
 * then uploads it using the chunked upload protocol.
 */
public class IconUploader {

    private static final int ICON_CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks for icons
    private final ModConfig config;

    /**
     * Create a new icon uploader.
     *
     * @param config Mod configuration
     */
    public IconUploader(ModConfig config) {
        this.config = config;
    }

    /**
     * Upload icons to the server.
     * Creates a ZIP archive containing all icon files and metadata,
     * then uploads it using chunked protocol.
     *
     * @param iconDir The directory containing exported icons
     * @param metadata The icon metadata object
     * @param version The modpack version
     * @param callback Progress callback (may be null)
     * @return UploadResult with success/failure details
     */
    public UploadResult uploadIcons(Path iconDir, IconMetadata metadata, String version,
                                    ChunkedUploader.ProgressCallback callback) {
        try {
            // Step 1: Create ZIP archive in memory
            if (callback != null) {
                callback.onProgress(0, 100, "Creating icon archive...");
            }

            byte[] zipData = createIconZip(iconDir, metadata);

            if (callback != null) {
                callback.onProgress(5, 100, "Icon archive created (" + formatBytes(zipData.length) + ")");
            }

            // Step 2: Upload via chunked protocol
            ChunkedUploader uploader = new ChunkedUploader(config, ICON_CHUNK_SIZE);
            return uploader.upload(zipData, version, "icons", callback);

        } catch (IOException e) {
            return UploadResult.error("Failed to create icon archive: " + e.getMessage(), e);
        } catch (Exception e) {
            return UploadResult.error("Icon upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Create a ZIP archive containing all icons and metadata.
     * The ZIP structure is:
     * icons.zip
     * ├── icon-metadata.json
     * └── [namespace]/[item].png (or .webp)
     *
     * @param iconDir Directory containing exported icons
     * @param metadata Icon metadata object
     * @return ZIP file contents as byte array
     * @throws IOException If ZIP creation fails
     */
    private byte[] createIconZip(Path iconDir, IconMetadata metadata) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Add icon-metadata.json
            ZipEntry metadataEntry = new ZipEntry("icon-metadata.json");
            zos.putNextEntry(metadataEntry);
            zos.write(metadata.toJson().getBytes("UTF-8"));
            zos.closeEntry();

            // Add all icon files
            for (Map.Entry<String, IconMetadata.IconEntry> entry : metadata.getIcons().entrySet()) {
                String itemId = entry.getKey();
                IconMetadata.IconEntry iconEntry = entry.getValue();
                String filename = iconEntry.getFilename();

                // Read icon file from disk
                Path iconPath = iconDir.resolve(filename);

                if (Files.exists(iconPath)) {
                    // Add icon to ZIP
                    ZipEntry iconZipEntry = new ZipEntry(filename);
                    zos.putNextEntry(iconZipEntry);
                    Files.copy(iconPath, zos);
                    zos.closeEntry();
                } else {
                    // Log warning but continue - metadata references this file but it's missing
                    System.err.println("Warning: Icon file not found: " + iconPath + " (referenced by " + itemId + ")");
                }
            }
        }

        return baos.toByteArray();
    }

    /**
     * Format byte count as human-readable string.
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }
}
