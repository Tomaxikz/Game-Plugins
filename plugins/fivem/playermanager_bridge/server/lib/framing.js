'use strict';
// 4-byte big-endian length prefix + body. Frames > maxFrame are a protocol error.
const MAX_FRAME = 4 * 1024 * 1024;

class FrameDecoder {
  constructor(maxFrame = MAX_FRAME) {
    this.maxFrame = maxFrame;
    this.buf = Buffer.alloc(0);
  }
  push(chunk) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    const frames = [];
    while (this.buf.length >= 4) {
      const len = this.buf.readUInt32BE(0);
      if (len > this.maxFrame) throw Object.assign(new Error('frame too large'), { code: 'invalid_args' });
      if (this.buf.length < 4 + len) break;
      frames.push(Buffer.from(this.buf.subarray(4, 4 + len)));
      this.buf = this.buf.subarray(4 + len);
    }
    return frames;
  }
}

function encodeFrame(body, maxFrame = MAX_FRAME) {
  if (body.length > maxFrame) throw Object.assign(new Error('frame too large'), { code: 'internal' });
  const head = Buffer.alloc(4);
  head.writeUInt32BE(body.length, 0);
  return Buffer.concat([head, body]);
}

module.exports = { FrameDecoder, encodeFrame, MAX_FRAME };
