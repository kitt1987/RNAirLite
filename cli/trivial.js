'use strict';
const colors = require('colors');

function warn(t) {
	console.log(t.yellow);
}

function info(t) {
	console.log(t.green);
}

function verbose(t) {
	console.log(t);
}

function error(t) {
	console.log(t.red);
}

module.exports = {
	warn,
	info,
	verbose,
	error,
};
