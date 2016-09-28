'use strict';

var {
  NativeModules,
  DeviceEventEmitter
} = require('react-native');

var AirLite = NativeModules.RNAirLite;

function allEvents() {
  return [
    AirLite.EventChecked,
    AirLite.EventError,
    AirLite.EventProgress,
    AirLite.EventDownloaded,
    AirLite.EventInstalled,
  ];
}

function init(uri, bundleVersion, storePatchInSD) {
  AirLite.init(uri, bundleVersion, !!storePatchInSD);
}

function checkForUpdate() {
  AirLite.checkForUpdate();
}

function downloadPatch() {
  AirLite.downloadPatch();
}

function installPatch(restartManually) {
  AirLite.installPatch(!!restartManually);
}

function restart() {
  AirLite.restart();
}

function addEventListener(event, listener) {
  if (allEvents().indexOf(event) < 0)
    throw new Error('Event supported are ' + allEvents().join());
  return DeviceEventEmitter.addListener(event, listener);
}

module.exports = {
  allEvents,
  init,
  checkForUpdate,
  downloadPatch,
  installPatch,
  addEventListener,
  restart,
};
