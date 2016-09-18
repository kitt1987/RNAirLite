'use strict';

var {
  NativeModules,
  DeviceEventEmitter
} = require('react-native');

var AirLite = NativeModules.RNAirLite;

function allEvents() {
  return [
    AirLite.EventUpdate,
    AirLite.EventError,
    AirLite.EventProgress,
    AirLite.EventInstalled,
  ];
}

function init(uri, bundleVersion, storePatchInSD) {
  AirLite.checkUpdate(uri, bundleVersion, !!storePatchInSD);
}

function checkUpdate() {
  AirLite.checkUpdate();
}

function downloadPatch() {
  AirLite.downloadPatch();
}

function installPatch(restartManually) {
  AirLite.installPatch(restartManually);
}

function restart() {
  AirLite.restart();
}

function addEventListener(event, listener) {
  if (allEvents().indexOf(event) < 0) return;
  return DeviceEventEmitter.addListener(event, eventHandle.bind(null, event,
    listener));
}

module.exports = {
  allEvents,
  init,
  checkUpdate,
  downloadPatch,
  installPatch,
  addEventListener
};
