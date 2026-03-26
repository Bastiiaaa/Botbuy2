package com.botbuy.gearth;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for G-Earth extensions.
 *
 * Implements the G-Earth extension socket protocol directly (no G-Earth JAR
 * required) so the compiled extension JAR is fully self-contained.
 *
 * <h3>Protocol summary (G-Earth ↔ Extension)</h3>
 * <p>Every message on the wire is a Habbo-style packet:
 * {@code [4-byte BE int: body_length][2-byte BE short: header_id][body_bytes...]}</p>
 *
 * <table border="1">
 *   <tr><th>Direction</th><th>ID</th><th>Name</th><th>Notes</th></tr>
 *   <tr><td>G-Earth → Ext</td><td>1</td><td>ON_DOUBLECLICK</td><td>User clicked in G-Earth</td></tr>
 *   <tr><td>G-Earth → Ext</td><td>2</td><td>INFO_REQUEST</td><td>Respond with EXTENSION_INFO</td></tr>
 *   <tr><td>G-Earth → Ext</td><td>3</td><td>PACKET_INTERCEPT</td><td>Habbo packet; respond with MANIPULATED_PACKET</td></tr>
 *   <tr><td>G-Earth → Ext</td><td>4</td><td>FLAGS_CHECK</td><td>G-Earth boot flags</td></tr>
 *   <tr><td>G-Earth → Ext</td><td>5</td><td>CONNECTION_START</td><td>Habbo session opened</td></tr>
 *   <tr><td>G-Earth → Ext</td><td>6</td><td>CONNECTION_END</td><td>Habbo session closed</td></tr>
 *   <tr><td>G-Earth → Ext</td><td>7</td><td>INIT</td><td>G-Earth is ready</td></tr>
 *   <tr><td>Ext → G-Earth</td><td>1</td><td>EXTENSION_INFO</td><td>Response to INFO_REQUEST</td></tr>
 *   <tr><td>Ext → G-Earth</td><td>2</td><td>MANIPULATED_PACKET</td><td>Response to PACKET_INTERCEPT</td></tr>
 *   <tr><td>Ext → G-Earth</td><td>4</td><td>SEND_MESSAGE</td><td>Inject a packet to Habbo</td></tr>
 * </table>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * public class MyExtension extends GEarthExtension {
 *     public MyExtension(String[] args) { super(args); }
 *
 *     protected String getTitle()       { return "My Extension"; }
 *     protected String getDescription() { return "Does stuff";   }
 *     protected String getVersion()     { return "1.0";          }
 *     protected String getAuthor()      { return "Me";           }
 *
 *     protected void initExtension() {
 *         interceptByNameOrHash(GHMessage.Direction.TOCLIENT, "SomePacket", msg -> {
 *             // process msg
 *         });
 *     }
 *
 *     public static void main(String[] args) {
 *         new MyExtension(args).run();
 *     }
 * }
 * }</pre>
 *
 * Run the extension JAR with {@code java -jar MyExtension.jar -p <port>}
 * where {@code <port>} is shown in G-Earth's extension page.
 */
public abstract class GEarthExtension {

    protected static final Logger LOGGER = Logger.getLogger(GEarthExtension.class.getName());

    // -----------------------------------------------------------------------
    // G-Earth protocol constants
    // -----------------------------------------------------------------------

    // Messages G-Earth sends to the extension
    private static final int IN_ONDOUBLECLICK   = 1;
    private static final int IN_INFO_REQUEST    = 2;
    private static final int IN_PACKET_INTERCEPT = 3;
    private static final int IN_FLAGS_CHECK     = 4;
    private static final int IN_CONNECTION_START = 5;
    private static final int IN_CONNECTION_END  = 6;
    private static final int IN_INIT            = 7;
    private static final int IN_UPDATE_HOST_INFO = 10;

    // Messages the extension sends to G-Earth
    private static final int OUT_EXTENSION_INFO     = 1;
    private static final int OUT_MANIPULATED_PACKET = 2;
    private static final int OUT_REQUEST_FLAGS      = 3;
    private static final int OUT_SEND_MESSAGE       = 4;

    private static final int DEFAULT_PORT = 9092;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final int port;
    private OutputStream outputStream;

    /** Packet intercept listeners keyed by name or hash. */
    private final Map<String, List<MessageListener>> incomingListeners  = new ConcurrentHashMap<>();
    private final Map<String, List<MessageListener>> outgoingListeners  = new ConcurrentHashMap<>();

    /** Maps packet header IDs ↔ names for the active Habbo client build. */
    private volatile GPacketInfoManager packetInfoManager = new GPacketInfoManager();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Callback interface for intercepted Habbo packets. */
    public interface MessageListener {
        void onMessage(GHMessage message);
    }

    protected GEarthExtension(String[] args) {
        this.port = parsePort(args);
    }

