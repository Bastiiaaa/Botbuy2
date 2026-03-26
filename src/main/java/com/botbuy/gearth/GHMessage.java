package com.botbuy.gearth;

/**
 * Represents a Habbo message (packet + metadata) as used by G-Earth.
 *
 * G-Earth serialises HMessage objects as strings for the PACKETINTERCEPT /
 * MANIPULATEDPACKET extension protocol messages.  The format is:
 *
 *   {@code "<blocked>\t<index>\t<direction>\t<packet.stringify()>"}
 *
 * where:
 *   blocked   – "1" if the packet should be blocked, "0" otherwise
 *   index     – monotonically increasing packet sequence number
 *   direction – "TOCLIENT" or "TOSERVER"
 *   packet.stringify() – the packet's byte content encoded as ISO-8859-1
 */
public class GHMessage {

    public enum Direction {
        TOCLIENT,
        TOSERVER
    }

    private final GHPacket packet;
    private final Direction direction;
    private final int index;
    private boolean blocked;

    public GHMessage(GHPacket packet, Direction direction, int index, boolean blocked) {
        this.packet = packet;
        this.direction = direction;
        this.index = index;
        this.blocked = blocked;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public GHPacket getPacket() {
        return packet;
    }

    public Direction getDirection() {
        return direction;
    }

    public int getIndex() {
        return index;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    // -----------------------------------------------------------------------
    // Serialisation (G-Earth extension protocol)
    // -----------------------------------------------------------------------

    /**
     * Parse a GHMessage from G-Earth's serialised string format.
     * Format: {@code "<blocked>\t<index>\t<direction>\t<packet_stringify>"}
     */
    public static GHMessage fromString(String s) {
        String[] parts = s.split("\t", 4);
        boolean blocked = "1".equals(parts[0]);
        int index = Integer.parseInt(parts[1]);
        Direction dir = "TOCLIENT".equals(parts[2]) ? Direction.TOCLIENT : Direction.TOSERVER;
        GHPacket packet = GHPacket.fromStringify(parts[3]);
        return new GHMessage(packet, dir, index, blocked);
    }

    /**
     * Serialise this message to G-Earth's string format.
     * The extension sends this string back as a "long string" in the
     * MANIPULATEDPACKET response.
     */
    public String stringify() {
        return (blocked ? "1" : "0")
                + "\t" + index
                + "\t" + direction.name()
                + "\t" + packet.stringify();
    }
}
