package com.botbuy.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalDouble;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for the HabboAPI (https://habboapi.site/).
 *
 * Uses the /api/market/history endpoint to retrieve current marketplace price data
 * for a specific furni item, allowing the bot to decide whether to search and buy.
 *
 * API response structure:
 * [
 *   {
 *     "ClassName": "...",
 *     "FurniName": "...",
 *     "marketData": {
 *       "history": [[avgPrice, soldItems, creditSum, openOffers, timestamp], ...],
 *       "averagePrice": 5,
 *       "lastUpdated": "2025-05-26 at 15:02"
 *     },
 *     "hotel_domain": "es"
 *   }
 * ]
 */
public class HabboApiClient {

    private static final Logger LOGGER = Logger.getLogger(HabboApiClient.class.getName());

    private static final String BASE_URL = "https://habboapi.site";
    private static final String MARKET_HISTORY_PATH = "/api/market/history";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;

    private final HttpClient httpClient;
    private final String hotel;

    public HabboApiClient(String hotel) {
        this.hotel = hotel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Queries the HabboAPI for the average marketplace price of a furni by name.
     *
     * @param furniName the display name of the furni (e.g. "bufanda de serpiente")
     * @return the average marketplace price in credits, or -1 if not found / API error
     */
    public int getAverageMarketplacePrice(String furniName) {
        try {
            String encodedName = URLEncoder.encode(furniName, StandardCharsets.UTF_8);
            String url = BASE_URL + MARKET_HISTORY_PATH
                    + "?name=" + encodedName
                    + "&hotel=" + hotel
                    + "&days=7";

            LOGGER.info("[HabboAPI] Querying: " + url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BotBuy/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warning("[HabboAPI] HTTP " + response.statusCode()
                        + " for furni: " + furniName);
                return -1;
            }

            return parseAveragePrice(response.body(), furniName);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "[HabboAPI] Request interrupted", e);
            return -1;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[HabboAPI] Error querying price for: " + furniName, e);
            return -1;
        }
    }

    /**
     * Parses the JSON response from /api/market/history and returns the average price.
     *
     * @param jsonBody  raw JSON string from the API
     * @param furniName used for logging
     * @return average price in credits, or -1 if unable to parse
     */
    private int parseAveragePrice(String jsonBody, String furniName) {
        try {
            JSONArray items = new JSONArray(jsonBody);
            if (items.isEmpty()) {
                LOGGER.warning("[HabboAPI] No results found for: " + furniName);
                return -1;
            }

            JSONObject item = items.getJSONObject(0);
            LOGGER.info("[HabboAPI] Found furni: " + item.optString("FurniName", "?")
                    + " (class: " + item.optString("ClassName", "?") + ")");

            JSONObject marketData = item.optJSONObject("marketData");
            if (marketData == null) {
                LOGGER.warning("[HabboAPI] No marketData for: " + furniName);
                return -1;
            }

            int averagePrice = marketData.optInt("averagePrice", -1);
            if (averagePrice >= 0) {
                LOGGER.info("[HabboAPI] averagePrice=" + averagePrice
                        + " credits for: " + furniName);
                return averagePrice;
            }

            // Fallback: use the most recent data point from history
            JSONArray history = marketData.optJSONArray("history");
            if (history != null && !history.isEmpty()) {
                // history entries: [avgPrice, soldItems, creditSum, openOffers, timestamp]
                JSONArray latestEntry = history.getJSONArray(history.length() - 1);
                int recentAvgPrice = latestEntry.optInt(0, -1);
                LOGGER.info("[HabboAPI] Latest history price=" + recentAvgPrice
                        + " credits for: " + furniName);
                return recentAvgPrice;
            }

            LOGGER.warning("[HabboAPI] Unable to determine price for: " + furniName);
            return -1;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[HabboAPI] JSON parse error: " + e.getMessage(), e);
            return -1;
        }
    }
}
