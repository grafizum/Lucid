'use strict';

/**
 * Self-test harness.
 *
 * Boots the REAL app (same main.js, preload, windows and IPC) with a throwaway
 * user-data directory, drives it, and captures each of its own windows so the
 * overlay can be inspected without leaving anything running.
 *
 *   electron scripts/selftest.js
 */

const path = require('node:path');
const fs = require('node:fs');

process.argv.push('--selftest');

const electronBinary = require('electron');
if (!process.versions.electron) {
  // Running under plain node — hand off to electron.
  require('node:child_process').spawnSync(electronBinary, [__filename], { stdio: 'inherit' });
  process.exit(0);
}

require('../src/main.js');
