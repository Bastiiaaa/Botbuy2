import { EventEmitter } from 'events';
import { config } from '../config';
import { GearthConnection } from '../gearth/connection';
import { PacketBuilder, HabboPacket } from '../gearth/protocol';

export interface MarketplaceOffer {
  offerId: number;
  itemId: number;
  price: number;
  count: number;
}

/**
 * Sends a GetMarketplaceOffers packet to G.Earth and resolves with the
 * first batch of offers returned by the server.
 *
 * Outgoing packet (header 1268):
 *   {out:GetMarketplaceOffers}{i:-1}{i:-1}{s:"<query>"}{i:1}
 *
 * Incoming response (header 1058 — MarketPlaceOffers):
 *   {i: offerCount}
 *   For each offer:
 *     {i: offerId}{i: ?}{i: ?}{i: itemId}{i: ?}{i: ?}{s: ""}{i: ?}{i: ?}{i: price}{i: ?}{i: count}{i: ?}
 */
export class MarketplaceSearcher extends EventEmitter {
  constructor(private readonly gearth: GearthConnection) {
    super();
  }

  /**
   * Sends a marketplace search request to Habbo and waits for the response.
   * @param query Item name to search for
   * @param timeoutMs Max milliseconds to wait for server response
   */
  search(query: string, timeoutMs = 10000): Promise<MarketplaceOffer[]> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.gearth.removeListener('packet', handler);
        reject(new Error(`Tiempo de espera agotado buscando "${query}"`));
      }, timeoutMs);

      const handler = (packet: HabboPacket, direction: 'in' | 'out') => {
        if (direction !== 'in') return;
        if (packet.headerId !== config.packetHeaders.marketPlaceOffers) return;

        clearTimeout(timer);
        this.gearth.removeListener('packet', handler);

        try {
          const offers = parseMarketplaceOffers(packet);
          resolve(offers);
        } catch (err) {
          reject(err);
        }
      };

      this.gearth.on('packet', handler);

      console.log(`[Búsqueda] Buscando "${query}" en el mercadillo...`);
      const searchPacket = new PacketBuilder(config.packetHeaders.getMarketplaceOffers)
        .addInt(-1)
        .addInt(-1)
        .addString(query)
        .addInt(1)
        .build();

      this.gearth.sendToServer(searchPacket);
    });
  }
}

/**
 * Parses a MarketPlaceOffers packet body.
 *
 * Packet structure (from packet capture analysis):
 *   i:offerCount
 *   Per offer:
 *     i:offerId  i:unknown  i:unknown  i:itemId  i:unknown  i:unknown
 *     s:unknown  i:unknown  i:unknown  i:price   i:unknown  i:count   i:unknown
 */
function parseMarketplaceOffers(packet: HabboPacket): MarketplaceOffer[] {
  const offers: MarketplaceOffer[] = [];

  try {
    const offerCount = packet.readInt();
    console.log(`[Búsqueda] ${offerCount} oferta(s) encontrada(s)`);

    for (let i = 0; i < offerCount; i++) {
      const offerId = packet.readInt();
      packet.readInt(); // unknown
      packet.readInt(); // unknown
      const itemId = packet.readInt();
      packet.readInt(); // unknown
      packet.readInt(); // unknown
      packet.readString(); // unknown string
      packet.readInt(); // unknown
      packet.readInt(); // unknown
      const price = packet.readInt();
      packet.readInt(); // unknown
      const count = packet.readInt();
      packet.readInt(); // unknown

      offers.push({ offerId, itemId, price, count });
      console.log(`[Búsqueda]   Oferta #${offerId}: itemId=${itemId}, precio=${price} créditos, cantidad=${count}`);
    }
  } catch (err) {
    console.warn('[Búsqueda] Error parseando ofertas (puede ser estructura parcial):', err);
  }

  return offers;
}
