import { config } from '../config';
import { GearthConnection } from '../gearth/connection';
import { PacketBuilder, HabboPacket } from '../gearth/protocol';
import { MarketplaceOffer } from './searcher';

export interface BuyResult {
  success: boolean;
  offerId: number;
  price: number;
  message: string;
}

/**
 * Sends a BuyMarketplaceOffer packet to G.Earth and waits for the result.
 *
 * Outgoing packet (header 3450):
 *   {out:BuyMarketplaceOffer}{i:offerId}
 *
 * Incoming response (header — MarketplaceBuyOfferResult):
 *   {i:result}{i:?}{i:?}{i:offerId}
 *   result: 1 = success
 */
export class MarketplaceBuyer {
  constructor(private readonly gearth: GearthConnection) {}

  /**
   * Attempts to purchase a marketplace offer.
   * @param offer The offer to purchase
   * @param timeoutMs Max milliseconds to wait for server confirmation
   */
  buy(offer: MarketplaceOffer, timeoutMs = 10000): Promise<BuyResult> {
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.gearth.removeListener('packet', handler);
        console.warn(`[Compra] Tiempo de espera agotado para oferta #${offer.offerId}`);
        resolve({
          success: false,
          offerId: offer.offerId,
          price: offer.price,
          message: 'Tiempo de espera agotado — sin confirmación del servidor',
        });
      }, timeoutMs);

      const handler = (packet: HabboPacket, direction: 'in' | 'out') => {
        if (direction !== 'in') return;
        if (packet.headerId !== config.packetHeaders.marketplaceBuyOfferResult) return;

        clearTimeout(timer);
        this.gearth.removeListener('packet', handler);

        try {
          const result = packet.readInt(); // 1 = success
          packet.readInt(); // unknown
          packet.readInt(); // unknown
          const returnedOfferId = packet.readInt();

          const success = result === 1;
          const message = success
            ? `Compra exitosa: oferta #${returnedOfferId} por ${offer.price} créditos`
            : `Compra fallida: código de error ${result}`;

          console.log(`[Compra] ${message}`);
          resolve({ success, offerId: offer.offerId, price: offer.price, message });
        } catch (parseErr) {
          // Response format may vary — treat as success if we got any response
          console.warn('[Compra] No se pudo parsear la respuesta del servidor:', parseErr);
          const message = `Respuesta de compra recibida para oferta #${offer.offerId}`;
          console.log(`[Compra] ${message}`);
          resolve({ success: true, offerId: offer.offerId, price: offer.price, message });
        }
      };

      this.gearth.on('packet', handler);

      console.log(`[Compra] Comprando oferta #${offer.offerId} por ${offer.price} créditos...`);
      const buyPacket = new PacketBuilder(config.packetHeaders.buyMarketplaceOffer)
        .addInt(offer.offerId)
        .build();

      this.gearth.sendToServer(buyPacket);
    });
  }
}
