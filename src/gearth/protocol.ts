/**
 * Habbo binary packet builder and parser.
 *
 * Habbo packet format:
 *   [4 bytes: total length (big-endian)] [2 bytes: header ID (big-endian)] [data...]
 *
 * Data types:
 *   String  : [2 bytes: string length (big-endian)] [UTF-8 bytes]
 *   Integer : [4 bytes big-endian]
 *   Short   : [2 bytes big-endian]
 *   Boolean : [1 byte]
 */

export class HabboPacket {
  private readonly buffer: Buffer;
  /** Absolute read position in the buffer. Starts at 2 to skip the 2-byte header. */
  private readPos: number;

  constructor(buffer: Buffer) {
    this.buffer = buffer;
    // Position 0-1 = header ID; data starts at position 2
    this.readPos = 2;
  }

  get headerId(): number {
    return this.buffer.readUInt16BE(0);
  }

  get bodyLength(): number {
    return this.buffer.length - 2;
  }

  // ── Readers ──────────────────────────────────────────────────────────────

  readInt(): number {
    const value = this.buffer.readInt32BE(this.readPos);
    this.readPos += 4;
    return value;
  }

  readShort(): number {
    const value = this.buffer.readInt16BE(this.readPos);
    this.readPos += 2;
    return value;
  }

  readBoolean(): boolean {
    const value = this.buffer.readUInt8(this.readPos) !== 0;
    this.readPos += 1;
    return value;
  }

  readString(): string {
    const length = this.buffer.readUInt16BE(this.readPos);
    this.readPos += 2;
    const str = this.buffer.toString('utf8', this.readPos, this.readPos + length);
    this.readPos += length;
    return str;
  }

  resetReadPos(): void {
    // Reset to start of data (after the 2-byte header)
    this.readPos = 2;
  }

  getRaw(): Buffer {
    return this.buffer;
  }
}

// ── Packet Builder ────────────────────────────────────────────────────────────

export class PacketBuilder {
  private parts: Buffer[] = [];

  constructor(private readonly headerId: number) {}

  addInt(value: number): this {
    const buf = Buffer.allocUnsafe(4);
    buf.writeInt32BE(value, 0);
    this.parts.push(buf);
    return this;
  }

  addShort(value: number): this {
    const buf = Buffer.allocUnsafe(2);
    buf.writeInt16BE(value, 0);
    this.parts.push(buf);
    return this;
  }

  addBoolean(value: boolean): this {
    const buf = Buffer.allocUnsafe(1);
    buf.writeUInt8(value ? 1 : 0, 0);
    this.parts.push(buf);
    return this;
  }

  addString(value: string): this {
    const strBuf = Buffer.from(value, 'utf8');
    const lenBuf = Buffer.allocUnsafe(2);
    lenBuf.writeUInt16BE(strBuf.length, 0);
    this.parts.push(lenBuf, strBuf);
    return this;
  }

  /**
   * Builds the complete Habbo packet:
   *   [4 bytes length][2 bytes header][data]
   * The length field equals (2 + data_length).
   */
  build(): Buffer {
    const header = Buffer.allocUnsafe(2);
    header.writeUInt16BE(this.headerId, 0);

    const body = Buffer.concat([header, ...this.parts]);
    const lengthBuf = Buffer.allocUnsafe(4);
    lengthBuf.writeUInt32BE(body.length, 0);

    return Buffer.concat([lengthBuf, body]);
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Parses a raw TCP stream buffer into an array of complete Habbo packets.
 * Returns [packets[], remainingBuffer].
 */
export function parsePackets(data: Buffer): [Buffer[], Buffer] {
  const packets: Buffer[] = [];
  let offset = 0;

  while (offset + 4 <= data.length) {
    const packetLength = data.readUInt32BE(offset);
    const totalLength = 4 + packetLength;

    if (offset + totalLength > data.length) {
      // Incomplete packet — wait for more data
      break;
    }

    // Include the 4-byte length prefix in the slice so callers can see full frame
    packets.push(data.slice(offset + 4, offset + totalLength));
    offset += totalLength;
  }

  return [packets, data.slice(offset)];
}
