import axios from 'axios';
import { config } from '../config';

export interface FurniPrice {
  name: string;
  price: number;
  currency: string;
}

/**
 * Fetches the current marketplace price of a furni from habboapi.site.
 * Returns null if the price cannot be determined.
 */
export async function getFurniPrice(furniKey: string): Promise<FurniPrice | null> {
  const url = `${config.habboApiUrl}/api/marketplace/${furniKey}`;
  console.log(`[API] Consultando precio de "${furniKey}" en ${url}`);

  try {
    const response = await axios.get(url, { timeout: 10000 });
    const data = response.data;

    // habboapi.site can return different shapes; handle the most common ones
    const price = extractPrice(data);
    if (price === null) {
      console.warn('[API] No se pudo extraer el precio de la respuesta:', JSON.stringify(data));
      return null;
    }

    const result: FurniPrice = { name: furniKey, price, currency: 'credits' };
    console.log(`[API] Precio obtenido: ${price} créditos`);
    return result;
  } catch (err: unknown) {
    if (axios.isAxiosError(err)) {
      console.error(`[API] Error HTTP ${err.response?.status ?? 'desconocido'}: ${err.message}`);
    } else {
      console.error('[API] Error desconocido:', err);
    }
    return null;
  }
}

/** Tries to extract a numeric price from the API response payload. */
function extractPrice(data: unknown): number | null {
  if (typeof data === 'number') return data;

  if (typeof data === 'object' && data !== null) {
    const obj = data as Record<string, unknown>;

    // Common field names returned by habboapi.site
    for (const key of ['price', 'avg_price', 'average_price', 'value', 'credits', 'sell_price', 'buy_price']) {
      if (typeof obj[key] === 'number') return obj[key] as number;
      if (typeof obj[key] === 'string') {
        const parsed = parseFloat(obj[key] as string);
        if (!isNaN(parsed)) return parsed;
      }
    }

    // Nested under "data" key
    if (typeof obj['data'] === 'object' && obj['data'] !== null) {
      return extractPrice(obj['data']);
    }
  }

  return null;
}
