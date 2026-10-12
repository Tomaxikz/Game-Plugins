// Frame layer (README section 3): 4-byte big-endian unsigned body length, then a MessagePack body.
// Max body size is 4 MiB (4194304 bytes), enforced on both encode and decode.

import { encode, decode } from './msgpack.mjs';

export const MAX_FRAME = 4194304;

export class FrameError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'FrameError';
    this.code = code;
  }
}

/** Encode one message object as a complete frame (header + body). */
export function encodeFrame(message) {
  const body = encode(message);
  if (body.length > MAX_FRAME) {
    throw new FrameError('frame_too_large', `body ${body.length} bytes exceeds ${MAX_FRAME}`);
  }
  const out = Buffer.allocUnsafe(4 + body.length);
  out.writeUInt32BE(body.length, 0);
  out.set(body, 4);
  return out;
}

/** Build only the 4-byte header for a body length (used to send malformed/oversize frames). */
export function frameHeader(length) {
  const h = Buffer.allocUnsafe(4);
  h.writeUInt32BE(length >>> 0, 0);
  return h;
}

/**
 * Incremental frame reader. push() accepts chunks and returns the complete raw bodies
 * (Uint8Array). Throws FrameError('frame_too_large') as soon as a header announces more than
 * the maximum; the caller must then drop the connection.
 */
export class FrameReader {
  constructor({ maxFrame = MAX_FRAME } = {}) {
    this.maxFrame = maxFrame;
    this.buf = Buffer.alloc(0);
  }

  push(chunk) {
    this.buf = this.buf.length === 0 ? Buffer.from(chunk) : Buffer.concat([this.buf, chunk]);
    const bodies = [];
    while (this.buf.length >= 4) {
      const len = this.buf.readUInt32BE(0);
      if (len > this.maxFrame) {
        throw new FrameError('frame_too_large', `frame length ${len} exceeds ${this.maxFrame}`);
      }
      if (this.buf.length < 4 + len) break;
      bodies.push(new Uint8Array(this.buf.subarray(4, 4 + len)));
      this.buf = this.buf.subarray(4 + len);
    }
    return bodies;
  }

  get pending() {
    return this.buf.length;
  }
}

/** Decode a raw body to a message object. Throws on invalid MessagePack or trailing bytes. */
export function decodeBody(body) {
  return decode(body);
}
