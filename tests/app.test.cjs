const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');
function boot(initial,failWrite=false,now){
 const memory=new Map(initial?[['kaoyan2028_v1',JSON.stringify(initial)]]:[]),handlers={};
 const nodes=new Map();const node=selector=>{if(!nodes.has(selector))nodes.set(selector,{innerHTML:'',textContent:'',hidden:true,style:{},value:'',dataset:{},classList:{add(){},remove(){},toggle(){}}});return nodes.get(selector)};
 const Clock=now?class extends Date{constructor(...args){super(...(args.length?args:[now]))}static now(){return new Date(now).getTime()}}:Date;
 const context=vm.createContext({CheckinCore:require('../core.js'),console,Date:Clock,JSON,Math,URL,Blob,setTimeout:()=>1,clearTimeout(){},setInterval:()=>1,
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
test('计划从11月2日开始，阶段衔接完整，初试日期设置仍然有效',()=>{
 const a=boot();
 assert.equal(a.run('START'),'2026-11-02');
 assert.equal(a.run("tasksFor('2026-11-01').length"),0);
 assert.match(a.run("tasksFor('2026-11-02').find(x=>x.id==='words').label"),/复习已学词/);
 assert.match(a.run("tasksFor('2026-11-02').find(x=>x.id==='drill').label"),/学习或复习/);
 assert.equal(a.run("PHASES.every((p,i)=>i===0||p.s===addD(PHASES[i-1].e,1))"),true);
 assert.equal(a.run("PHASES[0].s===START && PHASES.at(-1).e==='2027-11-30'"),true);
 assert.equal(a.run("phaseOf('2027-12-01').id"),'p4');
 assert.equal(a.run("PHASES.every(p=>p.gh===1.5) && PH4.gh===1.5"),true);
});
test('旧记录保留原任务、勾选和存储内容，新计划不会标记已学词为打卡',()=>{
 const original=seed();original.days['2026-10-20']={t:{words:true,drill:true},c:[],h:1,sh:{英语:1},rw:{g:'旧记录'},u:2};
 const a=boot(original),before=a.memory.get('kaoyan2028_v1');
 assert.equal(a.run("tasksFor('2026-10-20').find(x=>x.id==='words').label"),'背单词 50-80 个');
 assert.equal(a.run("isDone(dayRec('2026-10-20'),'words')"),true);
 assert.equal(a.run("isDone(dayRec('2026-11-02'),'words')"),false);
 assert.equal(a.memory.get('kaoyan2028_v1'),before);
});
test('日历从新开始日提醒，文字与新的两项最低线一致',()=>{
 const a=boot();const ics=a.run('buildICS()');
 assert.match(ics,/DTSTART;TZID=Asia\/Shanghai:20261102T190000/);
 assert.match(ics,/西综学习或复习至少20分钟/);
 assert.doesNotMatch(ics,/背单词 50-80 个 \+ 西综刷题 10 道/);
});
test('周完成率只计算每日最低线，阶段参考不要求每天完成',()=>{
 const original=seed();original.days['2026-11-02']={t:{words:true,drill:true},c:[],h:1,sh:{},rw:{},u:2};
 const a=boot(original,false,'2026-11-02T12:00:00');
 assert.equal(a.run("weekRate('2026-11-02')"),100);
});
test('开始前没有欠打卡，开始后的首页展示新计划和1.5小时目标',()=>{
 const before=boot(undefined,false,'2026-10-28T12:00:00');
 assert.match(before.run('vToday()'),/计划从 2026-11-02 开始，之前不计欠打卡/);
 assert.equal(before.run("weekStats('2026-10-26').tgt"),0);
 const after=boot(undefined,false,'2026-11-02T12:00:00');
 const page=after.run('vToday()');
 assert.match(page,/分批复习已学词/);
 assert.match(page,/其余是阶段参考/);
 assert.match(page,/日目标 1\.5h/);
});
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
