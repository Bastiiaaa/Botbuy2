package com.botbuy;

import com.botbuy.api.HabboApiClient;
import com.botbuy.gearth.GEarthExtension;
import com.botbuy.gearth.GHMessage;
import com.botbuy.gearth.GHPacket;
import com.botbuy.marketplace.MarketplaceOffer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BotBuy – G-Earth extension for automated Habbo marketplace purchases.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Every {@code search.interval.seconds} the bot queries
 *       <a href="https://habboapi.site/">habboapi.site</a> for the average
 *       marketplace price of the configured furni.</li>
 *   <li>If the API price is &le; the configured max price, the bot sends a
 *       {@code GetMarketplaceOffers} packet to search the in-game marketplace.</li>
 *   <li>When the server responds with {@code MarketPlaceOffers}, the bot reads
 *       each offer and buys the first one whose price is &le; the max price.</li>
 *   <li>The purchase result is logged via {@code MarketplaceBuyOfferResult}.</li>
 * </ol>
 *
 * <h3>Configuration</h3>
 * <p>Edit {@code src/main/resources/config.properties} before building:</p>
 * <ul>
 *   <li>{@code furni.name}              – display name for the HabboAPI query</li>
 *   <li>{@code furni.search.query}      – query string sent to the marketplace</li>
 *   <li>{@code max.price}               – maximum price in credits (default 5)</li>
 *   <li>{@code api.hotel}               – hotel code for habboapi.site (default es)</li>
 *   <li>{@code search.interval.seconds} – seconds between search cycles (default 10)</li>
 * </ul>
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn clean package
 *   java -jar target/BotBuy-1.0.0.jar -p &lt;G-Earth port&gt;
 * </pre>
 */
public class BotBuyExtension extends GEarthExtension {

    private static final Logger LOGGER = Logger.getLogger(BotBuyExtension.class.getName());

    // -----------------------------------------------------------------------
    // Configuration (loaded from config.properties)
    // -----------------------------------------------------------------------
    private String furniName;
    private String furniSearchQuery;
    private int maxPrice;
    private String apiHotel;
    private int searchIntervalSeconds;

    // -----------------------------------------------------------------------
    // Runtime state
    // -----------------------------------------------------------------------
    private HabboApiClient apiClient;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> searchTask;

    /** Guards against overlapping searches while waiting for MarketPlaceOffers. */
    private final AtomicBoolean awaitingOffers = new AtomicBoolean(false);

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public BotBuyExtension(String[] args) {
        super(args);
    }

    // -----------------------------------------------------------------------
    // Extension metadata
    // -----------------------------------------------------------------------

    @Override protected String getTitle()       { return "BotBuy"; }
    @Override protected String getDescription() {
        return "Compra automática de furni en el mercadillo de Habbo si el precio es ≤ max.price";
    }
    @Override protected String getVersion()     { return "1.0.0"; }
    @Override protected String getAuthor()      { return "BotBuy"; }

