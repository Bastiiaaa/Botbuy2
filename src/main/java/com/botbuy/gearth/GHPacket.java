package com.botbuy.gearth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Simplified Habbo packet implementation for the G-Earth extension protocol.
 *
 * Internal byte layout (mirrors G-Earth's HPacket):
 *   bytes 0-3  – big-endian int32  : (packetBytes.length - 4), i.e. header + body length
 *   bytes 4-5  – big-endian int16  : header ID
 *   bytes 6+   – body data
 *
 * Wire format (what is sent/received on the TCP socket) is identical to the
 * internal layout: G-Earth reads 4 bytes as the body+header length, then that
 * many bytes as the actual data (header 2 bytes + body).
 */
public class GHPacket {

    private byte[] data;
    private int readIndex = 6;

    /** Identifier for named packets (header ID = -1 until resolved). */
    private String identifier = null;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Wrap existing raw bytes (must include the 4-byte length prefix). */
    public GHPacket(byte[] bytes) {
        this.data = bytes.clone();
    }

    /** Build a new empty packet with the given numeric header ID. */
    public GHPacket(int headerId) {
        this.data = new byte[6];
        fixLength();
        setHeaderId(headerId);
    }

    /**
     * Build a new named packet.
     * The header ID starts as -1 and must be resolved before sending.
     */
    public GHPacket(String identifier, GHMessage.Direction direction) {
        this.data = new byte[6];
        fixLength();
        setHeaderId(-1);
        this.identifier = identifier;
    }

    // -----------------------------------------------------------------------
    // Header / length helpers
    // -----------------------------------------------------------------------

    public int headerId() {
        // Reads bytes 4-5 as a signed short, widened to int (matches HPacket behaviour)
        return ByteBuffer.wrap(new byte[]{data[4], data[5]}).getShort();
    }

    public void setHeaderId(int id) {
        data[4] = (byte) ((id >> 8) & 0xFF);
        data[5] = (byte) (id & 0xFF);
    }

    private void fixLength() {
        int len = data.length - 4;
        data[0] = (byte) ((len >> 24) & 0xFF);
        data[1] = (byte) ((len >> 16) & 0xFF);
        data[2] = (byte) ((len >> 8) & 0xFF);
        data[3] = (byte) (len & 0xFF);
    }

    public String getIdentifier() {
        return identifier;
    }

    /** Return a copy of the internal byte array (suitable for writing to a socket). */
    public byte[] toBytes() {
        return data.clone();
    }

    /** Total byte length including the 4-byte length prefix. */
    public int bytesLength() {
        return data.length;
    }

    public int getReadIndex() {
        return readIndex;
    }

    public void resetReadIndex() {
        readIndex = 6;
    }

    // -----------------------------------------------------------------------
    // Read methods (sequential, advances readIndex)
    // -----------------------------------------------------------------------

    public byte readByte() {
        return data[readIndex++];
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    /** Read 4-byte big-endian signed int. */
    public int readInt() {
        int val = ((data[readIndex] & 0xFF) << 24)
                | ((data[readIndex + 1] & 0xFF) << 16)
                | ((data[readIndex + 2] & 0xFF) << 8)
                | (data[readIndex + 3] & 0xFF);
        readIndex += 4;
        return val;
    }

    /** Read 2-byte big-endian unsigned short (returned as int). */
    public int readUShort() {
        int val = ((data[readIndex] & 0xFF) << 8) | (data[readIndex + 1] & 0xFF);
        readIndex += 2;
        return val;
    }

    /**
     * Read a Habbo short-string: [2-byte unsigned short length][string bytes ISO-8859-1].
     */
    public String readString() {
        int len = readUShort();
        String s = new String(data, readIndex, len, StandardCharsets.ISO_8859_1);
        readIndex += len;
        return s;
    }

    /**
     * Read a Habbo long-string: [4-byte int length][string bytes ISO-8859-1].
     * Used by G-Earth for HMessage serialisation.
     */
    public String readLongString() {
        int len = readInt();
        String s = new String(data, readIndex, len, StandardCharsets.ISO_8859_1);
        readIndex += len;
        return s;
    }

    // -----------------------------------------------------------------------
    // Append methods (build packet body)
    // -----------------------------------------------------------------------

    public GHPacket appendByte(byte b) {
        data = Arrays.copyOf(data, data.length + 1);
        data[data.length - 1] = b;
        fixLength();
        return this;
    }

    public GHPacket appendBoolean(boolean b) {
        return appendByte(b ? (byte) 1 : (byte) 0);
    }

    public GHPacket appendInt(int i) {
        data = Arrays.copyOf(data, data.length + 4);
        data[data.length - 4] = (byte) ((i >> 24) & 0xFF);
        data[data.length - 3] = (byte) ((i >> 16) & 0xFF);
        data[data.length - 2] = (byte) ((i >> 8) & 0xFF);
        data[data.length - 1] = (byte) (i & 0xFF);
        fixLength();
        return this;
    }

    /** Append a 2-byte unsigned short (value 0-65535). */
    public GHPacket appendUShort(int us) {
        data = Arrays.copyOf(data, data.length + 2);
        data[data.length - 2] = (byte) ((us >> 8) & 0xFF);
        data[data.length - 1] = (byte) (us & 0xFF);
        fixLength();
        return this;
    }

    /**
     * Append a Habbo short-string: [2-byte length][ISO-8859-1 bytes].
     */
    public GHPacket appendString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        appendUShort(bytes.length);
        appendBytes(bytes);
        return this;
    }

    /**
     * Append a long-string: [4-byte int length][ISO-8859-1 bytes].
     * Used when sending HMessage.stringify() back to G-Earth.
     */
    public GHPacket appendLongString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        appendInt(bytes.length);
        appendBytes(bytes);
        return this;
    }

    public GHPacket appendBytes(byte[] bytes) {
        int old = data.length;
        data = Arrays.copyOf(data, old + bytes.length);
        System.arraycopy(bytes, 0, data, old, bytes.length);
        fixLength();
        return this;
    }

    // -----------------------------------------------------------------------
    // G-Earth stringify protocol
    // (used by HMessage.stringify() for PACKETINTERCEPT / MANIPULATEDPACKET)
    // -----------------------------------------------------------------------

    /**
     * Encodes this packet for G-Earth's PACKETINTERCEPT/MANIPULATEDPACKET protocol.
     * Format: {@code (isEdited ? "1" : "0") + new String(data, ISO-8859-1)}
     * The "not edited" flag ("0") is always used in responses passed through unchanged.
     */
    public String stringify() {
        return "0" + new String(data, StandardCharsets.ISO_8859_1);
    }

    /** Reverse of {@link #stringify()}. */
    public static GHPacket fromStringify(String s) {
        // First character is the edited flag; the rest is the ISO-8859-1 byte string.
        byte[] bytes = s.substring(1).getBytes(StandardCharsets.ISO_8859_1);
        return new GHPacket(bytes);
    }
}
