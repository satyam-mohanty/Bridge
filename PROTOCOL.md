# Bridge Protocol Specification (v1)

Bridge is a zero-idle-cost, secure LAN protocol connecting a macOS host (server) and an Android device (client).

---

## 1. Transport & Network

- **Main Transport**: Raw TCP stream on port `48250`.
- **Server**: macOS host binds a TCP listener (`NWListener`) on port `48250`.
- **Client**: Android client connects via `java.net.Socket`.
- **TCP Keepalive**: Enabled on both sides.
- **Heartbeat & Liveness**:
  - Heartbeat interval: **15 seconds** of silence.
  - Connection dead timeout: **40 seconds** without any valid received frames.
- **UDP Discovery Beacon**:
  - macOS broadcasts UDP datagrams to `255.255.255.255:48250` every **3 seconds** ONLY when no client is currently connected.
  - Beacon payload (UTF-8 JSON):
    ```json
    {
      "v": 1,
      "name": "<Mac Machine Name>",
      "port": 48250
    }
    ```
- **mDNS / Bonjour Discovery**:
  - Service Type: `_bridge._tcp`
  - Domain: `local.`
  - Port: `48250`

---

## 2. Pairing & Key Exchange

Pairing is out-of-band via QR code displayed on macOS and scanned by Android, or manually pasted.

### QR Code / Pairing Payload (JSON)
```json
{
  "v": 1,
  "name": "My MacBook",
  "key": "BASE64_ENCODED_32_BYTES_PRESHARED_KEY",
  "port": 48250
}
```

- `key`: Exactly 32 random bytes (256-bit symmetric key), base64-encoded.
- **Mac Storage**: Stored securely in macOS Keychain.
- **Android Storage**: Stored securely in Android Keystore / Encrypted Preferences.
- **Manual Entry Fallback**: User can copy the pairing JSON string from the Mac menu bar and paste it into the Android app.

---

## 3. Framing & Cryptography

Every message sent over TCP is encrypted using **AES-256-GCM**.

### Frame Structure on TCP Wire
```
+---------------------------------------+---------------------------------------+
|  Length (4 bytes, Big-Endian uint32)  |   Ciphertext + Tag (Length bytes)     |
+---------------------------------------+---------------------------------------+
```

- `Length`: Total number of bytes in the encrypted body (Ciphertext + 16-byte GCM authentication tag).
- Maximum frame length: `131072` (128 KB) to guard against memory exhaustion.

### AES-256-GCM Nonce / IV Layout (12 bytes / 96 bits)
To eliminate IV collision risk and reject replays, both sides track a 64-bit monotonically increasing message counter starting at `0`.

```
Byte 0       : Direction Byte
               0x01 = Mac -> Android
               0x02 = Android -> Mac
Bytes 1..3   : Reserved / Zero Padding (0x00, 0x00, 0x00)
Bytes 4..11  : 64-bit Monotonic Message Counter (Big-Endian uint64)
```

### Encryption & Decryption Mechanics
- **Key**: 32-byte pre-shared AES key.
- **Additional Authenticated Data (AAD)**: Empty (length 0).
- **Tag Length**: 16 bytes (128 bits).
- **Ciphertext Body**: Contains `Plaintext bytes` followed by the 16-byte authentication tag (`combined` representation in Apple CryptoKit and standard `Cipher.doFinal()` in Java Cryptography Architecture).
- **Replay Protection**:
  - Each side maintains `expectedIncomingCounter = 0`.
  - When a message is decrypted successfully, its counter must equal `expectedIncomingCounter`. After processing, `expectedIncomingCounter += 1`.
  - If counter does not match or authentication tag verification fails: **silently drop the connection immediately**.

---

## 4. Plaintext Message Format

Decrypted plaintext consists of:
```
Byte 0       : Message Type (uint8)
Bytes 1..N   : Payload
```

### Message Types

| ID | Name | Payload Description |
|---|---|---|
| `0x01` | `PING` | UTF-8 JSON: `{"t": <timestamp_ms>}` |
| `0x02` | `PONG` | UTF-8 JSON: `{"t": <timestamp_ms>}` |
| `0x03` | `CLIPBOARD_TEXT` | UTF-8 JSON |
| `0x04` | `CLIPBOARD_IMAGE_START` | UTF-8 JSON |
| `0x05` | `CLIPBOARD_IMAGE_CHUNK` | Binary Chunk Header + Raw Bytes |
| `0x06` | `CLIPBOARD_IMAGE_ACK` | UTF-8 JSON |
| `0x07` | `DISCONNECT` | UTF-8 JSON: `{"reason": "<reason>"}` |
| `0x08` | `FILE_START` | UTF-8 JSON: file metadata |
| `0x09` | `FILE_CHUNK` | Binary Chunk Header + Raw Bytes |
| `0x0A` | `FILE_ACK` | UTF-8 JSON: receipt confirmation |

---

## 5. Message Payloads

### 5.1 `0x01` PING & `0x02` PONG (Heartbeat)
```json
{
  "t": 1729432000000
}
```