    // -----------------------------------------------------------------------
    // Abstract methods – implement in subclass
    // -----------------------------------------------------------------------

    protected abstract String getTitle();
    protected abstract String getDescription();
    protected abstract String getVersion();
    protected abstract String getAuthor();

    /** Called once G-Earth signals it is ready (INIT message, non-delayed). */
    protected void initExtension() {}

    /** Called when a Habbo session is established (CONNECTION_START). */
    protected void onStartConnection() {}

    /** Called when the Habbo session ends (CONNECTION_END). */
    protected void onEndConnection() {}

    /** Called when the user double-clicks the extension in G-Earth. */
    protected void onClick() {}

    // -----------------------------------------------------------------------
    // Intercept registration
    // -----------------------------------------------------------------------

    /**
     * Register a listener for intercepted Habbo packets matching the given
     * packet name or hash (as resolved by G-Earth's PacketInfoManager).
     *
     * @param direction  {@code TOCLIENT} for server→client packets,
     *                   {@code TOSERVER} for client→server packets
     * @param nameOrHash packet name (e.g. "MarketPlaceOffers") or hash
     * @param listener   callback invoked for every matching packet
     */
    public void interceptByNameOrHash(GHMessage.Direction direction,
                                      String nameOrHash,
                                      MessageListener listener) {
        Map<String, List<MessageListener>> map =
                direction == GHMessage.Direction.TOCLIENT ? incomingListeners : outgoingListeners;
        map.computeIfAbsent(nameOrHash, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    // -----------------------------------------------------------------------
    // Packet injection
    // -----------------------------------------------------------------------

    /**
     * Send a packet to the Habbo server.
     * If the packet was constructed with a name, its header ID will be
     * resolved automatically from the PacketInfoManager.
     */
    public boolean sendToServer(GHPacket packet) {
        return send(packet, GHMessage.Direction.TOSERVER);
    }

    /**
     * Send a packet to the Habbo client.
     * If the packet was constructed with a name, its header ID will be
     * resolved automatically from the PacketInfoManager.
     */
    public boolean sendToClient(GHPacket packet) {
        return send(packet, GHMessage.Direction.TOCLIENT);
    }

    /**
     * Convenience factory: create an outgoing (TOSERVER) named packet.
     * The numeric header ID is resolved from the PacketInfoManager when the
     * packet is sent.
     */
    public GHPacket createOutgoingPacket(String name) {
        return new GHPacket(name, GHMessage.Direction.TOSERVER);
    }

    // -----------------------------------------------------------------------
    // Main connection loop
    // -----------------------------------------------------------------------

    /**
     * Connect to G-Earth and run the event loop (blocking).
     * Call this from {@code main}.
     */
    public void run() {
        LOGGER.info("[GEarth] Connecting to G-Earth on port " + port + " …");
        try (Socket socket = new Socket("localhost", port)) {
            outputStream = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            LOGGER.info("[GEarth] Connected.");

            while (!socket.isClosed()) {
                // Read the 4-byte length prefix
                int bodyLen;
                try {
                    bodyLen = in.readInt();
                } catch (EOFException e) {
                    // G-Earth closed the connection
                    break;
                }

                // Read bodyLen bytes (header ID + body)
                byte[] buf = new byte[bodyLen + 4];
                // Pre-fill the length field so we can construct an HPacket-compatible buffer
                ByteBuffer.wrap(buf).putInt(bodyLen);
                int remaining = bodyLen;
                int offset = 4;
                while (remaining > 0) {
                    int n = in.read(buf, offset, remaining);
                    if (n < 0) {
                        remaining = 0; // EOF
                        break;
                    }
                    offset += n;
                    remaining -= n;
                }

                GHPacket packet = new GHPacket(buf);
                try {
                    handleIncoming(packet);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[GEarth] Error handling packet id="
                            + packet.headerId(), e);
                }
            }

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[GEarth] Connection error", e);
        }

        LOGGER.info("[GEarth] Disconnected from G-Earth.");
    }

    // -----------------------------------------------------------------------
    // Incoming message dispatch
    // -----------------------------------------------------------------------

    private void handleIncoming(GHPacket p) throws IOException {
        int id = p.headerId();

        if (id == IN_INFO_REQUEST) {
            onInfoRequest();

        } else if (id == IN_INIT) {
            boolean delayedInit = p.readBoolean();
            // Skip HostInfo (string, string, int, (string, string)*) – not used by the bot
            skipHostInfo(p);
            if (!delayedInit) {
                initExtension();
            }

        } else if (id == IN_CONNECTION_START) {
            String host        = p.readString();
            int    connPort    = p.readInt();
            String version     = p.readString();
            String identifier  = p.readString();
            String clientType  = p.readString();
            packetInfoManager  = GPacketInfoManager.readFromPacket(p);
            LOGGER.info("[GEarth] Habbo session started: " + host
                    + ":" + connPort + " [" + version + "] type=" + clientType);
            onStartConnection();

        } else if (id == IN_CONNECTION_END) {
            LOGGER.info("[GEarth] Habbo session ended.");
            onEndConnection();

        } else if (id == IN_PACKET_INTERCEPT) {
            handlePacketIntercept(p);

        } else if (id == IN_ONDOUBLECLICK) {
            onClick();
        }
        // IN_FLAGS_CHECK and IN_UPDATE_HOST_INFO are silently ignored
    }

    // -----------------------------------------------------------------------
    // INFO_REQUEST handler – respond with extension metadata
    // -----------------------------------------------------------------------

    private void onInfoRequest() throws IOException {
        GHPacket resp = new GHPacket(OUT_EXTENSION_INFO);
        resp.appendString(getTitle());
        resp.appendString(getAuthor());
        resp.appendString(getVersion());
        resp.appendString(getDescription());
        resp.appendBoolean(false);   // fireEventButtonVisible
        resp.appendBoolean(false);   // isInstalledExtension
        resp.appendString("");       // fileName
        resp.appendString("");       // cookie / auth token
        resp.appendBoolean(true);    // canLeave
        resp.appendBoolean(true);    // canDelete
        writeToStream(resp.toBytes());
        LOGGER.info("[GEarth] Sent extension info: \"" + getTitle() + "\"");
    }

    // -----------------------------------------------------------------------
    // PACKET_INTERCEPT handler
    // -----------------------------------------------------------------------

    private void handlePacketIntercept(GHPacket p) throws IOException {
        String msgStr = p.readLongString();
        GHMessage message = GHMessage.fromString(msgStr);

        // Find matching listeners by resolving the header ID to packet names
        int headerId = message.getPacket().headerId();
        List<String> names = packetInfoManager.getNamesForHeaderId(
                message.getDirection(), headerId);

        Map<String, List<MessageListener>> listeners =
                message.getDirection() == GHMessage.Direction.TOCLIENT
                        ? incomingListeners
                        : outgoingListeners;

        for (String name : names) {
            List<MessageListener> namedListeners = listeners.get(name);
            if (namedListeners != null && !namedListeners.isEmpty()) {
                message.getPacket().resetReadIndex();
                for (MessageListener listener : namedListeners) {
                    try {
                        listener.onMessage(message);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "[GEarth] Listener error for packet: " + name, e);
                    }
                }
            }
        }

        // Always respond with MANIPULATED_PACKET (pass-through if unmodified)
        GHPacket response = new GHPacket(OUT_MANIPULATED_PACKET);
        response.appendLongString(message.stringify());
        writeToStream(response.toBytes());
    }

