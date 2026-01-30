package com.sparrowwallet.frigate.bitcoind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Parses Bitcoin Core's UTXO snapshot file format (from dumptxoutset).
 *
 * File format (Bitcoin Core 26+):
 * - File header (11 bytes):
 *   - magic: 4 bytes ("utxo")
 *   - marker: 1 byte (0xFF)
 *   - version: 2 bytes (little-endian)
 *   - network_magic: 4 bytes
 * - Metadata:
 *   - base_blockhash: 32 bytes (little-endian)
 *   - coins_count: 8 bytes (uint64, little-endian) - TOTAL number of UTXOs
 * - Coin entries (grouped by txid):
 *   - txid: 32 bytes (little-endian)
 *   - num_outputs: CompactSize - number of UTXOs for this txid
 *   - For each output:
 *     - vout: CompactSize
 *     - code: VarInt (height * 2 + coinbase_flag)
 *     - amount: VarInt (compressed)
 *     - script: VarInt nSize + data (compressed using CScriptCompressor)
 */
public class UtxoSnapshotParser {
    private static final Logger log = LoggerFactory.getLogger(UtxoSnapshotParser.class);
    private static final HexFormat HEX = HexFormat.of();

    // Script type bytes (for nSize < 6)
    private static final int SCRIPT_P2PKH = 0;
    private static final int SCRIPT_P2SH = 1;
    private static final int SCRIPT_P2PK_EVEN = 2;
    private static final int SCRIPT_P2PK_ODD = 3;
    private static final int SCRIPT_P2PK_UNCOMPRESSED_EVEN = 4;
    private static final int SCRIPT_P2PK_UNCOMPRESSED_ODD = 5;

    private final File snapshotFile;
    private String baseBlockHash;
    private long coinCount;

    public UtxoSnapshotParser(File snapshotFile) {
        this.snapshotFile = snapshotFile;
    }

    public String getBaseBlockHash() {
        return baseBlockHash;
    }

    public long getCoinCount() {
        return coinCount;
    }

