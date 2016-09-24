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

function loadAllPatches(patchBase) {
  var patches = fs.readdirSync(patchBase);
  patches = patches.filter(p => {
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

  console.log('Patches are', patches);
  return patches;
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

class PatchManager {
  constructor(platform, entry) {
    if (platform !== 'android' && platform !== 'ios') {
      throw new Error('The platform must be android or ios');
    }

    this.platform = platform;
    this.entry = entry || 'index';

    const patchBase = path.join(PATCH_BASE, this.platform);
    fse.mkdirSync(patchBase);
    this.patches = loadAllPatches(patchBase);
    if (this.patches.length > 0) {
      this.newVersion = 2 + this.patches[0];
      this.latestVersion = 1 + this.patches[0];
    } else {
      this.newVersion = 0;
      this.latestVersion = 0;
    }

    if (fs.existsSync(path.join(patchBase, NEWEST_PATCH)))
      this.patches.push(NEWEST_PATCH);

    console.log('The latest version is', this.latestVersion);
    console.log('The new version will be', this.newVersion);
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
    tr.info('The new patch outputs to ' + fileOut);
  }

  buildNewPatch() {
    this.prepareForNewPatch();
    const intermediatesRawPatch = this.getIntermediatesPath();
    const tmpAssetsTar = '/tmp/' + RAW_ASSETS;
    const newAssetsTar = this.getIntermediatesPath(RAW_ASSETS);

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
            fse.replace(newAssetsTar, tmpAssetsTar);
            const newAssetsBytes = fs.readFileSync(newAssetsTar);

            this.patches.forEach((version) => {
              const assetsBytes = fs.readFileSync(this.getRawAssets(version));
              var patchRawBuf = bs.diff(assetsBytes, newAssetsBytes);
              // FIXME null will be returned if 2 buffers are same.
              if (!patchRawBuf) {
                throw new Error(
                  'Both version ' + version + ' and the newest have same bundle.'
                );
              }

              this.pack(patchRawBuf, this.getPatchPath(version));
            });

            const newPatchPath = this.getNewPatchPath();
            if (fs.existsSync(newPatchPath)) {
              fse.replace(this.getPath(this.latestVersion), newPatchPath);
            }

            fs.mkdirSync(newPatchPath);
            fse.replace(this.getNewVersionRawPatch(), newAssetsTar);
            this.pack(newAssetsBytes, this.getNewVersionPatchPath());
            fse.rm(this.getIntermediatesPath());
          });


        fstream.Reader({
            path: intermediatesRawPatch,
            type: "Directory",
          })
          .on('error', err => {
            console.error('An error occurred:', err);
          })
          .pipe(packer)
          .pipe(fs.createWriteStream(tmpAssetsTar));
      }
    );
  }
}

module.exports = PatchManager;