### 5.2 `0x03` CLIPBOARD_TEXT
```json
{
  "id": "e7b0a3c2-4819-4b62-9721-a3f7215c0a01",
  "origin": "mac",
  "hash": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e",
  "text": "Hello from Mac clipboard!"
}
```
- `id`: Unique UUIDv4 string.
- `origin`: `"mac"` or `"android"`.
- `hash`: SHA-256 hex string of the UTF-8 text. Used for echo / loop prevention.

### 5.3 `0x04` CLIPBOARD_IMAGE_START
```json
{
  "id": "f8c1b4d3-5920-4c73-a832-b4f8326d1b02",
  "origin": "mac",
  "hash": "b4c235...<sha256 hex of complete image bytes>...",
  "mime": "image/png",
  "size": 184320,
  "totalChunks": 3
}
```
- `size`: Total uncompressed image bytes.
- `totalChunks`: Number of 64 KB chunks (`ceil(size / 65536)`).
- `mime`: `"image/png"` or `"image/jpeg"`.

### 5.4 `0x05` CLIPBOARD_IMAGE_CHUNK (Binary)
Layout of payload (following `0x05` byte):
```
Bytes 0..35  : Transfer UUID string (36 bytes UTF-8 ASCII, e.g. "f8c1b4d3-5920-4c73-a832-b4f8326d1b02")
Bytes 36..39 : Chunk Index (4 bytes, Big-Endian uint32, 0-indexed)
Bytes 40..43 : Chunk Byte Length (4 bytes, Big-Endian uint32, <= 65536)
Bytes 44..N  : Raw Image Chunk Bytes
```

### 5.5 `0x06` CLIPBOARD_IMAGE_ACK
```json
{
  "id": "f8c1b4d3-5920-4c73-a832-b4f8326d1b02",
  "status": "ok"
}
```
Status is `"ok"` if SHA-256 matches and clip was saved/applied, or `"error"` if verification failed.

---

## 6. Clipboard Sync Rules & Image Limits

1. **Max Image Resolution**: 4096 px per side. Downscale proportionally if either width or height exceeds 4096 px.
2. **Format**: PNG preferred. If encoded image size exceeds **10 MB**, re-encode as JPEG (quality 0.85).
3. **Chunking**: Transferred in chunks of up to 64 KB (`65,536` bytes).
4. **Echo / Loop Prevention**:
   - Both sides maintain an LRU set of recent clip hashes (SHA-256) and their origin.
   - When a clip arrives over the network, save its hash to the local seen cache before writing to clipboard.
   - When local clipboard changes, compute hash: if it matches the last received network hash or was originating from self, ignore it.
5. **Cache Lifecycle (Android)**:
   - Images received on Android are stored in `context.cacheDir/bridge_images/`.
   - On startup and after receiving each image, delete cache files older than 24 hours.
   - Expose received images via `FileProvider` (`content://...`) and set URI as `ClipData`.

---

## 7. File Sharing (AirDrop Style)

Bridge provides bidirectional file transfer for any file type (documents, archives, videos, photos, APKs) saved directly to the device's Downloads directory.

### 7.1 `0x08` FILE_START
```json
{
  "id": "c3f8e124-7856-4a1b-9f82-3d8b1e4c90ab",
  "origin": "mac" | "android",
  "name": "Report.pdf",
  "size": 4194304,
  "mime": "application/pdf",
  "totalChunks": 64,
  "hash": "<sha256 hex>"
}
```

### 7.2 `0x09` FILE_CHUNK (Binary)
Layout of payload (following `0x09` byte):
```
Bytes 0..35  : Transfer UUID string (36 bytes UTF-8 ASCII)
Bytes 36..39 : Chunk Index (4 bytes, Big-Endian uint32, 0-indexed)
Bytes 40..43 : Chunk Byte Length (4 bytes, Big-Endian uint32, <= 65536)
Bytes 44..N  : Raw File Chunk Bytes
```

### 7.3 `0x0A` FILE_ACK
```json
{
  "id": "c3f8e124-7856-4a1b-9f82-3d8b1e4c90ab",
  "status": "ok" | "error"
}
```

### 7.4 Storage & Destination Rules
1. **Mac Receiver**:
   - Files are written to the user-configured download directory (default: `~/Downloads`).
   - If a file with the same name exists, automatically append ` (1)`, ` (2)` etc.
   - Verifies SHA-256 upon receiving all chunks.
   - Shows system notification: *"Received <filename> from Android phone"*.
2. **Android Receiver**:
   - Files are saved to the standard public `Download` folder (`Environment.DIRECTORY_DOWNLOADS`).
   - Adds entry to system MediaStore / DownloadManager so it appears immediately in the Files app.
   - Shows a notification with a direct tap-to-open action.
3. **Sharing Triggers**:
   - **From Mac**: Drag & Drop into Bridge window, "Choose File..." button, or right-click Quick Action / Share.
   - **From Android**: System Share Sheet (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`) supporting any MIME type (`*/*`), plus in-app "Send File" button.

