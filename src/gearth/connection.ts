import * as net from 'net';
import { EventEmitter } from 'events';
import { config } from '../config';
import { PacketBuilder, HabboPacket, parsePackets } from './protocol';

/**
 * G.Earth Extension Protocol Headers
 *
 * G.Earth communicates with extensions over a local TCP socket (default port 9092).
 *
 * Handshake:
 *   1. G.Earth → Extension: HELLO (1)
 *   2. Extension → G.Earth: INFO (4)  — extension metadata
 *   3. G.Earth → Extension: CONNECTED (7) — game host/port info
 *
 * Packet interception:
 *   G.Earth → Extension: PACKET_INTERCEPT (2) — an intercepted game packet
 *
 * Sending packets to the game:
 *   Extension → G.Earth: SENDPACKET (6) — send a raw Habbo packet to the game
 */
const GEARTH_HEADERS = {
  INCOMING_HELLO: 1,
  INCOMING_PACKET_INTERCEPT: 2,
  INCOMING_CONNECTED: 7,
  OUTGOING_INFO: 4,
  OUTGOING_SENDPACKET: 6,
} as const;

export interface GameConnectionInfo {
  host: string;
  port: number;
  hotel: string;
}

/**
 * Events emitted by GearthConnection:
 *   'connected'          — G.Earth handshake complete
 *   'game_connected'     — Game server connection info received (GameConnectionInfo)
 *   'packet'             — A Habbo packet intercepted from the game (HabboPacket, direction: 'in'|'out')
 *   'disconnected'       — TCP socket closed
 *   'error'              — An error occurred (Error)
 */
export class GearthConnection extends EventEmitter {
  private socket: net.Socket | null = null;
  private buffer: Buffer = Buffer.alloc(0);
  private connected = false;

  constructor(
    private readonly host = config.gearthHost,
    private readonly port = config.gearthPort,
  ) {
    super();
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = new net.Socket();
      this.socket = socket;

      socket.connect(this.port, this.host, () => {
        console.log(`[G.Earth] Conectado a ${this.host}:${this.port}`);
      });

      socket.on('data', (data: Buffer) => {
        this.buffer = Buffer.concat([this.buffer, data]);
        this.processBuffer();
      });

      socket.on('close', () => {
        this.connected = false;
        console.log('[G.Earth] Conexión cerrada');
        this.emit('disconnected');
      });

      socket.on('error', (err: Error) => {
        console.error('[G.Earth] Error de socket:', err.message);
        this.emit('error', err);
        reject(err);
      });

      // Resolve once handshake completes
      this.once('connected', () => resolve());

      // Reject if the socket errors before handshake
      socket.once('error', reject);
    });
  }

  disconnect(): void {
    this.socket?.destroy();
    this.socket = null;
  }

  /**
   * Sends a raw Habbo packet to the game server through G.Earth.
   * @param packet Raw Habbo packet bytes (with 4-byte length prefix)
   */
  sendToServer(packet: Buffer): void {
    if (!this.socket || !this.connected) {
      throw new Error('No hay conexión activa con G.Earth');
    }

    // G.Earth SENDPACKET wrapper:
    //   [4 bytes: outer length][2 bytes: header OUTGOING_SENDPACKET]
    //   [1 byte: direction — 1 = to server]
    //   [4 bytes: inner packet length][2 bytes: inner header][inner data]
    const directionByte = Buffer.from([1]); // 1 = to server
    const inner = packet; // already has [4-byte length][header][data]

    const body = Buffer.concat([directionByte, inner]);
    const outerHeader = Buffer.allocUnsafe(2);
    outerHeader.writeUInt16BE(GEARTH_HEADERS.OUTGOING_SENDPACKET, 0);

    const outerFrame = Buffer.concat([outerHeader, body]);
    const outerLength = Buffer.allocUnsafe(4);
    outerLength.writeUInt32BE(outerFrame.length, 0);

    this.socket.write(Buffer.concat([outerLength, outerFrame]));
  }

  isConnected(): boolean {
    return this.connected;
  }

  // ── Private helpers ─────────────────────────────────────────────────────

  private processBuffer(): void {
    const [frames, remaining] = parsePackets(this.buffer);
    this.buffer = remaining;

    for (const frame of frames) {
      this.handleGearthFrame(frame);
    }
  }

  private handleGearthFrame(frame: Buffer): void {
    if (frame.length < 2) return;

    const headerId = frame.readUInt16BE(0);

    switch (headerId) {
      case GEARTH_HEADERS.INCOMING_HELLO:
        console.log('[G.Earth] HELLO recibido — enviando info de extensión');
        this.sendExtensionInfo();
        break;

      case GEARTH_HEADERS.INCOMING_CONNECTED: {
        const info = this.parseConnectedPacket(frame);
        console.log(`[G.Earth] Conectado al juego: ${info.host}:${info.port} (${info.hotel})`);
        this.connected = true;
        this.emit('connected');
        this.emit('game_connected', info);
        break;
      }

      case GEARTH_HEADERS.INCOMING_PACKET_INTERCEPT:
        this.handleInterceptedPacket(frame);
        break;

      default:
        // Unknown G.Earth control packet — ignore
        break;
    }
  }

  /** Sends the extension metadata to G.Earth after receiving HELLO. */
  private sendExtensionInfo(): void {
    const body = new PacketBuilder(GEARTH_HEADERS.OUTGOING_INFO)
      .addString(config.extensionName)
      .addString(config.extensionDescription)
      .addString(config.extensionVersion)
      .addString(config.extensionAuthor)
      .addBoolean(false) // isFireEvent
      .build();

    this.socket?.write(body);
    console.log('[G.Earth] Info de extensión enviada');
  }

  /** Parses a CONNECTED frame to extract game host/port/hotel. */
  private parseConnectedPacket(frame: Buffer): GameConnectionInfo {
    // Frame layout (after 2-byte header): host (string), port (int), hotel (string)
    try {
      const packet = new HabboPacket(frame);
      const host = packet.readString();
      const port = packet.readInt();
      const hotel = packet.readString();
      return { host, port, hotel };
    } catch {
      return { host: 'unknown', port: 0, hotel: 'unknown' };
    }
  }

  /**
   * Handles an intercepted game packet forwarded by G.Earth.
   *
   * G.Earth PACKET_INTERCEPT frame layout (after 2-byte header):
   *   [1 byte: direction — 0=from server, 1=from client]
   *   [original Habbo packet bytes (4-byte length + header + data)]
   */
  private handleInterceptedPacket(frame: Buffer): void {
    if (frame.length < 3) return;

    const direction = frame.readUInt8(2) === 0 ? 'in' : 'out';
    const innerPacket = frame.slice(3); // raw Habbo packet

    try {
      const packet = new HabboPacket(innerPacket);
      this.emit('packet', packet, direction);
    } catch {
      // Malformed packet — skip
    }
  }
}
