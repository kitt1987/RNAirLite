/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 */

import React, { Component } from 'react';
import {
  AppRegistry,
  StyleSheet,
  Text,
  View
} from 'react-native';

import * as AirLite from 'react-native-air-lite';
// const AirLite = require('react-native-air-lite');

class sample extends Component {
  sample() {
    this.url = 'http://localhost'
  }

  render() {
    return (
      <View style={styles.container}>
        <Text style={styles.welcome}>
          Welcome to React Native!
        </Text>
        <Text style={styles.instructions} onPress={() => AirLite.checkUpdate(this.url)}>
          Check Update
        </Text>
        <Text style={styles.instructions} onPress={() => AirLite.update(this.url)}>
          Update
        </Text>
      </View>
    );
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#F5FCFF',
  },
  welcome: {
    fontSize: 20,
    textAlign: 'center',
    margin: 10,
  },
  instructions: {
    textAlign: 'center',
    color: '#333333',
    marginBottom: 5,
  },
});

AppRegistry.registerComponent('sample', () => sample);
