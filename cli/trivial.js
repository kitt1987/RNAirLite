'use strict';
const colors = require('colors');

function warn(...text) {
	console.log(colors.yellow(text.join(' ')));
}

function info(...text) {
	console.log(colors.green(text.join(' ')));
}

function verbose(...text) {
	console.log(text);
}

function error(...text) {
	console.log(colors.red(text.join(' ')));
}

module.exports = {
	warn,
	info,
	verbose,
	error,
};
