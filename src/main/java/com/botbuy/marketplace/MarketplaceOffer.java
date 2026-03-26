package com.botbuy.marketplace;

/**
 * Represents a single offer from the Habbo marketplace.
 *
 * Packet structure for each offer inside MarketPlaceOffers (based on packet capture):
 *   int  offerId          - unique ID for this marketplace offer
 *   int  itemType         - 1 = floor furni, 2 = wall furni
 *   int  unknownFlag      - observed as 1 in captures
 *   int  spriteId         - furniture type / sprite ID
 *   int  stuffDataType    - type of extra stuffdata (0=void, 1=string, 2=map, 7=map variant...)
 *   [stuffdata fields depend on stuffDataType]
 *   int  unknownFlag2     - observed as 1 in captures
 *   int  daysLeftForSale  - days remaining before offer expires
 *   int  priceInCredits   - price of this offer in credits
 *   int  unknownFlag3     - observed as 6 in captures
 *   int  totalOffersCount - total number of offers available for this furni
 *   int  unknownFlag4     - observed as 1 in captures
 */
public class MarketplaceOffer {

    private final int offerId;
    private final int itemType;
    private final int spriteId;
    private final int price;
    private final int totalOffers;
    private final int daysLeft;

    public MarketplaceOffer(int offerId, int itemType, int spriteId,
                            int price, int totalOffers, int daysLeft) {
        this.offerId = offerId;
        this.itemType = itemType;
        this.spriteId = spriteId;
        this.price = price;
        this.totalOffers = totalOffers;
        this.daysLeft = daysLeft;
    }

    public int getOfferId() {
        return offerId;
    }

    public int getItemType() {
        return itemType;
    }

    public int getSpriteId() {
        return spriteId;
    }

    public int getPrice() {
        return price;
    }

    public int getTotalOffers() {
        return totalOffers;
    }

    public int getDaysLeft() {
        return daysLeft;
    }

    @Override
    public String toString() {
        return "MarketplaceOffer{" +
                "offerId=" + offerId +
                ", itemType=" + itemType +
                ", spriteId=" + spriteId +
                ", price=" + price +
                ", totalOffers=" + totalOffers +
                ", daysLeft=" + daysLeft +
                '}';
    }
}
