# BotBuy – Habbo Marketplace Auto-Buyer (G-Earth Extension)

Una extensión de [G-Earth](https://github.com/sirjonasxx/G-Earth) escrita en **Java** que:

1. Consulta **[habboapi.site](https://habboapi.site/)** para obtener el precio medio de la _bufanda de serpiente_ en el mercadillo.
2. Si el precio es **≤ 5 créditos**, busca automáticamente el furni en el mercadillo de Habbo.
3. Compra automáticamente la primera oferta encontrada cuyo precio sea **≤ 5 créditos**.

---

## Requisitos

| Herramienta | Versión mínima |
|-------------|----------------|
| Java JDK    | 8+             |
| Maven       | 3.8+           |
| G-Earth     | 1.5+           |

---

## Configuración

Edita el archivo `src/main/resources/config.properties` antes de compilar:

```properties
# Nombre del furni para buscar en habboapi.site
furni.name=bufanda de serpiente

# Nombre de búsqueda en el mercadillo de Habbo
furni.search.query=bufanda de serpiente

# Precio máximo (en créditos) — el bot compra si precio <= max.price
max.price=5

# Código del hotel para habboapi.site (com, de, es, fi, fr, it, nl, br, tr)
api.hotel=es

# Segundos entre cada ciclo de búsqueda (mínimo recomendado: 10)
search.interval.seconds=10
```

---

## Compilación

```bash
mvn clean package -DskipTests
```

El archivo resultante estará en `target/BotBuy-1.0.0.jar`.

---

## Uso

1. Abre **G-Earth** y conéctate a Habbo Hotel.
2. En la pestaña **Extra**, añade la extensión y apunta al JAR generado:
   ```
   target/BotBuy-1.0.0.jar
   ```
   O ejecuta directamente en la terminal:
   ```bash
   java -jar target/BotBuy-1.0.0.jar
   ```
3. La extensión aparecerá en G-Earth. Actívala con el botón verde.
4. El bot comenzará a consultar la API y a buscar en el mercadillo automáticamente.

---

## Flujo de funcionamiento

```
Cada {search.interval.seconds} segundos:
  ┌─ Consulta habboapi.site
  │  GET /api/market/history?name=bufanda+de+serpiente&hotel=es&days=7
  │
  ├─ ¿averagePrice <= max.price?
  │   SÍ ──► Envía GetMarketplaceOffers (búsqueda en Habbo)
  │   NO ──► Espera el siguiente ciclo
  │
  └─ Recibe MarketPlaceOffers
       └─ Para cada oferta:
            ├─ ¿precio <= max.price?
            │   SÍ ──► Envía BuyMarketplaceOffer (compra)
            └─ NO  ──► Continúa con la siguiente oferta
```

---

## Paquetes de red utilizados

| Dirección | Nombre del paquete          | Descripción                        |
|-----------|-----------------------------|------------------------------------|
| Outgoing  | `GetMarketplaceOffers`      | Búsqueda en el mercadillo          |
| Incoming  | `MarketPlaceOffers`         | Respuesta con las ofertas          |
| Outgoing  | `BuyMarketplaceOffer`       | Envío de la compra                 |
| Incoming  | `MarketplaceBuyOfferResult` | Confirmación de la compra          |

---

## Notas

- El bot **no** realiza ninguna compra si el precio de la API supera el límite configurado, evitando búsquedas innecesarias.
- La extensión funciona con el cliente moderno de Habbo (no Flash), que es el que G-Earth soporta actualmente.
- Si deseas cambiar el furni objetivo, simplemente actualiza `furni.name` y `furni.search.query` en `config.properties` y recompila.

---

## Estructura del proyecto

```
BotBuy/
├── pom.xml                                   # Build Maven
├── src/
│   └── main/
│       ├── java/com/botbuy/
│       │   ├── BotBuyExtension.java          # Clase principal (G-Earth extension)
│       │   ├── api/
│       │   │   └── HabboApiClient.java       # Cliente de habboapi.site
│       │   └── marketplace/
│       │       └── MarketplaceOffer.java     # Modelo de oferta del mercadillo
│       └── resources/
│           └── config.properties             # Configuración
└── README.md
```
