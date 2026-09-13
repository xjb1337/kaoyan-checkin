const {test}=require('node:test');
const assert=require('node:assert/strict');
const C=require('../core.js');
const base=()=>({days:{},weeks:{},recur:[],scores:[],ch:{},mk:[],settings:{exam:'2027-12-25'},upd:0,bk:0,tip:0});
test('旧记录迁移保留复盘、未分类时长且不修改输入',()=>{
 const raw=base();raw.days['2026-09-13']={t:{words:true},c:[],h:2,sh:{英语:.5},note:'收获',u:1};
 const out=C.normalizeState(raw);
 assert.equal(out.days['2026-09-13'].rw.g,'收获');assert.equal(out.days['2026-09-13'].sh['其他'],1.5);
 assert.equal(out.settings.backupDays,7);assert.equal(raw.days['2026-09-13'].note,'收获');
});
test('保留旧版本科目时长超过总时长的记录',()=>{assert.equal(C.normalizeHours({h:0,sh:{西综:2}}).h,2)});
test('按科目增减与直接编辑保持总时长一致',()=>{
 const a={h:0,sh:{}};const b=C.setHours(a,'西综',.5);assert.equal(b.h,.5);assert.equal(a.h,0);
 const c=C.setHours(b,'英语',1);assert.equal(c.h,1.5);
 assert.equal(C.setHours(c,'西综',0).h,1);
 assert.throws(()=>C.setHours(c,'政治',24));assert.throws(()=>C.setHours(c,'英语',-1));assert.throws(()=>C.setHours(c,'英语',NaN));
});
test('严格验证导入，拒绝错误结构、日期、数值和危险键',()=>{
 for(const bad of [[],{},null,{...base(),days:[]},{...base(),recur:'bad'},
 {...base(),days:{'2026-02-30':{}}},{...base(),ch:{phys:[1]}},
 {...base(),scores:[{id:'1',d:'2026-09-13',n:'',tot:301,p:{}}]},
 JSON.parse('{"days":{},"__proto__":{"polluted":true}}')]) assert.throws(()=>C.normalizeState(bad));
 assert.equal({}.polluted,undefined);
});
test('基础备份和旧周目标兼容',()=>{
 const x=base();x.weeks['2026-09-07']={must:['a','',''],md:[true,false,false],ex:[{n:'b',d:false}],review:'周复盘',u:1};
 assert.equal(C.normalizeState(x).weeks['2026-09-07'].review,'周复盘');
 assert.equal(C.normalizeState(base()).schemaVersion,2);
});
test('备份提醒包含章节、周目标，支持自定义周期',()=>{
 const x=base();assert.equal(C.needsBackup(x,10*864e5),false);x.ch={phys:[true]};assert.equal(C.needsBackup(x,10*864e5),true);
 x.bk=4*864e5;x.settings.backupDays=7;assert.equal(C.needsBackup(x,10*864e5),false);assert.equal(C.needsBackup(x,12*864e5),true);
});
test('跨天只自动跟随原本的今天；历史不变',()=>{
 assert.equal(C.rollDate('2026-09-13','2026-09-13','2026-09-14'),'2026-09-14');
 assert.equal(C.rollDate('2026-09-12','2026-09-13','2026-09-14'),'2026-09-12');
});
test('旧界面生成的长文本与超范围分科成绩不锁死整个存档',()=>{
 const s=base();s.recur=['a'.repeat(500)];s.scores=[{id:'s1',d:'2026-09-13',n:'b'.repeat(500),tot:200,p:{phys:301}}];
 s.weeks['2026-09-07']={must:['x'.repeat(3000),'',''],md:[false,false,false],ex:[{n:'x'.repeat(500),d:false}],review:'x'.repeat(21000),u:1};
 const out=C.normalizeState(s);assert.equal(out.scores[0].p.phys,301);assert.equal(out.weeks['2026-09-07'].review.length,21000);
});
test('完整记录和所有可选字段正确复制，不共享引用',()=>{
 const x=base();x.schemaVersion=1;x.tip=1;x.bk=2;x.settings.backupDays=14;x._recovery='{"days":{}}';
 x.days['2026-09-13']={t:{},c:[],h:0,sh:{},rw:{g:'',s:'难点',n:'明天'},u:1};
 x.ch={phys:[true,null,false]};x.mk=['2026-09-12'];
 const y=C.normalizeState(x);assert.equal(y.tip,1);assert.equal(y._recovery,x._recovery);assert.equal(y.settings.backupDays,14);
 y.ch.phys[0]=false;assert.equal(x.ch.phys[0],true);
});
test('各种非法数据和边界都给出错误而非污染状态',()=>{
 for(const patch of [{schemaVersion:99},{weeks:[]},{weeks:{no:{}}},{recur:[1]},{scores:{}},{ch:[]},{mk:{}},{mk:['bad']},{settings:[]},{settings:{exam:'bad'}},{settings:{backupDays:0}},{bk:-1},{upd:'1'},{_recovery:12},
 {days:{'2026-09-13':{t:{words:1}}}},{days:{'2026-09-13':{c:[{n:'x',d:1}]}}},{days:{'2026-09-13':{h:-1}}},
 {days:{'2026-09-13':{sh:{英语:Infinity}}}},{days:{'2026-09-13':{rw:{s:1}}}}])assert.throws(()=>C.normalizeState({...base(),...patch}));
 let deep={};for(let i=0;i<18;i++)deep={child:deep};assert.throws(()=>C.normalizeState({...base(),extra:deep}));
 for(const d of [null,123,'2026-2-01','2026-13-01','2026-02-29'])assert.equal(C.validDate(d),false);
 assert.equal(C.validDate('2028-02-29'),true);
 for(const k of ['',null,'constructor'])assert.throws(()=>C.setHours({},k,0));
});
test('所有类型的真实学习数据会触发备份，空占位日期不会',()=>{
 assert.equal(C.hasData({}),false);assert.equal(C.hasData({days:{d:{t:{words:false},c:[],rw:{}}}}),false);
 for(const patch of [{days:{d:{h:1}}},{days:{d:{t:{words:true}}}},{days:{d:{c:[{n:'x',d:false}]}}},{days:{d:{rw:{g:'收获'}}}},{weeks:{w:{}}},{scores:[{}]},{ch:{phys:[]}},{recur:['阅读']}])assert.equal(C.hasData(patch),true);
});
