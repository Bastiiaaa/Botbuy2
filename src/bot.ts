import { config } from './config';
import { getFurniPrice } from './api/habboApi';
import { GearthConnection } from './gearth/connection';
import { MarketplaceSearcher } from './marketplace/searcher';
import { MarketplaceBuyer } from './marketplace/buyer';

/**
 * Main bot orchestrator.
 *
 * Flow:
 *   1. Connect to G.Earth
 *   2. Query Habbo API for furni price
 *   3. If API price ≤ maxPrice → search marketplace
 *   4. For each offer found, if price ≤ maxPrice → buy it
 *   5. Log result and exit
 */
export class Bot {
  private readonly gearth: GearthConnection;
  private readonly searcher: MarketplaceSearcher;
  private readonly buyer: MarketplaceBuyer;

  constructor() {
    this.gearth = new GearthConnection();
    this.searcher = new MarketplaceSearcher(this.gearth);
    this.buyer = new MarketplaceBuyer(this.gearth);
  }

  async run(): Promise<void> {
    console.log('='.repeat(60));
    console.log('  BotBuy2 — Habbo Marketplace Auto-Buyer');
    console.log('='.repeat(60));
    console.log(`  Furni    : ${config.furniSearchName}`);
    console.log(`  Precio máx: ${config.maxPrice} créditos`);
    console.log('='.repeat(60));

    // Step 1: Connect to G.Earth
    await this.connectWithRetry();

    // Step 2: Wait until the game connection is ready
    await this.waitForGameConnection();

    // Step 3: Query API
    const furniPrice = await this.checkApiPrice();
    if (furniPrice === null) {
      console.error('[Bot] No se pudo obtener el precio de la API. Abortando.');
      this.gearth.disconnect();
      return;
    }

    if (furniPrice > config.maxPrice) {
      console.log(
        `[Bot] Precio en API (${furniPrice} créditos) supera el máximo (${config.maxPrice} créditos). No se comprará.`,
      );
      this.gearth.disconnect();
      return;
    }

    console.log(
      `[Bot] Precio en API (${furniPrice} créditos) ≤ máximo (${config.maxPrice} créditos). Procediendo con búsqueda en mercadillo.`,
    );

    // Step 4: Search marketplace
    let offers;
    try {
      offers = await this.searcher.search(config.furniSearchName);
    } catch (err) {
      console.error('[Bot] Error en búsqueda de mercadillo:', err);
      this.gearth.disconnect();
      return;
    }

    if (offers.length === 0) {
      console.log('[Bot] No se encontraron ofertas en el mercadillo.');
      this.gearth.disconnect();
      return;
    }

    // Step 5: Buy the first affordable offer
    let purchased = false;
    for (const offer of offers) {
      if (offer.price <= config.maxPrice) {
        console.log(
          `[Bot] Oferta encontrada — precio: ${offer.price} créditos (≤ ${config.maxPrice}). Comprando...`,
        );

        const result = await this.buyer.buy(offer);
        if (result.success) {
          console.log(`[Bot] ✔ Compra completada: ${result.message}`);
          purchased = true;
          break;
        } else {
          console.warn(`[Bot] ✘ Fallo al comprar oferta #${offer.offerId}: ${result.message}`);
        }
      } else {
        console.log(
          `[Bot] Oferta #${offer.offerId} (${offer.price} créditos) supera el máximo. Saltando.`,
        );
      }
    }

    if (!purchased) {
      console.log('[Bot] No se realizó ninguna compra (ninguna oferta cumple el criterio de precio).');
    }

    this.gearth.disconnect();
    console.log('[Bot] Proceso finalizado.');
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private async connectWithRetry(): Promise<void> {
    for (let attempt = 1; attempt <= config.maxRetries; attempt++) {
      try {
        console.log(`[Bot] Conectando a G.Earth (intento ${attempt}/${config.maxRetries})...`);
        await this.gearth.connect();
        console.log('[Bot] Conexión con G.Earth establecida.');
        return;
      } catch (err) {
        console.error(`[Bot] No se pudo conectar a G.Earth: ${(err as Error).message}`);
        if (attempt < config.maxRetries) {
          console.log(`[Bot] Reintentando en ${config.retryDelay / 1000}s...`);
          await sleep(config.retryDelay);
        } else {
          throw new Error('No se pudo conectar a G.Earth después de varios intentos.');
        }
      }
    }
  }

  private waitForGameConnection(): Promise<void> {
    return new Promise((resolve) => {
      if (this.gearth.isConnected()) {
        resolve();
        return;
      }
      this.gearth.once('game_connected', () => {
        console.log('[Bot] Conexión con el juego confirmada.');
        resolve();
      });
    });
  }

  private async checkApiPrice(): Promise<number | null> {
    for (let attempt = 1; attempt <= config.maxRetries; attempt++) {
      const result = await getFurniPrice(config.furniApiKey);
      if (result !== null) return result.price;

      if (attempt < config.maxRetries) {
        console.log(`[Bot] Reintentando consulta de API en ${config.retryDelay / 1000}s...`);
        await sleep(config.retryDelay);
      }
    }
    return null;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
