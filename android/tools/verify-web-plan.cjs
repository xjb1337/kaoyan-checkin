const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const source = html.slice(html.indexOf('const START='), html.indexOf('const REVISE='));
const expected = JSON.parse(vm.runInNewContext(source + ';JSON.stringify({START,HISTORY_START,LEGACY_PHASE,PHASES,PH4,PH5,SUBJ,MILES})'));
const actual = JSON.parse(fs.readFileSync(path.join(root, 'android/app/src/main/assets/plan.json'), 'utf8'));
assert.deepStrictEqual(actual, expected);
console.log('Web / Android plan parity: START, HISTORY_START, LEGACY_PHASE, PHASES, PH4, PH5, SUBJ, MILES match verbatim.');
