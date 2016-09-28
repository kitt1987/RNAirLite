/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 * @flow
 */

import React, { Component } from 'react';
import {
  AppRegistry,
  StyleSheet,
  Text,
  Image,
  View
} from 'react-native';

import * as Air from 'react-native-air-lite';

class RNSample extends Component {
  state = {
    version: 1,
    newVersion: 1,
    progress: 0.0,
  };

  componentDidMount() {
    Air.init('http://10.0.2.2:8080/airlite/', this.state.version);
    Air.addEventListener('error', (err) => {
      console.log('Error', err);
    });

    Air.addEventListener('checked', (version) => {
      console.log('A new version found', version);
      this.setState({newVersion: version});
    });

    Air.addEventListener('downloaded', (version) => {
      console.log('The new version is downloaded', version);
      this.setState({newVersion: version});
    });

    Air.addEventListener('progress', (event) => {
      console.log('progress', event);
      this.setState({progress: event.downloaded / event.total});
    });
  }

  render() {
    return (
      <View style={styles.container}>
        <Image source={require('./image/wechat.png')} />
        <Text style={styles.welcome} onPress={() => {Air.checkForUpdate()}}>
          Check for update!
        </Text>
        <Text style={styles.instructions}>
          Current Version is {this.state.version}. The new version will be {this.state.newVersion}.
        </Text>
        <Text style={styles.welcome} onPress={() => {Air.downloadPatch()}}>
          Download({this.state.progress})!
        </Text>
        <Text style={styles.welcome} onPress={() => {Air.installPatch()}}>
          Install(Automatically)!
        </Text>
        <Text style={styles.welcome} onPress={() => {Air.installPatch(true)}}>
          Install(Manually)!
        </Text>
        <Text style={styles.welcome} onPress={() => {Air.restart()}}>
          Reboot!
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

AppRegistry.registerComponent('RNSample', () => RNSample);