    // -----------------------------------------------------------------------
    // G-Earth lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void initExtension() {
        loadConfig();
        apiClient = new HabboApiClient(apiHotel);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "botbuy-scheduler");
            t.setDaemon(true);
            return t;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "botbuy-shutdown"));

        // Register incoming packet listeners
        interceptByNameOrHash(GHMessage.Direction.TOCLIENT,
                "MarketPlaceOffers",         this::handleMarketplaceOffers);
        interceptByNameOrHash(GHMessage.Direction.TOCLIENT,
                "MarketplaceBuyOfferResult", this::handleBuyResult);

        LOGGER.info("[BotBuy] Initialized. Furni: \"" + furniName
                + "\", max price: " + maxPrice + " credits.");
    }

    @Override
    protected void onStartConnection() {
        LOGGER.info("[BotBuy] Habbo session started – beginning search loop.");
        startSearchLoop();
    }

    @Override
    protected void onEndConnection() {
        awaitingOffers.set(false);
        LOGGER.info("[BotBuy] Habbo session ended – pausing search loop.");
        stopSearchLoop();
    }

    /** Shuts down the scheduler. Called automatically via the JVM shutdown hook. */
    private void shutdown() {
        stopSearchLoop();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Search / buy loop
    // -----------------------------------------------------------------------

    private void startSearchLoop() {
        stopSearchLoop();
        searchTask = scheduler.scheduleWithFixedDelay(
                this::runSearchCycle,
                2,
                searchIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void stopSearchLoop() {
        if (searchTask != null && !searchTask.isCancelled()) {
            searchTask.cancel(false);
        }
    }

    /**
     * One search cycle:
     * <ol>
     *   <li>Query habboapi.site for the average price of the target furni.</li>
     *   <li>If the API price is within budget, send a marketplace search packet.</li>
     * </ol>
     */
    private void runSearchCycle() {
        if (awaitingOffers.get()) {
            LOGGER.fine("[BotBuy] Still waiting for previous MarketPlaceOffers – skipping cycle.");
            return;
        }

        try {
            LOGGER.info("[BotBuy] Querying API for price of: \"" + furniName + "\"");
            int apiPrice = apiClient.getAverageMarketplacePrice(furniName);

            if (apiPrice < 0) {
                LOGGER.warning("[BotBuy] API returned no price data – retrying next cycle.");
                return;
            }

            if (apiPrice > maxPrice) {
                LOGGER.info("[BotBuy] API average price " + apiPrice
                        + " > " + maxPrice + " credits – skipping marketplace search.");
                return;
            }

            LOGGER.info("[BotBuy] API price " + apiPrice
                    + " <= " + maxPrice + " credits – searching in-game marketplace…");
            searchMarketplace(furniSearchQuery);
            awaitingOffers.set(true);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[BotBuy] Unexpected error in search cycle", e);
            awaitingOffers.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Outgoing packet helpers
    // -----------------------------------------------------------------------

    /**
     * Send {@code GetMarketplaceOffers} to search the in-game marketplace.
     *
     * <p>Packet structure (outgoing):
     * <pre>
     *   int    minPrice   (-1 = no filter)
     *   int    maxPriceFilter (-1 = no filter)
     *   string query      (furni name to search for)
     *   int    sortType   (1 = lowest price first)
     * </pre>
     */
    private void searchMarketplace(String query) {
        GHPacket packet = createOutgoingPacket("GetMarketplaceOffers");
        packet.appendInt(-1);
        packet.appendInt(-1);
        packet.appendString(query);
        packet.appendInt(1);
        sendToServer(packet);
        LOGGER.info("[BotBuy] Sent GetMarketplaceOffers for: \"" + query + "\"");
    }

    /**
     * Send {@code BuyMarketplaceOffer} to purchase the given offer.
     *
     * <p>Packet structure (outgoing):
     * <pre>
     *   int   offerId
     * </pre>
     */
    private void buyOffer(int offerId) {
        GHPacket packet = createOutgoingPacket("BuyMarketplaceOffer");
        packet.appendInt(offerId);
        sendToServer(packet);
        LOGGER.info("[BotBuy] Sent BuyMarketplaceOffer for offerId=" + offerId);
    }

    // -----------------------------------------------------------------------
    // Incoming packet handlers
    // -----------------------------------------------------------------------

    /**
     * Handles {@code MarketPlaceOffers} – the server's response to our search.
     *
     * <p>Per-offer structure observed in live packet captures (habbo.es):
     * <pre>
     *   int    offerId
     *   int    itemType         (1 = floor furni, 2 = wall furni)
     *   int    unknownFlag
     *   int    spriteId         (furniture type ID)
     *   int    stuffDataType    (0=void, 1=string, 2=map, 7=map variant, …)
     *   [stuffdata content based on type]
     *   string extraDataStr     (extra field observed after stuffdata)
     *   int    unknownFlag2
     *   int    daysLeftForSale
     *   int    priceInCredits   ← used for the price check
     *   int    unknownFlag3
     *   int    totalOffersCount
     *   int    unknownFlag4
     * </pre>
     */
    private void handleMarketplaceOffers(GHMessage message) {
        awaitingOffers.set(false);
        GHPacket packet = message.getPacket();

        try {
            int offerCount = packet.readInt();
            LOGGER.info("[BotBuy] Received " + offerCount + " marketplace offer(s).");

            boolean bought = false;
            for (int i = 0; i < offerCount && !bought; i++) {
                MarketplaceOffer offer = readOffer(packet);
                LOGGER.info("[BotBuy] Offer #" + (i + 1) + ": " + offer);

                if (offer.getPrice() <= maxPrice) {
                    LOGGER.info("[BotBuy] Price " + offer.getPrice()
                            + " <= " + maxPrice + " credits – buying offer " + offer.getOfferId());
                    buyOffer(offer.getOfferId());
                    bought = true;
                }
            }

            if (!bought) {
                LOGGER.info("[BotBuy] No offer within price limit found this cycle.");
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[BotBuy] Error parsing MarketPlaceOffers packet", e);
        }
    }

    /**
     * Handles {@code MarketplaceBuyOfferResult} – the purchase confirmation.
     *
     * <p>Packet structure:
     * <pre>
     *   int result         (1 = success)
     *   int newOfferId
     *   int unknown
     *   int originalOfferId
     * </pre>
     */
    private void handleBuyResult(GHMessage message) {
        try {
            GHPacket packet = message.getPacket();
            int result      = packet.readInt();
            int newOfferId  = packet.readInt();
            int unknown     = packet.readInt();
            int origOfferId = packet.readInt();

            if (result == 1) {
                LOGGER.info("[BotBuy] ✓ Purchase SUCCESSFUL! offerId=" + origOfferId);
            } else {
                LOGGER.warning("[BotBuy] ✗ Purchase FAILED. result=" + result
                        + ", offerId=" + origOfferId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[BotBuy] Error parsing MarketplaceBuyOfferResult", e);
        }
    }

    // -----------------------------------------------------------------------
    // Packet parsing helpers
    // -----------------------------------------------------------------------

    private MarketplaceOffer readOffer(GHPacket packet) {
        int offerId      = packet.readInt();
        int itemType     = packet.readInt();
        int unknownFlag  = packet.readInt();
        int spriteId     = packet.readInt();

        // Read and discard the variable-length stuffdata section
        readStuffData(packet);

        // Extra string observed between stuffdata and the price fields
        packet.readString();

        int unknownFlag2   = packet.readInt();
        int daysLeft       = packet.readInt();
        int priceInCredits = packet.readInt();
        int unknownFlag3   = packet.readInt();
        int totalOffers    = packet.readInt();
        int unknownFlag4   = packet.readInt();

        return new MarketplaceOffer(offerId, itemType, spriteId, priceInCredits, totalOffers, daysLeft);
    }

    /**
     * Read and discard the stuffdata section from the given packet.
     *
     * <p>StuffData types in Habbo's modern protocol:
     * <ul>
     *   <li>0 – void (no data)</li>
     *   <li>1 – single string value</li>
     *   <li>2 – string-string map (int count + pairs)</li>
     *   <li>3 – string array (int count + strings)</li>
     *   <li>6 – int array (int count + ints)</li>
     *   <li>7 – string-string map variant (same encoding as type 2)</li>
     * </ul>
     */
    private void readStuffData(GHPacket packet) {
        int type = packet.readInt();
        switch (type) {
            case 0:
                // void – nothing extra
                break;
            case 1:
                // single string value
                packet.readString();
                break;
            case 2:
            case 7:
                // string-string map
                int mapCount = packet.readInt();
                for (int i = 0; i < mapCount; i++) {
                    packet.readString(); // key
                    packet.readString(); // value
                }
                break;
            case 3:
                // string array
                int arrCount = packet.readInt();
                for (int i = 0; i < arrCount; i++) {
                    packet.readString();
                }
                break;
            case 6:
                // int array
                int intCount = packet.readInt();
                for (int i = 0; i < intCount; i++) {
                    packet.readInt();
                }
                break;
            default:
                // Unknown type – best-effort: try reading one string as fallback
                LOGGER.fine("[BotBuy] Unknown stuffDataType=" + type + "; trying string fallback.");
                try {
                    packet.readString();
                } catch (Exception ignored) {}
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Configuration loading
    // -----------------------------------------------------------------------

    private void loadConfig() {
        Properties props = new Properties();
        try (InputStream is = getClass().getResourceAsStream("/config.properties")) {
            if (is != null) {
                props.load(is);
                LOGGER.info("[BotBuy] Loaded config.properties.");
            } else {
                LOGGER.warning("[BotBuy] config.properties not found – using defaults.");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[BotBuy] Could not read config.properties", e);
        }

        furniName             = props.getProperty("furni.name",             "bufanda de serpiente");
        furniSearchQuery      = props.getProperty("furni.search.query",     "bufanda de serpiente");
        apiHotel              = props.getProperty("api.hotel",              "es");
        maxPrice              = parseIntSafe(props.getProperty("max.price",                "5"), 5);
        searchIntervalSeconds = parseIntSafe(props.getProperty("search.interval.seconds", "10"), 10);
    }

    private static int parseIntSafe(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        new BotBuyExtension(args).run();
    }
}
