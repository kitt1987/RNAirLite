'use strict';

const tr = require('./trivial');
const fs = require('fs');
const fse = require('./fs_extra');
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

function loadAllPatches(patchBase) {
  return fs.readdirSync(patchBase).filter(p => {
    if (p[0] === '.') return false;
    var isDigital = /^\d+$/.test(p);
    if (!isDigital) {
      if (p !== NEWEST_PATCH) tr.warn(p + ' is not a valid patch version.');
      return false;
    }

    var versionPath = path.join(patchBase, p);
    var stat = fs.statSync(versionPath);
    if (!stat.isDirectory()) {
      tr.error(p + ' is not a valid cause it is not a directory.');
      return false;
    }

    return fse.isRegularFile(path.join(versionPath, RAW_ASSETS)) &&
      fse.isRegularFile(path.join(versionPath, BASE_PACKAGE));
  }).map(p => parseInt(p)).sort((a, b) => b - a);
}

function getBundleCommand(platform, patchDir, entry) {
  if (platform !== 'ios' && platform !== 'android')
    throw new Error('Only ios and android are supported. ->' + platform);
  if (!patchDir)
    throw new Error('A directory is required to save patch files');
  entry = entry || 'index';
  const cmd = 'react-native bundle --platform %s --entry-file %s.%s.js --dev false --bundle-output "%s/%s" --assets-dest "%s"';
  return util.format(cmd, platform, entry, platform, patchDir, JSBUNDLE_NAME,
    patchDir);
}

function loadPatchVersion(patchPath) {
  const fd = fs.openSync(patchPath, 'r');
  const buf = Buffer.alloc(HEADER_LENGTH.VERSION);
  const bytesRead = fs.readSync(
    fd, buf, 0,
    HEADER_LENGTH.VERSION,
    HEADER_LENGTH.PACK_VERSION
  );

  fs.closeSync(fd);
  if (bytesRead !== HEADER_LENGTH.VERSION)
    throw new Error(patchPath + ' is corrupted.');

  return buf.readUInt32LE();
}

class PatchManager {
  constructor(platform, entry, newestVersion) {
    if (platform !== 'android' && platform !== 'ios') {
      throw new Error('The platform must be android or ios');
    }

    this.platform = platform;
    this.entry = entry || 'index';

    const patchBase = path.join(PATCH_BASE, this.platform);
    fse.mkdirSync(patchBase);
    this.patches = loadAllPatches(patchBase);

    if (this.patches.length > 0) {
      tr.info('Versions of patches loaded are', this.patches);
      this.latestVersion = 1 + this.patches[0];
      this.newVersion = 1 + this.patches[0];
    } else {
      this.newVersion = 0;
      this.latestVersion = 0;
    }

    if (fs.existsSync(path.join(patchBase, NEWEST_PATCH))) {
      this.patches.push(NEWEST_PATCH);
      this.latestVersion = loadPatchVersion(this.getNewVersionPatchPath());
      this.newVersion = this.latestVersion + 1;
    }

    if (newestVersion) {
      if (newestVersion <= this.newVersion) {
        throw new Error(
          'The version of newest patch must be greater than' + this.newVersion
        );
      }

      this.newVersion = newestVersion;
    }

    tr.info('The latest version will be', this.latestVersion);
    tr.info('The new version will be', this.newVersion);
  }

  getPath(version, file) {
    if (version === undefined || version === null) {
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
    fse.mkdirSync(this.getIntermediatesPath());
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

    const temp = this.getIntermediatesPath('pack.tmp');
    fs.writeFileSync(temp, Buffer.concat([header, patchBuf]));
    fse.replace(fileOut, temp);
  }

  buildNewPatch() {
    this.prepareForNewPatch();
    const intermediatesRawPatch = this.getIntermediatesPath();
    const tmpAssetsTar = '/tmp/' + RAW_ASSETS;
    const newAssetsTar = this.getIntermediatesPath(RAW_ASSETS);

    tr.info('Building new JS bundle...');
    cp.exec(getBundleCommand(this.platform, intermediatesRawPatch, this.entry),
      (error, stdout, stderr) => {
        if (error) {
          tr.error('An error occurred:', err);
          return;
        }

        if (stderr) {
          tr.error(stderr);
          throw new Error('Fail to build RN bundle.js');
        }

        tr.verbose(stdout);
        var packer = tar.Pack({
            noProprietary: true
          })
          .on('error', err => {
            tr.error('An error occurred:', err);
          })
          .on('end', () => {
            fse.replace(newAssetsTar, tmpAssetsTar);
            tr.info('The newest JS bundle is packed.');
            const newAssetsBytes = fs.readFileSync(newAssetsTar);

            this.patches.forEach((version) => {
              const assetsBytes = fs.readFileSync(this.getRawAssets(version));
              const patchRawBuf = bs.diff(assetsBytes, newAssetsBytes);
              const patchPath = this.getPatchPath(version);
              this.pack(patchRawBuf, patchPath);
              tr.info('Generating patch for version', version, patchPath);
            });

            const newPatchPath = this.getNewPatchPath();
            if (fs.existsSync(newPatchPath)) {
              fse.replace(this.getPath(this.latestVersion), newPatchPath);
            }

            fs.mkdirSync(newPatchPath);
            fse.replace(this.getNewVersionRawPatch(), newAssetsTar);
            this.pack(newAssetsBytes, this.getNewVersionPatchPath());
            fse.rm(this.getIntermediatesPath());
            tr.info('The newest patch version is', this.newVersion);
          });

        fstream.Reader({
            path: intermediatesRawPatch,
            type: "Directory",
          })
          .on('error', err => {
            tr.error('An error occurred:', err);
          })
          .pipe(packer)
          .pipe(fs.createWriteStream(tmpAssetsTar));
      }
    );
  }
}

module.exports = PatchManager;
