# Task: Implement Chunked Upload with Hash Verification

## Overview

Implement a robust upload system that sends icon data in chunks and verifies the upload completed correctly using hash comparison.

## Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │     │   Server    │     │   Storage   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       │  1. Build data    │                   │
       │  2. Compute hash  │                   │
       │                   │                   │
       │  POST /upload/init                    │
       │  { version, type, hash, totalSize }   │
       │──────────────────>│                   │
       │                   │                   │
       │  { uploadId }     │                   │
       │<──────────────────│                   │
       │                   │                   │
       │  POST /upload/chunk                   │
       │  { uploadId, index, data }            │
       │──────────────────>│                   │
       │                   │  Store chunk      │
       │                   │─────────────────->│
       │  { received }     │                   │
       │<──────────────────│                   │
       │                   │                   │
       │  ... repeat for all chunks ...        │
       │                   │                   │
       │  POST /upload/finalize                │
       │  { uploadId }     │                   │
       │──────────────────>│                   │
       │                   │  Assemble chunks  │
       │                   │  Compute hash     │
       │                   │  Verify match     │
       │                   │─────────────────->│
       │                   │                   │
       │  { verified, hash }                   │
       │<──────────────────│                   │
       │                   │                   │
       │  3. Compare hash  │                   │
       │  4. Confirm sync  │                   │
       └───────────────────┴───────────────────┘
```

## Client Implementation

### Upload Payload Structure

The client builds a complete payload containing all icon data:

```json
{
  "version": "1.2.3",
  "type": "icons",
  "timestamp": 1704067200000,
  "icons": [
    {
      "id": "minecraft:diamond",
      "type": "static",
      "data": "base64_png_data..."
    },
    {
      "id": "gtceu:max_input_bus",
      "type": "animated",
      "spriteSheet": "base64_png_data...",
      "metadata": { ... }
    }
  ]
}
```

### Hash Computation

Use SHA-256 to hash the complete payload:

```java
public class PayloadHasher {
    public static String computeHash(byte[] payload) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(payload);
        return bytesToHex(hash);
    }
}
```

### Chunking Strategy

- Default chunk size: 2MB
- Compress each chunk with gzip before sending
- Track chunk index for reassembly order

```java
public class ChunkedIconUploader {
    private static final int CHUNK_SIZE = 2 * 1024 * 1024; // 2MB

    public UploadResult upload(byte[] payload, String version) {
        String hash = PayloadHasher.computeHash(payload);

        // 1. Initialize upload
        String uploadId = initUpload(version, "icons", hash, payload.length);

        // 2. Send chunks
        for (int i = 0; i < payload.length; i += CHUNK_SIZE) {
            byte[] chunk = Arrays.copyOfRange(payload, i,
                Math.min(i + CHUNK_SIZE, payload.length));
            sendChunk(uploadId, i / CHUNK_SIZE, compress(chunk));
        }

        // 3. Finalize and verify
        FinalizeResponse response = finalizeUpload(uploadId);

        if (!response.hash.equals(hash)) {
            return UploadResult.failure("Hash mismatch - upload corrupted");
        }

        return UploadResult.success(response.bytesStored);
    }
}
```

## Server API Endpoints

### POST /api/upload/init

Initialize a chunked upload session.

**Request:**
```json
{
  "version": "1.2.3",
  "type": "icons",
  "hash": "abc123...",
  "totalSize": 15728640,
  "chunkCount": 8
}
```

**Response:**
```json
{
  "uploadId": "upload_xyz789",
  "expiresAt": 1704070800000
}
```

### POST /api/upload/chunk

Upload a single chunk.

**Request:**
- Header: `X-Upload-Id: upload_xyz789`
- Header: `X-Chunk-Index: 3`
- Header: `Content-Encoding: gzip`
- Body: Compressed chunk data

**Response:**
```json
{
  "received": true,
  "chunkIndex": 3,
  "bytesReceived": 2097152
}
```

### POST /api/upload/finalize

Complete the upload and verify integrity.

**Request:**
```json
{
  "uploadId": "upload_xyz789"
}
```

**Response:**
```json
{
  "success": true,
  "hash": "abc123...",
  "totalBytes": 15728640,
  "chunksAssembled": 8
}
```

## Error Handling

### Retry Logic

- Retry failed chunks up to 3 times
- Exponential backoff: 1s, 2s, 4s
- Resume from last successful chunk on connection loss

### Hash Mismatch

If server hash doesn't match client hash:
1. Log detailed error
2. Delete partial upload on server
3. Optionally retry entire upload
4. Report failure to user with actionable message

## Files to Create/Modify

### New Files
- `upload/PayloadHasher.java` - SHA-256 hashing utility
- `upload/ChunkedIconUploader.java` - Icon-specific chunked upload

### Modify
- `upload/ChunkedUploader.java` - Add hash verification to existing uploader
- `command/SyncCommand.java` - Use new verification flow

## Acceptance Criteria

- [ ] Client computes SHA-256 hash before upload
- [ ] Upload split into 2MB chunks
- [ ] Chunks compressed with gzip
- [ ] Server reassembles and verifies hash
- [ ] Client confirms hash match
- [ ] Failed chunks retry up to 3 times
- [ ] Hash mismatch reported clearly to user
- [ ] Progress messages show chunk upload status
