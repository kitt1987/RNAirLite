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
    AirLite.EventUpdated
  ];
}

function checkUpdate(url) {
  AirLite.checkUpdate(url);
}

function update(url) {
  AirLite.update(url);
}

function rollbackOnError(enable) {
  AirLite.rollbackOnError();
}

function addEventListener(event, listener) {
  if (allEvents().indexOf(event) < 0) return;
  return DeviceEventEmitter.addListener(event, eventHandle.bind(null, event,
    listener));
}

module.exports = {
  checkUpdate,
  update,
  rollbackOnError,
  addEventListener
};