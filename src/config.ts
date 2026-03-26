// Configuration for the Habbo Marketplace Bot

export const config = {
  // G.Earth extension connection
  gearthHost: 'localhost',
  gearthPort: 9092,

  // Extension metadata
  extensionName: 'BotBuy2',
  extensionDescription: 'Auto-buys furni from marketplace when price is within limit',
  extensionVersion: '1.0.0',
  extensionAuthor: 'BotBuy2',

  // Habbo API
  habboApiUrl: 'https://habboapi.site',
  // API key for the furni to look up (snake_scarf = bufanda de serpiente)
  furniApiKey: 'snake_scarf',

  // Marketplace search query
  furniSearchName: 'bufanda de serpiente',

  // Maximum price in credits to pay for the furni
  maxPrice: 5,

  // Retry settings
  retryDelay: 5000,   // ms between retries
  maxRetries: 3,

  // Packet header IDs for Habbo protocol
  packetHeaders: {
    // Outgoing (client → server)
    getMarketplaceOffers: 1268,
    buyMarketplaceOffer: 3450,

    // Incoming (server → client)
    marketPlaceOffers: 1058,
    marketplaceBuyOfferResult: 527,
    creditBalance: 6,
  },
};
