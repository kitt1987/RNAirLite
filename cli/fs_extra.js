'use strict';

const fs = require('fs');
const path = require('path');

function mkdirSync(yourPath) {
  if (fs.existsSync(yourPath)) return;
  fs.mkdirSync(yourPath);
}

function isRegularFile(fullPath) {
  var stat = fs.statSync(fullPath);
  return stat.isFile();
}

function replace(dst, src) {
  console.log('Move from', src, 'to', dst);
  rm(dst);
  cp(src, dst);
  rm(src);
}

function rm(fullPath) {
  if (!fs.existsSync(fullPath)) return;
  if (isRegularFile(fullPath)) {
    fs.unlinkSync(fullPath);
    return;
  }

  fs.readdirSync(fullPath).map((name) => {
    rm(path.join(fullPath, name));
  });
  fs.rmdirSync(fullPath);
}

function cp(src, dst) {
  if (isRegularFile(src)) {
    fs.renameSync(src, dst);
    return;
  }

  fs.mkdirSync(dst);
  fs.readdirSync(src).map((sub) => {
    if (sub === '.' || sub === '..') return;
    cp(path.join(src, sub), path.join(dst, sub));
  });
}

module.exports = {
  isRegularFile,
  mkdirSync,
  replace,
  rm,
  cp
};
