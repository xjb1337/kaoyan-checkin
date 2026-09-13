const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');
function boot(initial,failWrite=false){
 const memory=new Map(initial?[['kaoyan2028_v1',JSON.stringify(initial)]]:[]),handlers={};
 const nodes=new Map();const node=selector=>{if(!nodes.has(selector))nodes.set(selector,{innerHTML:'',textContent:'',hidden:true,style:{},value:'',dataset:{},classList:{add(){},remove(){},toggle(){}}});return nodes.get(selector)};
 const context=vm.createContext({CheckinCore:require('../core.js'),console,Date,JSON,Math,URL,Blob,setTimeout:()=>1,clearTimeout(){},setInterval:()=>1,
 navigator:{},location:{protocol:'file:',hostname:'localhost'},confirm:()=>true,
 localStorage:{getItem:k=>memory.get(k)||null,setItem(k,v){if(failWrite)throw new Error('quota');memory.set(k,v)}},
 document:{querySelector:node,querySelectorAll:()=>[],addEventListener:(k,fn)=>{(handlers[k]??=[]).push(fn)},visibilityState:'visible'},
 window:{addEventListener(){}},fetch:()=>{throw new Error('unexpected network')}});
 const html=fs.readFileSync(require('node:path').join(__dirname,'../index.html'),'utf8');
 const source=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].map(x=>x[1]).join('\n').split('/* ================= 启动 ================= */')[0];
 vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'../views.js'),'utf8')+'\n'+source,context);vm.runInContext("render=()=>{};refreshToday=()=>{};syncPush=()=>{};toast=m=>globalThis.lastToast=m;download=()=>{}",context);
 return {context,memory,node,run:code=>vm.runInContext(code,context),click:act=>handlers.click[0]({target:{closest:()=>({dataset:{act}})}})};
}
const seed=()=>({days:{'2026-09-13':{t:{words:true},c:[{n:'阅读',d:true}],h:1,sh:{英语:1},rw:{g:'记住我'},u:1}},weeks:{},recur:['阅读'],scores:[],ch:{},mk:[],settings:{exam:'2027-12-25'},upd:1,bk:0});
test('存储失败可见，不能宣称已保存',()=>{const a=boot(undefined,true);assert.equal(a.run('persist()'),false);assert.equal(a.node('#storage-alert').hidden,false)});
test('损坏的本地记录不自动覆盖',()=>{const a=boot({days:[]});a.run('persist()');assert.equal(a.memory.get('kaoyan2028_v1'),'{"days":[]}');assert.equal(a.node('#storage-alert').hidden,false)});
test('导出日历不设置备份时间',()=>{const a=boot(seed());a.click('icsdl');assert.equal(a.run('S.bk'),0)});
test('停用固定任务不删除历史记录',()=>{const a=boot(seed());a.run("retireTask(0)");assert.equal(a.run('S.recur.length'),0);assert.equal(a.run("S.days['2026-09-13'].c[0].n"),'阅读')});
test('未来日期无法写入打卡和时长',()=>{const a=boot();a.run("selDate='2099-01-01'");a.click('tg');assert.equal(a.run("S.days['2099-01-01']"),undefined)});
test('9月不展示12月才开始的长难句，12月正常展示',()=>{const a=boot();assert.equal(a.run("tasksFor('2026-09-14').some(x=>x.id==='sent')"),false);assert.equal(a.run("tasksFor('2026-12-01').some(x=>x.id==='sent2')"),true)});
test('合法导入先留恢复副本；错误导入不修改状态',()=>{
 const a=boot(seed());const old=a.run('JSON.stringify(S)');a.context.raw=JSON.stringify({...seed(),upd:2});
 assert.equal(a.run('importBackup(raw)'),true);assert.equal(JSON.parse(a.memory.get('kaoyan2028_v1'))._recovery,old);
 const current=a.run('JSON.stringify(S)');a.context.raw='{"days":[],"upd":999}';assert.equal(a.run('importBackup(raw)'),false);assert.equal(a.run('JSON.stringify(S)'),current);
});
test('存储失败时导入不替换内存记录',()=>{const a=boot(seed(),true);const old=a.run('JSON.stringify(S)');a.context.raw=JSON.stringify({...seed(),upd:99});assert.equal(a.run('importBackup(raw)'),false);assert.equal(a.run('JSON.stringify(S)'),old)});

test('导入单次原子写入同时保存状态与副本，失败保留上次恢复点',()=>{
 const initial=seed();initial._recovery=JSON.stringify({days:{},upd:5});
 const a=boot(initial,true),old=a.memory.get('kaoyan2028_v1');a.context.raw=JSON.stringify(seed());
 assert.equal(a.run('importBackup(raw)'),false);assert.equal(a.memory.get('kaoyan2028_v1'),old);
});
test('主记录结构损坏时仍能提取完好的内嵌恢复副本',()=>{
 const snapshot=JSON.stringify(seed()),a=boot({days:[],_recovery:snapshot});
 assert.equal(a.run('recoveryBackup()'),snapshot);
 assert.equal(a.run("importBackup(recoveryBackup(),'replace')"),true);
 assert.equal(a.run("S.days['2026-09-13'].rw.g"),'记住我');
});
