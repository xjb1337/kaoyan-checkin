/* 可独立测试的数据边界；兼容旧 localStorage 与 JSON 备份。 */
(function(root){
  'use strict';
  const fail=message=>{throw new Error(message)};
  const object=x=>!!x&&typeof x==='object'&&!Array.isArray(x);
  const text=(x,max=5*1024*1024)=>typeof x==='string'&&x.length<=max;
  const number=x=>typeof x==='number'&&Number.isFinite(x)&&x>=0;
  const round=x=>Math.round(x*100)/100;
  function validDate(x){
    if(typeof x!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(x))return false;
    const d=new Date(x+'T12:00:00Z');
    return !Number.isNaN(d.getTime())&&d.toISOString().slice(0,10)===x;
  }
  function safeKeys(value,depth=0){
    if(depth>15)fail('数据嵌套过深');
    if(!value||typeof value!=='object')return;
    Object.keys(value).forEach(k=>{
      if(['__proto__','constructor','prototype'].includes(k))fail('数据包含不安全字段');
      safeKeys(value[k],depth+1);
    });
  }
  function recordMap(x,validator,label){
    if(!object(x))fail(label+'格式错误');
    Object.entries(x).forEach(([k,v])=>{if(!validDate(k)||!validator(v))fail(label+'含无效记录：'+k)});
  }
  const task=x=>object(x)&&text(x.n)&&typeof x.d==='boolean';
  function validDay(r){
    return object(r)&&object(r.t||{})&&Object.values(r.t||{}).every(v=>typeof v==='boolean')&&
      Array.isArray(r.c||[])&&(r.c||[]).every(task)&&number(r.h??0)&&number(r.u??0)&&
      object(r.sh||{})&&Object.entries(r.sh||{}).every(([k,v])=>text(k)&&number(v))&&
      object(r.rw||{})&&['g','s','n'].every(k=>text((r.rw||{})[k]??''))&&text(r.note??'');
  }
  function validWeek(w){
    return object(w)&&Array.isArray(w.must)&&w.must.length===3&&w.must.every(x=>text(x))&&
      Array.isArray(w.md)&&w.md.length===3&&w.md.every(x=>typeof x==='boolean')&&
      Array.isArray(w.ex)&&w.ex.every(task)&&text(w.review??'')&&number(w.u??0);
  }
  function validScore(s){
    return object(s)&&text(s.id)&&validDate(s.d)&&text(s.n??'')&&number(s.tot)&&s.tot<=300&&
      object(s.p||{})&&Object.values(s.p||{}).every(number);
  }
  function normalizeHours(rec){
    const sh={...(rec.sh||{})};const sum=round(Object.values(sh).reduce((a,b)=>a+b,0));
    // 旧版未分类的总时长保留到“其他”；不丢弃旧科目记录。
    if((rec.h||0)>sum)sh['其他']=round((sh['其他']||0)+rec.h-sum);
    return {...rec,sh,h:round(Object.values(sh).reduce((a,b)=>a+b,0))};
  }
  function normalizeState(raw){
    if(!object(raw)||!object(raw.days))fail('不是有效的打卡备份（缺少 days）');
    safeKeys(raw);
    if(raw.schemaVersion!==undefined&&![1,2].includes(raw.schemaVersion))fail('备份版本较新，请先更新应用');
    recordMap(raw.days,validDay,'每日数据');recordMap(raw.weeks||{},validWeek,'周目标');
    const recur=raw.recur??[],scores=raw.scores??[],ch=raw.ch??{},mk=raw.mk??[],settings=raw.settings??{};
    if(!Array.isArray(recur)||!recur.every(x=>text(x)))fail('固定任务格式错误');
    if(!Array.isArray(scores)||!scores.every(validScore))fail('模考成绩格式错误');
    if(!object(ch)||!Object.values(ch).every(x=>Array.isArray(x)&&x.every(v=>typeof v==='boolean'||v===null)))fail('章节格式错误');
    if(!Array.isArray(mk)||!mk.every(validDate))fail('补签日期格式错误');
    if(!object(settings)||!validDate(settings.exam||'2027-12-25'))fail('初试日期格式错误');
    if(settings.backupDays!==undefined&&![7,14,30].includes(settings.backupDays))fail('备份周期格式错误');
    if(raw._recovery!==undefined&&!text(raw._recovery))fail('恢复副本格式错误');
    if(!number(raw.upd??0)||!number(raw.bk??0))fail('备份时间格式错误');
    const days=Object.fromEntries(Object.entries(raw.days).map(([d,r])=>[d,normalizeHours({
      t:{...(r.t||{})},c:(r.c||[]).map(x=>({...x})),h:r.h||0,sh:{...(r.sh||{})},
      rw:{g:r.rw?.g??r.note??'',s:r.rw?.s??'',n:r.rw?.n??''},u:r.u||0
    })]));
    return {schemaVersion:2,days,weeks:JSON.parse(JSON.stringify(raw.weeks||{})),
      recur:[...recur],scores:JSON.parse(JSON.stringify(scores)),ch:JSON.parse(JSON.stringify(ch)),mk:[...mk],
      settings:{exam:settings.exam||'2027-12-25',backupDays:settings.backupDays||7},
      upd:raw.upd||0,bk:raw.bk||0,tip:raw.tip?1:0,...(raw._recovery?{_recovery:raw._recovery}:{})};
  }
  function setHours(rec,subject,value){
    if(!text(subject,200)||!subject||['__proto__','constructor','prototype'].includes(subject))fail('科目名称无效');
    if(!number(value)||value>24)fail('科目时长须在 0–24 小时之间');
    const normalized=normalizeHours(rec),sh={...normalized.sh,[subject]:round(value)};
    const h=round(Object.values(sh).reduce((a,b)=>a+b,0));
    if(h>24)fail('一天的学习时长不能超过 24 小时');
    return {...normalized,sh,h};
  }
  function hasData(s){
    return Object.values(s.days||{}).some(r=>r.h||Object.values(r.t||{}).some(Boolean)||(r.c||[]).length||Object.values(r.rw||{}).some(Boolean))||
      Object.keys(s.weeks||{}).length>0||(s.scores||[]).length>0||Object.keys(s.ch||{}).length>0||(s.recur||[]).length>0;
  }
  function needsBackup(s,now){return hasData(s)&&(!s.bk||(now-s.bk)/864e5>=(s.settings?.backupDays||7))}
  const rollDate=(selected,previous,current)=>selected===previous?current:selected;
  const api={normalizeState,normalizeHours,setHours,hasData,needsBackup,rollDate,validDate};
  if(typeof module==='object'&&module.exports)module.exports=api;
  else root.CheckinCore=api;
})(typeof globalThis==='object'?globalThis:this);
