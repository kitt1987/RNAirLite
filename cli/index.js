#!/usr/bin/env node

'use strict';

var ArgumentParser = require('argparse').ArgumentParser;
var meta = require('./package.json');
const fs = require('fs');
const crypto = require('crypto');
const bs = require('node-addon-bsdiff');
const bz2 = require('node-addon-bz2');
const colors = require('colors');

const HEADER_LENGTH = {
  PACK_VERSION: 1,
  VERSION: 4,
  SHA: 32,
  RESERVED: 27
};

const CHUNK_SIZE = 1024 * 1024;

const LENGTH_HEADER = Object.keys(HEADER_LENGTH).reduce(
  (prev, k) => prev + HEADER_LENGTH[k], 0);

function calcVersion(patch) {
  return new Promise((resolve, reject) => {
    if (!patch) {
      resolve(0);
      return;
    }

    fs.open(patch, 'r', (err, fd) => {
      if (err) {
        reject(err);
        return;
      }

      console.assert(fd >= 0);
      var buf = Buffer.alloc(HEADER_LENGTH.VERSION);
      var bytes = fs.read(
        patchFD, buf, 0, HEADER_LENGTH.VERSION, HEADER_LENGTH.PACK_VERSION,
        (err, bytesRead, buffer) => {
          if (err) {
            reject(err);
            return;
          }

          if (bytesRead < HEADER_LENGTH.VERSION) {
            reject(new Error('Your patch file may be corrupt.'));
            return;
          }

          fs.closeSync(patchFD);
          resolve(buf.readUInt32LE());
        }
      );
    });
  });
}

function buildPatch2(version, newVersion, old, newest) {
  var patchName = 'patch@' + version + '?~' + newVersion + '-' + Date.now();
  var oldBuf = fs.readFileSync(old);
  var newBuf = fs.readFileSync(newest);
  var patchBuf = bz2.compressSync(bs.diff(oldBuf, newBuf));

  var header = Buffer.alloc(LENGTH_HEADER, 0);
  header.writeUInt8(0x01);
  header.writeUInt32LE(version, HEADER_LENGTH.PACK_VERSION);
  var hasher = crypto.createHash('sha256');
  hasher.update(header);
  hasher.update(patchBuf);
  hasher.digest().copy(header,
    HEADER_LENGTH.PACK_VERSION + HEADER_LENGTH.VERSION,
    0
  );

  fs.writeFileSync(patchName, Buffer.concat([header, patchBuf]));
}

function verifyPatch(old, patch, newest) {
  var patchBuf = fs.readFileSync(patch);
  var packVersion = patchBuf.readUInt8();
  console.log('The pack version of your patch is ' + packVersion);
  var patchVersion = patchBuf.readUInt32LE(HEADER_LENGTH.PACK_VERSION);
  console.log('Your patch version is ' + patchVersion);
  var checksum = Buffer.alloc(HEADER_LENGTH.SHA);
  patchBuf.copy(checksum, 0,
    HEADER_LENGTH.PACK_VERSION + HEADER_LENGTH.VERSION,
    HEADER_LENGTH.PACK_VERSION + HEADER_LENGTH.VERSION + HEADER_LENGTH.SHA
  );

  Buffer.alloc(HEADER_LENGTH.SHA, 0).copy(patchBuf,
    HEADER_LENGTH.PACK_VERSION + HEADER_LENGTH.VERSION,
    0
  );

  var hasher = crypto.createHash('sha256');
  hasher.update(patchBuf);

  const d = hasher.digest();
  if (d.compare(checksum) !== 0) {
    console.log('Checksum verfication failed.'.red);
    return;
  }

  var rawPatch = bz2.decompressSync(patchBuf.slice(LENGTH_HEADER));
  var rawData = bs.patch(fs.readFileSync(old), rawPatch);
  var newestDigestHasher = crypto.createHash('md5');
  newestDigestHasher.update(fs.readFileSync(newest));
  var rawDataHaser = crypto.createHash('md5');
  rawDataHaser.update(rawData);
  if (newestDigestHasher.digest().compare(rawDataHaser.digest()) !== 0) {
    console.log('Your patch is corrupt.'.red);
    return;
  }

  console.log('Your patch if fine.'.green);
}

function main() {
  var parser = new ArgumentParser({
    version: meta.version,
    addHelp: true,
    description: 'Command line interface of react-native-air-lite.'
  });

  parser.addArgument(
    ['-verify'], {
      action: 'storeTrue',
      help: 'The last patch has published'
    }
  );

  parser.addArgument(
    ['--patch'], {
      help: 'The last patch has published'
    }
  );

  parser.addArgument(
    ['--latest'], {
      required: true,
      help: 'The latest bundle has published or build in apk or ipa'
    }
  );

  parser.addArgument(
    ['--new'], {
      required: true,
      help: 'The new bundle going to publish'
    }
  );

  var args = parser.parseArgs();
  if (args.verify) {
    verifyPatch(args.latest, args.patch, args.new);
    return;
  }

  calcVersion(args.patch)
    .then(version => {
      var newVersion = 1 + version;
      console.log('Your new patch version is ' + newVersion);
      if (version !== 1 && !args.latest)
        throw new Error(`You have to offer the latest bundle file your have 
          published`);
      return buildPatch2(version, newVersion, args.latest, args.new);
    })
    .catch(err => console.log(err.stack));
}

main();
