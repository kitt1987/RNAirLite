'use strict';

const tr = require('./trivial');
const fs = require('fs');
const path = require('path');
const cp = require('child_process');
const util = require('util');
const tar = require('tar');
const fstream = require('fstream');
const bs = require('node-addon-bsdiff');
const bz2 = require('node-addon-bz2');
const crypto = require('crypto');

const PATCH_BASE = 'airlite';
const JSBUNDLE_NAME = 'main.jsbundle';
const ASSETS = 'assets';
const INTERMEDIATES = '.intermediates';
const RAW_ASSETS = 'assets.tar';
const BASE_PACKAGE = 'base';
const PATCH_PACKAGE = 'patch';
const NEWEST_PATCH = 'newest';

const HEADER_LENGTH = {
  PACK_VERSION: 1,
  VERSION: 4,
  SHA: 32,
  RESERVED: 27
};

const LENGTH_HEADER = Object.keys(HEADER_LENGTH).reduce(
  (prev, k) => prev + HEADER_LENGTH[k], 0);

function mkdirSync(yourPath) {
  if (fs.existsSync(yourPath)) return;
  fs.mkdirSync(yourPath);
}

function isRegular(filePath) {
  if (!fs.existsSync(filePath)) {
    tr.error(filePath + ' not found!');
    return false;
  }

  var stat = fs.statSync(filePath);
  if (!stat.isFile()) {
    tr.error(filePath + ' is not a regular file.');
    return false;
  }
}

function loadAllPatches(platform) {
  var patches = fs.readdirSync(path.join(PATCH_BASE, platform));
  patches = patches.filter(p => {
    if (p[0] === '.') return false;
    var isDigital = /^\d+$/.test(p);
    if (!isDigital) {
      tr.warn(p + ' is not a valid patch version.');
      return isDigital;
    }

    var versionPath = path.join(PATCH_BASE, platform, p);
    var stat = fs.statSync(versionPath);
    if (!stat.isDirectory()) {
      tr.error(p + ' is not a valid cause it is not a directory.');
      return false;
    }

    return isRegular(path.join(platformPath, RAW_ASSETS)) &&
      isRegular(path.join(platformPath, BASE_PACKAGE));
  }).map(p => parseInt(p)).sort((a, b) => b - a);
  return patches;
}

function getBundleCommand(platform, patchDir, entry) {
  // platform entry-file platform output-directory output-directory
  if (platform !== 'ios' && platform !== 'android')
    throw new Error('Only ios and android are supported. ->' + platform);
  if (!patchDir)
    throw new Error('A directory is required to save patch files');
  entry = entry || 'index';
  const cmd = 'react-native bundle --platform %s --entry-file %s.%s.js --dev false --bundle-output "%s/%s" --assets-dest "%s"';
  return util.format(cmd, platform, entry, platform, patchDir, JSBUNDLE_NAME,
    patchDir);
}

class PatchManager {
  constructor(platform, entry) {
    if (platform !== 'android' && platform !== 'ios') {
      throw new Error('The platform must be android or ios');
    }

    this.platform = platform;
    this.entry = entry || 'index';

    mkdirSync(path.join(PATCH_BASE, this.platform));
    this.patches = loadAllPatches(this.platform);
    if (this.patches.length > 0) {
      this.newVersion = 2 + this.patches[0];
      this.latestVersion = 1 + this.patches[0];
    } else {
      this.newVersion = 0;
      this.latestVersion = 0;
    }
  }

  getPath(version, file) {
    if (!version) {
      return path.join(PATCH_BASE, this.platform);
    }

    if (file) {
      return path.join(PATCH_BASE, this.platform, '' + version, file);
    } else {
      return path.join(PATCH_BASE, this.platform, '' + version);
    }
  }

  getIntermediatesPath(file) {
    return this.getPath(INTERMEDIATES, file);
  }

  getNewPatchPath() {
    return this.getPath(NEWEST_PATCH);
  }

  getRawAssets(version) {
    return this.getPath(version, RAW_ASSETS);
  }

  getNewVersionRawPatch() {
    return this.getRawAssets(NEWEST_PATCH);
  }

  getNewVersionPatchPath() {
    return this.getPath(NEWEST_PATCH, BASE_PACKAGE);
  }

  getPatchPath(version) {
    return this.getPath(version, PATCH_PACKAGE);
  }

  prepareForNewPatch() {
    const newPatchPath = this.getNewPatchPath();
    if (fs.existsSync(newPatchPath)) {
      fs.renameSync(newPatchPath, this.getPath(this.latestVersion));
    }

    [
      this.getPath(NEWEST_PATCH),
      this.getIntermediatesPath(),
    ].map(mkdirSync);
  }

  pack(content, fileOut) {
    var patchBuf = bz2.compressSync(content);

    var header = Buffer.alloc(LENGTH_HEADER, 0);
    header.writeUInt8(0x01);
    header.writeUInt32LE(this.newVersion, HEADER_LENGTH.PACK_VERSION);
    var hasher = crypto.createHash('sha256');
    hasher.update(header);
    hasher.update(patchBuf);
    hasher.digest().copy(header,
      HEADER_LENGTH.PACK_VERSION + HEADER_LENGTH.VERSION,
      0
    );

    fs.writeFileSync(fileOut, Buffer.concat([header, patchBuf]));
    tr.info('The new patch outputs to ' + fileOut);
  }

  buildNewPatch() {
    this.prepareForNewPatch();
    const intermediatesRawPatch = this.getIntermediatesPath();
    const newAssetsTar = this.getNewVersionRawPatch();

    cp.exec(getBundleCommand(this.platform, intermediatesRawPatch, this.entry),
      (error, stdout, stderr) => {
        if (error) {
          console.error('An error occurred:', err);
          return;
        }

        if (stderr) {
          tr.error(stderr);
          throw new Error('Fail to build RN bundle.js');
        }

        tr.info(stdout);
        var packer = tar.Pack({
            noProprietary: true
          })
          .on('error', err => {
            console.error('An error occurred:', err);
          })
          .on('end', () => {
            const newAssetsBytes = fs.readFileSync(newAssetsTar);
            const patchPath = this.getNewVersionPatchPath();
            this.pack(newAssetsBytes, patchPath);

            this.patches.forEach((version) => {
              const assetsBytes = fs.readFileSync(this.getRawAssets(version));
              var patchRawBuf = bs.diff(assetsBytes, newAssetsBytes);
              if (!patchRawBuf) {
                tr.error(
                  'Both version ' + version + ' and the newest have same bundle.'
                );
                return;
              }

              this.pack(patchRawBuf, this.getPatchPath(version));
            });
          });


        fstream.Reader({
            path: intermediatesRawPatch,
            type: "Directory"
          })
          .on('error', err => {
            console.error('An error occurred:', err);
          })
          .pipe(packer)
          .pipe(fs.createWriteStream(newAssetsTar));
      }
    );
  }
}

module.exports = PatchManager;