    /**
     * Parse the snapshot file and call the consumer for each P2TR UTXO.
     * This streams the file so memory usage stays bounded.
     *
     * @param minValue minimum satoshi value to include
     * @param consumer called for each matching P2TR UTXO
     * @return number of P2TR UTXOs found
     */
    public long parseP2TRUtxos(long minValue, Consumer<ParsedUtxo> consumer) throws IOException {
        long p2trCount = 0;
        long processed = 0;
        long lastLogTime = System.currentTimeMillis();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(snapshotFile), 32 * 1024 * 1024))) {
            // Header format (Bitcoin Core 26+):
            // [4 bytes: "utxo" magic] [1 byte: 0xFF] [2 bytes: version] [4 bytes: network magic]
            // [32 bytes: blockhash] [8 bytes: coin_count]

            // Read and validate the 5-byte magic
            byte[] magic = new byte[5];
            dis.readFully(magic);
            if (magic[0] != 0x75 || magic[1] != 0x74 || magic[2] != 0x78 || magic[3] != 0x6f || magic[4] != (byte)0xff) {
                throw new IOException("Invalid UTXO snapshot magic: " + HEX.formatHex(magic));
            }

            // Read version (2 bytes LE)
            int version = readUint16LE(dis);
            if (version != 2) {
                throw new IOException("Unsupported UTXO snapshot version: " + version + " (only version 2 supported)");
            }

            // Read network magic (4 bytes)
            byte[] networkMagic = new byte[4];
            dis.readFully(networkMagic);
            log.debug("Network magic: {}", HEX.formatHex(networkMagic));

            // Read blockhash (32 bytes)
            byte[] hashBytes = new byte[32];
            dis.readFully(hashBytes);
            baseBlockHash = HEX.formatHex(reverseBytes(hashBytes));

            // Read coin count (8 bytes LE) - this is TOTAL number of UTXOs
            coinCount = readUint64LE(dis);

            log.info("Snapshot base block: {}, total UTXOs: {}", baseBlockHash, coinCount);

            // Process coin entries (grouped by txid)
            int coinsPerHashLeft = 0;
            String currentTxid = null;

            while (processed < coinCount) {
                // Read new txid group if needed
                if (coinsPerHashLeft == 0) {
                    byte[] txidBytes = new byte[32];
                    dis.readFully(txidBytes);
                    currentTxid = HEX.formatHex(reverseBytes(txidBytes));
                    coinsPerHashLeft = (int) readCompactSize(dis);
                }

                // Read vout (CompactSize)
                int vout = (int) readCompactSize(dis);

                // Read code (VarInt): height * 2 + coinbase
                long code = readVarInt(dis);
                int height = (int) (code >> 1);
                boolean coinbase = (code & 1) == 1;

                // Read compressed amount (VarInt)
                long compressedAmount = readVarInt(dis);
                long amount = decompressAmount(compressedAmount);

                // Read compressed script
                long nSize = readVarInt(dis);
                byte[] scriptData;
                boolean isP2TR = false;

                if (nSize == 0) {
                    // P2PKH: OP_DUP OP_HASH160 <20 bytes> OP_EQUALVERIFY OP_CHECKSIG
                    scriptData = new byte[20];
                    dis.readFully(scriptData);
                } else if (nSize == 1) {
                    // P2SH: OP_HASH160 <20 bytes> OP_EQUAL
                    scriptData = new byte[20];
                    dis.readFully(scriptData);
                } else if (nSize == 2 || nSize == 3) {
                    // P2PK compressed: <33 bytes pubkey> OP_CHECKSIG
                    scriptData = new byte[32];
                    dis.readFully(scriptData);
                } else if (nSize == 4 || nSize == 5) {
                    // P2PK uncompressed (stored as compressed)
                    scriptData = new byte[32];
                    dis.readFully(scriptData);
                } else {
                    // Uncompressed script (nSize >= 6)
                    int scriptLen = (int) (nSize - 6);
                    if (scriptLen > 10000) {
                        throw new IOException("Invalid script length: " + scriptLen + " at UTXO " + processed + ", nSize=" + nSize);
                    }
                    scriptData = new byte[scriptLen];
                    dis.readFully(scriptData);

                    // Check if this is a P2TR script: OP_1 (0x51) + OP_PUSHBYTES_32 (0x20) + 32 bytes
                    if (scriptLen == 34 && scriptData[0] == 0x51 && scriptData[1] == 0x20) {
                        isP2TR = true;
                        // Extract the 32-byte x-only pubkey
                        byte[] pubkey = new byte[32];
                        System.arraycopy(scriptData, 2, pubkey, 0, 32);
                        scriptData = pubkey;
                    }
                }

                // Filter for P2TR outputs meeting minimum value
                if (isP2TR && amount >= minValue && !coinbase) {
                    consumer.accept(new ParsedUtxo(currentTxid, vout, amount, height, scriptData));
                    p2trCount++;
                }

                processed++;
                coinsPerHashLeft--;

                // Log progress periodically
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 10000) { // Every 10 seconds
                    double pct = (processed * 100.0) / coinCount;
                    log.info("Parsing progress: {}/{} UTXOs ({}%), found {} P2TR UTXOs",
                            processed, coinCount, String.format("%.1f", pct), p2trCount);
                    lastLogTime = now;
                }
            }
        }

        log.info("Parsing complete: processed {} UTXOs, found {} P2TR UTXOs above {} sats",
                processed, p2trCount, minValue);
        return p2trCount;
    }

    /**
     * Read a Bitcoin Core VARINT from the stream.
     * This is NOT the same as CompactSize! It uses MSB continuation bit encoding.
     * Based on Bitcoin Core's ReadVarInt in serialize.h
     */
    private static long readVarInt(DataInputStream dis) throws IOException {
        long n = 0;
        while (true) {
            int b = dis.readUnsignedByte();
            n = (n << 7) | (b & 0x7F);
            if ((b & 0x80) != 0) {
                n++;
            } else {
                return n;
            }
        }
    }

    /**
     * Read a Bitcoin CompactSize from the stream.
     * Format:
     * - 0x00-0xFC: value as-is (1 byte)
     * - 0xFD: next 2 bytes are value (little-endian)
     * - 0xFE: next 4 bytes are value (little-endian)
     * - 0xFF: next 8 bytes are value (little-endian)
     */
    private static long readCompactSize(DataInputStream dis) throws IOException {
        int first = dis.readUnsignedByte();
        if (first < 0xFD) {
            return first;
        } else if (first == 0xFD) {
            return readUint16LE(dis);
        } else if (first == 0xFE) {
            return readUint32LE(dis);
        } else {
            return readUint64LE(dis);
        }
    }

    /**
     * Read uint16 in little-endian format.
     */
    private static int readUint16LE(DataInputStream dis) throws IOException {
        byte[] buf = new byte[2];
        dis.readFully(buf);
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    /**
     * Read uint32 in little-endian format.
     */
    private static long readUint32LE(DataInputStream dis) throws IOException {
        byte[] buf = new byte[4];
        dis.readFully(buf);
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    /**
     * Read uint64 in little-endian format.
     */
    private static long readUint64LE(DataInputStream dis) throws IOException {
        byte[] buf = new byte[8];
        dis.readFully(buf);
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /**
     * Decompress a Bitcoin compressed amount.
     * Based on Bitcoin Core's DecompressAmount function.
     */
    private static long decompressAmount(long x) {
        if (x == 0) {
            return 0;
        }
        x--;
        int e = (int) (x % 10);
        x /= 10;
        long n;
        if (e < 9) {
            int d = (int) (x % 9) + 1;
            x /= 9;
            n = x * 10 + d;
        } else {
            n = x + 1;
        }
        while (e > 0) {
            n *= 10;
            e--;
        }
        return n;
    }

    /**
     * Reverse byte array (for converting between little-endian and big-endian).
     */
    private static byte[] reverseBytes(byte[] bytes) {
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = bytes[bytes.length - 1 - i];
        }
        return result;
    }

    /**
     * Parsed UTXO data from the snapshot.
     */
    public record ParsedUtxo(String txid, int vout, long amount, int height, byte[] scriptData) {}
}
