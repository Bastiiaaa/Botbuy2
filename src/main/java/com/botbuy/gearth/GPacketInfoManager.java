package com.botbuy.gearth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Habbo packet header IDs to human-readable names (and vice-versa).
 *
 * G-Earth sends this information in the CONNECTION-START message so that
 * extensions can intercept packets by name instead of hard-coded header IDs
 * that change with every game client update.
 *
 * The serialised format inside the CONNECTION-START packet is:
 *   int:    count
 *   foreach:
 *     int:    headerId
 *     string: hash    ("NULL" if absent)
 *     string: name    ("NULL" if absent)
 *     string: structure ("NULL" if absent)
 *     bool:   isOutgoing  (true → TOSERVER, false → TOCLIENT)
 *     string: source
 */
public class GPacketInfoManager {

    /** header ID → list of names/hashes, keyed by direction. */
    private final Map<Integer, List<String>> incomingHeaderToNames = new HashMap<>();
    private final Map<Integer, List<String>> outgoingHeaderToNames = new HashMap<>();

    /** name/hash → header ID, keyed by direction. */
    private final Map<String, Integer> incomingNameToHeader = new HashMap<>();
    private final Map<String, Integer> outgoingNameToHeader = new HashMap<>();

    public GPacketInfoManager() {}

    // -----------------------------------------------------------------------
    // Mutation
    // -----------------------------------------------------------------------

    /**
     * Add a packet info entry.
     *
     * @param direction TOCLIENT (incoming) or TOSERVER (outgoing)
     * @param headerId  numeric header ID for the current client build
     * @param hash      packet hash (may be null)
     * @param name      human-readable name (may be null)
     */
    public void addPacketInfo(GHMessage.Direction direction, int headerId, String hash, String name) {
        Map<Integer, List<String>> headerToNames =
                direction == GHMessage.Direction.TOCLIENT ? incomingHeaderToNames : outgoingHeaderToNames;
        Map<String, Integer> nameToHeader =
                direction == GHMessage.Direction.TOCLIENT ? incomingNameToHeader : outgoingNameToHeader;

        headerToNames.computeIfAbsent(headerId, k -> new ArrayList<>());

        if (name != null) {
            headerToNames.get(headerId).add(name);
            nameToHeader.put(name, headerId);
        }
        if (hash != null) {
            headerToNames.get(headerId).add(hash);
            nameToHeader.put(hash, headerId);
        }
    }

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    /**
     * Return all names and hashes known for the given header ID.
     *
     * @param direction packet direction
     * @param headerId  numeric header ID
     * @return list of names/hashes (may be empty)
     */
    public List<String> getNamesForHeaderId(GHMessage.Direction direction, int headerId) {
        Map<Integer, List<String>> headerToNames =
                direction == GHMessage.Direction.TOCLIENT ? incomingHeaderToNames : outgoingHeaderToNames;
        return headerToNames.getOrDefault(headerId, Collections.emptyList());
    }

    /**
     * Return the header ID for the given name or hash, or -1 if not found.
     *
     * @param direction packet direction
     * @param name      human-readable name or hash
     * @return header ID, or -1
     */
    public int getHeaderId(GHMessage.Direction direction, String name) {
        Map<String, Integer> nameToHeader =
                direction == GHMessage.Direction.TOCLIENT ? incomingNameToHeader : outgoingNameToHeader;
        return nameToHeader.getOrDefault(name, -1);
    }

    // -----------------------------------------------------------------------
    // Deserialisation from CONNECTION-START packet
    // -----------------------------------------------------------------------

    /**
     * Read a {@code GPacketInfoManager} from the current read position of a
     * CONNECTION-START {@link GHPacket}.
     */
    public static GPacketInfoManager readFromPacket(GHPacket packet) {
        GPacketInfoManager manager = new GPacketInfoManager();
        int count = packet.readInt();

        for (int i = 0; i < count; i++) {
            int headerId  = packet.readInt();
            String hash   = packet.readString();
            String name   = packet.readString();
            packet.readString();                // structure (unused)
            boolean isOutgoing = packet.readBoolean();
            packet.readString();                // source (unused)

            GHMessage.Direction dir = isOutgoing ? GHMessage.Direction.TOSERVER
                                                 : GHMessage.Direction.TOCLIENT;
            manager.addPacketInfo(
                    dir,
                    headerId,
                    "NULL".equals(hash) ? null : hash,
                    "NULL".equals(name) ? null : name
            );
        }

        return manager;
    }
}
