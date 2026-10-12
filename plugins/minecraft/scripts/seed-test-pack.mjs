#!/usr/bin/env node
// Writes a tiny synthetic resource pack (solid-colour PNGs generated here, no third-party art) into
// <server root>/plugins/Oraxen/pack so asset conformance tests have something to serve.
//   node seed-test-pack.mjs <server-root>
import { mkdirSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { join } from 'node:path';

const root = process.argv[2];
if (!root) { console.error('usage: seed-test-pack.mjs <server-root>'); process.exit(2); }
const pack = join(root, 'plugins', 'Oraxen', 'pack');

function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function png(w, h, rgb) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const o = y * (w * 4 + 1) + 1 + x * 4;
      raw[o] = (rgb >> 16 & 255) ^ (y * 3 & 255); raw[o + 1] = (rgb >> 8 & 255) ^ (x * 5 & 255); raw[o + 2] = rgb & 255; raw[o + 3] = 255;
    }
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
function put(rel, data) {
  const f = join(pack, rel);
  mkdirSync(join(f, '..'), { recursive: true });
  writeFileSync(f, data);
}
const json = (o) => JSON.stringify(o);

put('assets/pmtest/models/item/ruby.json', json({ parent: 'minecraft:item/generated', textures: { layer0: 'pmtest:item/ruby' } }));
put('assets/pmtest/textures/item/ruby.png', png(16, 16, 0xaa1133));
put('assets/pmtest/models/item/ruby_block.json', json({ parent: 'pmtest:block/ruby_block' }));
put('assets/pmtest/models/block/ruby_block.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'pmtest:block/ruby_block' } }));
put('assets/pmtest/textures/block/ruby_block.png', png(16, 16, 0x881122));
put('assets/pmtest/models/item/spinner.json', json({ parent: 'minecraft:item/generated', textures: { layer0: 'pmtest:item/spinner' } }));
put('assets/pmtest/textures/item/spinner.png', png(16, 64, 0x2255aa));
put('assets/pmtest/textures/item/spinner.png.mcmeta', json({ animation: { frametime: 3, interpolate: true } }));
put('assets/pmtest/items/sword.json', json({ model: { type: 'minecraft:model', model: 'pmtest:item/sword' } }));
put('assets/pmtest/models/item/sword.json', json({ parent: 'minecraft:item/handheld', textures: { layer0: 'pmtest:item/sword' } }));
put('assets/pmtest/textures/item/sword.png', png(16, 16, 0x55aa55));
// Note: a custom_model_data override on a vanilla item (assets/minecraft/models/item/paper.json) is deliberately not seeded:
// its manifest key is item:minecraft:paper@minecraft:paper#cmd:N, which conformance/tests/assets.mjs rejects as a vanilla key.
put('assets/pmtest/models/item/coin.json', json({ parent: 'minecraft:item/generated', textures: { layer0: 'pmtest:item/coin' } }));
put('assets/pmtest/textures/item/coin.png', png(16, 16, 0xddaa22));
console.log('seeded test pack in', pack);