    // -----------------------------------------------------------------------
    // Send packet to Habbo (via G-Earth)
    // -----------------------------------------------------------------------

    private boolean send(GHPacket packet, GHMessage.Direction direction) {
        // Resolve named packet → header ID
        if (packet.headerId() == -1 && packet.getIdentifier() != null) {
            int headerId = packetInfoManager.getHeaderId(direction, packet.getIdentifier());
            if (headerId == -1) {
                LOGGER.warning("[GEarth] Could not resolve header ID for packet: \""
                        + packet.getIdentifier() + "\" (direction=" + direction
                        + "). Is G-Earth connected to Habbo?");
                return false;
            }
            packet.setHeaderId(headerId);
        }

        try {
            byte[] packetBytes = packet.toBytes();
            GHPacket sendMsg = new GHPacket(OUT_SEND_MESSAGE);
            sendMsg.appendByte(direction == GHMessage.Direction.TOCLIENT ? (byte) 0 : (byte) 1);
            sendMsg.appendInt(packetBytes.length);
            sendMsg.appendBytes(packetBytes);
            writeToStream(sendMsg.toBytes());
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[GEarth] Failed to send packet", e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // I/O helpers
    // -----------------------------------------------------------------------

    private synchronized void writeToStream(byte[] bytes) throws IOException {
        outputStream.write(bytes);
        outputStream.flush();
    }

    /**
     * Skip the HostInfo block that G-Earth appends after the delayed_init bool
     * in the INIT message.  Format: string, string, int, (string, string)*.
     */
    private static void skipHostInfo(GHPacket p) {
        try {
            p.readString();                          // packetlogger name
            p.readString();                          // version
            int count = p.readInt();                 // attribute count
            for (int i = 0; i < count; i++) {
                p.readString();                      // key
                p.readString();                      // value
            }
        } catch (Exception ignored) {
            // If HostInfo is missing or has a different format, just skip it
        }
    }

    // -----------------------------------------------------------------------
    // Argument parsing
    // -----------------------------------------------------------------------

    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("-p".equals(args[i]) || "--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {}
            }
        }
        LOGGER.warning("[GEarth] No -p <port> argument found; using default " + DEFAULT_PORT);
        return DEFAULT_PORT;
    }
}
