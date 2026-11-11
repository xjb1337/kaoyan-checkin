const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const planPath = path.resolve(__dirname, '../app/src/main/assets/plan.json');
const plan = JSON.parse(fs.readFileSync(planPath, 'utf8'));

const requiredKeys = [
  'START',
  'HISTORY_START',
  'LEGACY_PHASE',
  'PHASES',
  'PH4',
  'PH5',
  'SUBJ',
  'MILES',
];

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const isDate = (value) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);

function checkText(value, label) {
  assert.equal(typeof value, 'string', `${label} 必须是字符串`);
  assert.ok(value.length > 0, `${label} 不能为空`);
}

function checkDate(value, label) {
  assert.ok(isDate(value), `${label} 必须是 YYYY-MM-DD 格式`);
}

function checkTasks(value, label) {
  assert.ok(Array.isArray(value), `${label} 必须是数组`);
  assert.ok(value.length > 0, `${label} 不能为空`);
  value.forEach((task, index) => {
    assert.ok(isObject(task), `${label}[${index}] 必须是对象`);
    checkText(task.id, `${label}[${index}].id`);
    checkText(task.label, `${label}[${index}].label`);
    checkText(task.tag, `${label}[${index}].tag`);
  });
}

function checkPhase(value, label, requireRange = true) {
  assert.ok(isObject(value), `${label} 必须是对象`);
  checkText(value.id, `${label}.id`);
  checkText(value.name, `${label}.name`);
  checkText(value.desc, `${label}.desc`);
  assert.equal(typeof value.gh, 'number', `${label}.gh 必须是数字`);
  if (requireRange) {
    checkDate(value.s, `${label}.s`);
    checkDate(value.e, `${label}.e`);
  }
  checkTasks(value.tasks, `${label}.tasks`);
}

assert.ok(isObject(plan), 'plan.json 顶层必须是对象');
requiredKeys.forEach((key) => assert.ok(Object.hasOwn(plan, key), `缺少 ${key}`));

checkDate(plan.START, 'START');
checkDate(plan.HISTORY_START, 'HISTORY_START');

checkPhase(plan.LEGACY_PHASE, 'LEGACY_PHASE');

assert.ok(Array.isArray(plan.PHASES), 'PHASES 必须是数组');
assert.ok(plan.PHASES.length > 0, 'PHASES 不能为空');
plan.PHASES.forEach((phase, index) => checkPhase(phase, `PHASES[${index}]`));

checkPhase(plan.PH4, 'PH4', false);
checkPhase(plan.PH5, 'PH5', false);
assert.equal(typeof plan.PH5.fs, 'number', 'PH5.fs 必须是数字');

assert.ok(Array.isArray(plan.SUBJ), 'SUBJ 必须是数组');
assert.ok(plan.SUBJ.length > 0, 'SUBJ 不能为空');
plan.SUBJ.forEach((subject, index) => {
  const label = `SUBJ[${index}]`;
  assert.ok(isObject(subject), `${label} 必须是对象`);
  checkText(subject.k, `${label}.k`);
  checkText(subject.name, `${label}.name`);
  checkDate(subject.s, `${label}.s`);
  checkDate(subject.e, `${label}.e`);
  assert.ok(Array.isArray(subject.ch), `${label}.ch 必须是数组`);
  subject.ch.forEach((chapter, chapterIndex) => checkText(chapter, `${label}.ch[${chapterIndex}]`));
});

assert.ok(Array.isArray(plan.MILES), 'MILES 必须是数组');
plan.MILES.forEach((milestone, index) => {
  const label = `MILES[${index}]`;
  assert.ok(Array.isArray(milestone), `${label} 必须是数组`);
  assert.ok(milestone.length >= 3, `${label} 至少需要日期、标题、说明三项`);
  if (milestone[0] !== '') checkDate(milestone[0], `${label}[0]`);
  checkText(milestone[1], `${label}[1]`);
  checkText(milestone[2], `${label}[2]`);
});

console.log(
  'plan.json 校验通过：START、HISTORY_START、LEGACY_PHASE、PHASES、PH4、PH5、SUBJ、MILES 结构正常。',
);
