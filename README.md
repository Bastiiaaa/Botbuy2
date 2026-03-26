# BotBuy2 — Habbo Marketplace Auto-Buyer

Bot en TypeScript/Node.js que se conecta a G.Earth y compra automáticamente furnis en el marketplace de Habbo cuando el precio cumple el criterio configurado.

## Requisitos

- [Node.js](https://nodejs.org/) 18+
- [G.Earth](https://github.com/sirjonasxx/G-Earth) ejecutándose y conectado al juego

## Instalación

```bash
npm install
```

## Compilar

```bash
npm run build
```

## Uso

1. Abre G.Earth y conéctate a Habbo Hotel.
2. Asegúrate de que G.Earth esté escuchando en el puerto 9092 (predeterminado).
3. Ejecuta el bot:

```bash
npm start
```

O en modo de desarrollo (sin compilar):

```bash
npm run dev
```

## Flujo de ejecución

1. **Conexión a G.Earth** — El bot se conecta como extensión de G.Earth en `localhost:9092`.
2. **Consulta de API** — Consulta `https://habboapi.site/api/marketplace/snake_scarf` para obtener el precio actual del furni *bufanda de serpiente*.
3. **Validación de precio** — Si el precio de la API es ≤ 5 créditos, continúa.
4. **Búsqueda en mercadillo** — Envía el paquete `GetMarketplaceOffers` con la búsqueda `"bufanda de serpiente"`.
5. **Compra automática** — Si hay una oferta con precio ≤ 5 créditos, envía el paquete `BuyMarketplaceOffer`.
6. **Log** — Registra cada paso y el resultado de la compra.

## Configuración

Edita `src/config.ts` para ajustar:

| Parámetro | Descripción | Valor predeterminado |
|-----------|-------------|----------------------|
| `gearthPort` | Puerto de G.Earth | `9092` |
| `furniApiKey` | Clave del furni en la API | `snake_scarf` |
| `furniSearchName` | Nombre en el mercadillo de Habbo | `bufanda de serpiente` |
| `maxPrice` | Precio máximo en créditos | `5` |
| `maxRetries` | Intentos de reintento | `3` |
| `retryDelay` | Espera entre reintentos (ms) | `5000` |

## Estructura del proyecto

```
src/
├── main.ts                  # Punto de entrada
├── config.ts                # Configuración
├── bot.ts                   # Orquestador principal
├── api/
│   └── habboApi.ts          # Cliente de la API de habboapi.site
├── gearth/
│   ├── connection.ts        # Conexión TCP a G.Earth (protocolo de extensiones)
│   └── protocol.ts          # Construcción y parseo de paquetes Habbo
└── marketplace/
    ├── searcher.ts          # Búsqueda en el mercadillo
    └── buyer.ts             # Lógica de compra
```

## Paquetes Habbo utilizados

| Paquete | Header | Dirección | Descripción |
|---------|--------|-----------|-------------|
| `GetMarketplaceOffers` | 1268 | Saliente | Busca ofertas por nombre |
| `MarketPlaceOffers` | 1058 | Entrante | Lista de ofertas devuelta por el servidor |
| `BuyMarketplaceOffer` | 3450 | Saliente | Compra una oferta por su ID |
| `MarketplaceBuyOfferResult` | 527 | Entrante | Confirmación de compra |

> **Nota:** Los headers de paquetes entrantes pueden variar entre versiones del cliente. Si la búsqueda no responde, ajusta `packetHeaders.marketPlaceOffers` y `packetHeaders.marketplaceBuyOfferResult` en `src/config.ts`.
