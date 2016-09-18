#!/usr/bin/env node

'use strict';

var ArgumentParser = require('argparse').ArgumentParser;
var meta = require('../package.json');
const tr = require('./trivial');
const PatchManager = require('./patch_manager');

function main() {
  var parser = new ArgumentParser({
    version: meta.version,
    addHelp: true,
    description: 'Command line interface of react-native-air-lite.'
  });

  parser.addArgument(
    ['-i', '--initial'], {
      action: 'storeTrue',
      help: 'Execute this to build bundle after 1st release.'
    }
  );

  parser.addArgument(
    ['-e', '--verify'], {
      action: 'storeTrue',
      help: 'The last patch has published'
    }
  );

  parser.addArgument(
    ['-p', '--pack'], {
      action: 'storeTrue',
      help: 'Build new patch'
    }
  );

  parser.addArgument(
    ['--platform'], {
      required: true,
      help: 'Which platform the new patch will apply, ios or android?'
    }
  );

  parser.addArgument(
    ['--entry'], {
      help: 'Entry file of your RN project. Default is index.'
    }
  );

  parser.addArgument(
    ['--patch-version'], {
      help: 'Patch of which version you want to verfify or update.'
    }
  );

  var args = parser.parseArgs();
  var pm = new PatchManager(args.platform, args.entry);

  if (args.verify) {
    tr.error('To be implemented');
    return;
  }

  if (args.pack) {
    pm.buildNewPatch();
    return;
  }

  tr.warn('Nothing happened!');
}

main();
