'use strict';
/* 页面与备份交互。共享 index.html 中的状态，不在加载时读写数据。 */
/* ================= 今日 ================= */
function phaseCard(ds){
  const p=ds<START&&(ds>=today()||!dayRec(ds))?PHASES[0]:phaseOf(ds);
  const total=dayDiff(p.e,p.s)+1,passed=clamp(dayDiff(ds,p.s)+1,0,total);
  const pct=Math.round(passed/total*100);
  return `<div class="card">
    <div class="ph"><span class="nm">${esc(p.name)}</span><span class="rg">${p.s} ~ ${p.e}</span></div>
    <div class="phds">${esc(p.desc)} · 已过 ${pct}% · 日目标 ${p.gh}h</div>
    <div class="pgwrap"><div class="pg" style="width:${pct}%"></div></div>
  </div>`;
}
function minlineHTML(ds){
  if(ds<START&&(ds>=today()||!dayRec(ds)))return `<div class="minline info" id="minline">计划从 ${START} 开始，之前不计欠打卡。</div>`;
  const p=phaseOf(ds),mc=minCount(ds),mx=minMax(p),ok=satisfied(ds),mkup=isMakeup(ds);
  const ym=ds.slice(0,7),isPast=ds<today();
  if(mkup)return `<div class="minline ok" id="minline">🩹 已用补签卡保住这天（本月还剩 ${mkLeft(ym)} 张）</div>`;
  if(ok)return `<div class="minline ok" id="minline">✓ 今日保底已完成 · 继续推进，或安心收工</div>`;
  let extra='';
  if(isPast&&ds>=START&&!p.fs&&mkLeft(ym)>0)extra=`<button class="mkbtn" data-act="usemk">🩹 用补签卡（剩${mkLeft(ym)}）</button>`;
  return `<div class="minline" id="minline">⚡ 最低完成线 ${mc}/${mx}　${isPast?'这天还没达标，可以补记':'先完成两项，今天就有进步'}${extra}</div>`;
}
function subjTimeHTML(ds){
  const r=dayRec(ds),sh=(r&&r.sh)||{};
  const ks=Object.keys(sh).filter(k=>sh[k]>0);
  if(!ks.length)return '<span class="tip" style="font-size:12px">点下方科目按钮记录时间，可看时间分布</span>';
  return ks.map(k=>`<span class="pill ok" style="margin-right:5px">${esc(k)} ${sh[k].toFixed(1)}h</span>`).join('');
}
function vToday(){
  const ds=selDate,isToday=ds===today(),future=ds>today();
  const rec=dayRec(ds)||{t:{},c:[],h:0,sh:{},rw:{}};
  const list=tasksFor(ds);
  const doneN=list.filter(x=>isDone(rec,x.id)).length;
  let html=`<div class="daynav">
    <button data-act="dprev">‹</button>
    <input type="date" value="${ds}" data-field="seldate" min="${addD(HISTORY_START,-60)}">
    <button data-act="dnext" ${isToday?'disabled style="opacity:.35"':''}>›</button>
    <button data-act="dnow" ${isToday?'disabled style="opacity:.35"':''}>今天</button>
  </div>`;
  if(!isToday)html+=`<div class="minline info">${future?'未来日期 · 仅供预览':'正在补记 '+ds}，点「今天」返回</div>`;
  const toStart=dayDiff(START,today());
  if(toStart>0)html+=`<div class="minline info" id="startline">🎯 ${START.slice(5).replace('-','/')}（周一）正式开营，还有 ${toStart} 天 · 想提前开始也行</div>`;
  html+=`<div style="display:none" id="bkwarn" class="minline info">💾 记得备份到手机外 · <button class="btn" data-act="tab" data-tab="set">去备份</button></div>`;
  html+=minlineHTML(ds);
  html+=`<div class="card"><h3 id="taskhd">今日任务 ${doneN}/${list.length}${future?'（未来日期，仅供预览）':''}</h3>`;
  if(!list.length&&ds<START)html+='<div class="tip">新计划尚未开始；可以先休息或自行准备学习资料。</div>';
  else if(ds>=START)html+='<div class="tip">两项最低线优先；其余是阶段参考，按每周安排选择，不必每天全部完成。</div>';
  html+=list.map(x=>{
    const on=isDone(rec,x.id);const custom=x.id.indexOf('r:')===0||x.id.indexOf('c:')===0;
    return `<div class="task ${on?'done':''}" role="checkbox" aria-checked="${on}" aria-disabled="${future}" tabindex="${future?-1:0}" data-act="tg" data-id="${esc(x.id)}">
      <span class="ck ${on?'on':''}"></span>
      <span class="lb">${esc(x.label)}</span>
      ${x.min?'<span class="mtag">最低线</span>':''}
      <span class="tchip tg-${({西综:'b',英语:'g',政治:'o',复试:'r',自定义:'p'})[x.tag]||'p'}">${esc(x.tag)}</span>
      ${custom?'<button class="del" data-act="cdel" data-id="'+esc(x.id)+'">✕</button>':''}
    </div>`;
  }).join('');
  html+=`<div class="addrow"><input type="text" id="cnew" maxlength="200" placeholder="加一条临时任务…"><button class="btn" data-act="cadd">添加</button></div></div>`;
  html+=`<div class="card"><h3>学习时长 · 按科目记录</h3>
    <div class="hours-summary"><strong id="hrnum">${(rec.h||0).toFixed(1)}</strong><span>小时 / 日目标 ${goalH(ds)}h</span></div>
    <div id="hour-controls">${hoursHTML(ds)}</div>
    <div class="hint">每次增减 0.5 小时，也可直接修改。总时长自动相加。</div>
    <div id="subjtxt" hidden>${subjTimeHTML(ds)}</div></div>`;
  html+=`<div class="card"><h3>三问复盘 · 写一句也可以</h3>`;
  REVISE.forEach((q,i)=>{
    const k=['g','s','n'][i];
    html+=`<label for="rw-${k}" class="fl" style="margin-top:${i?'8px':'0'}">${esc(q)}</label>
      <input id="rw-${k}" type="text" maxlength="20000" data-field="rw" data-k="${k}" value="${esc((rec.rw||{})[k]||'')}" placeholder="…">`;
  });
  html+='</div>';
  html+=`<details class="card stage-details"><summary>阶段计划与安装帮助</summary>${phaseCard(ds)}
    <p class="tip">安卓 Chrome → ⋮ →「安装应用」或「添加到主屏幕」。安装后在设置中确认离线资源就绪。</p>
    <p class="tip">英语已学约1000词：分批复习，记忆稳定后再增加新词。可参考英语20–30分钟、西综40–60分钟、余下时间做阅读或复盘；每周根据实际情况调整。</p>
    <p class="tip">每天1–2小时覆盖全部科目可能偏紧。请每月结合进度与可投入时间调整；模考和完整套卷可安排较长时段。</p>
    <p class="tip">本机保存不等于云备份。换手机前请导出 JSON，并保存到网盘或发给自己。</p></details>`;
  return html;
}

/* ================= 进度（章节 + 模考） ================= */
function vProg(){
  let html='<div class="card"><h3>计划 vs 实际（按计划线性推进）</h3>';
  let anyCh=false;
  SUBJ.forEach(su=>{
    const dn=chDone(su),ex=expectedCh(su),tot=su.ch.length;
    const diff=dn-ex;
    let cls='ok',txt='正常';
    if(today()<su.s){cls='warn';txt='未开始'}
    else if(diff>=1){cls='ok';txt='领先 '+diff}
    else if(diff<=-1){cls='bad';txt='落后 '+(-diff)}
    const pct=tot?Math.round(dn/tot*100):0;
    anyCh=anyCh||dn>0;
    html+=`<div class="barrow"><span class="bl">${su.name}</span>
      <div class="btk"><div class="bfl" style="width:${pct}%;background:${cls==='bad'?'#ef4444':cls==='warn'?'#d97706':'#16a34a'}"></div></div>
      <span class="bv">${dn}/${tot}</span></div>
      <div class="hint" style="margin:0 0 8px 52px">计划应完成 ${ex} 章 · <span class="pill ${cls}">${txt}</span></div>`;
  });
  if(!anyCh)html+='<div class="tip">还没有勾选章节。下面展开每科，学完一章点一下。</div>';
  html+='</div>';
  html+='<div class="card"><h3>章节进度</h3>';
  SUBJ.forEach(su=>{
    const dn=chDone(su),tot=su.ch.length;
    html+=`<details class="mi"><summary><span class="mi-t">${su.name}</span>
      <span class="mi-d">${dn}/${tot} 章</span><span class="chip">${su.s.slice(5)}~${su.e.slice(5)}</span></summary>`;
    html+=su.ch.map((c,i)=>{
      const on=chList(su.k)[i];
      return `<div class="dchip ${on?'on':''}" data-act="chtg" data-k="${su.k}" data-i="${i}">
        <span class="idx">${i+1}</span><span class="lb">${on?c:esc(c)}</span>
        ${on?'<span class="chip">已学</span>':''}</div>`;
    }).join('');
    html+='</details>';
  });
  html+='</div>';
  /* 模考成绩 */
  html+=`<div class="card"><h3>模考 / 真题套卷成绩</h3>`;
  const sc=(S.scores||[]).slice().sort((a,b)=>(a.d||'').localeCompare(b.d||''));
  if(sc.length){
    html+=scoreChart(sc);
    const last=sc[sc.length-1];
    const pass=last.tot>=200;
    html+=`<div style="margin-top:6px">最近一次：<b>${esc(last.n||'套卷')}</b> ${esc(last.d)} · <b style="font-size:17px">${last.tot}</b> 分
      <span class="pill ${pass?'ok':'bad'}">${pass?'达到 200 分个人参考线':'暂未达到个人参考线，关注后续趋势'}</span></div>`;
    html+=sc.slice().reverse().map(s=>`<div class="scorerow">
      <span class="sn">${esc(s.d)} ${esc(s.n||'')}</span>
      <span class="sv" style="color:${s.tot>=200?'#16a34a':'#ef4444'}">${s.tot}</span>
      <button class="del" data-act="scdel" data-id="${esc(s.id)}">✕</button></div>`).join('');
  }else{
    html+='<div class="tip">还没有记录。做完一套真题就记一次，这里会画出趋势线，并对照 200 分个人参考线。</div>';
  }
  html+=`<div class="formgrid">
    <div><label class="fl">日期</label><input type="date" id="scd" value="${today()}"></div>
    <div><label class="fl">卷子</label><input type="text" id="scn" maxlength="200" placeholder="如 2015年真题"></div>
    <div class="full"><label class="fl">总分（300 分制；参考线不等于录取线）</label><input type="number" id="sct" placeholder="如 215" min="0" max="300"></div>
  </div>
  <details style="margin-top:8px"><summary style="font-size:12.5px;color:var(--sub);cursor:pointer">选填：各科得分</summary>
    <div class="formgrid" style="margin-top:6px">
      <div><label class="fl">生理</label><input type="number" id="sc_phys" placeholder="选填" min="0" max="100"></div>
      <div><label class="fl">生化</label><input type="number" id="sc_bioc" placeholder="选填" min="0" max="100"></div>
      <div><label class="fl">病理</label><input type="number" id="sc_path" placeholder="选填" min="0" max="100"></div>
      <div><label class="fl">内科</label><input type="number" id="sc_med" placeholder="选填" min="0" max="100"></div>
      <div><label class="fl">外科</label><input type="number" id="sc_surg" placeholder="选填" min="0" max="100"></div>
    </div>
  </details>
  <button class="bigbtn" data-act="scadd">保存这次成绩</button></div>`;
  return html;
}
function scoreChart(sc){
  const W=330,H=160,pl=30,pr=14,pt=26,pb=24;
  const maxY=300,minY=100;
  const x=i=>sc.length===1?(pl+(W-pl-pr)/2):pl+i*(W-pl-pr)/(sc.length-1);
  const y=v=>pt+(1-(clamp(v,minY,maxY)-minY)/(maxY-minY))*(H-pt-pb);
  const pts=sc.map((s,i)=>x(i).toFixed(1)+','+y(s.tot).toFixed(1)).join(' ');
  const y200=y(200);
  let g='';
  [100,150,200,250,300].forEach(v=>{g+=`<line x1="${pl}" y1="${y(v).toFixed(1)}" x2="${W-pr}" y2="${y(v).toFixed(1)}" stroke="#e5e7eb" stroke-width="1"/>
    <text x="${pl-5}" y="${(y(v)+3.5).toFixed(1)}" font-size="9" fill="#9ca3af" text-anchor="end">${v}</text>`});
  const dots=sc.map((s,i)=>`<circle cx="${x(i).toFixed(1)}" cy="${y(s.tot).toFixed(1)}" r="3.5" fill="${s.tot>=200?'#16a34a':'#ef4444'}"/>`).join('');
  // 决策线标签放在图表上方左侧，带底色块，绝不与折线/数据点重叠
  const lbl='参考线 200';
  const lw=lbl.length*7.6+10;
  return `<svg class="scorechart" viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg">
    ${g}
    <line x1="${pl}" y1="${y200.toFixed(1)}" x2="${W-pr}" y2="${y200.toFixed(1)}" stroke="#d97706" stroke-width="1.5" stroke-dasharray="5 4"/>
    <rect x="${pl}" y="3" width="${lw.toFixed(0)}" height="14" rx="4" fill="var(--card)"/>
    <text x="${pl+5}" y="13.5" font-size="9.5" fill="#d97706" font-weight="600">${lbl}</text>
    <polyline points="${pts}" fill="none" stroke="#2563eb" stroke-width="2.2" stroke-linejoin="round"/>
    ${dots}
  </svg>`;
}

/* ================= 周目标 ================= */
function weekRate(wk){
  let done=0,total=0;
  for(let i=0;i<7;i++){
    const ds=addD(wk,i);if(ds>today())break;
    const rec=dayRec(ds);const all=tasksFor(ds);
    const list=phaseOf(ds).fs?all:all.filter(x=>x.min);
    total+=list.length;done+=list.filter(x=>isDone(rec,x.id)).length;
  }
  return total?Math.round(done/total*100):0;
}
function weekStats(wk){
  let days=0,h=0,tgt=0,elapsed=0;
  for(let i=0;i<7;i++){
    const ds=addD(wk,i);if(ds>today())break;
    if(ds<START&&!dayRec(ds))continue;
    elapsed++;
    if(satisfied(ds))days++;
    const r=dayRec(ds);h+=(r&&r.h)||0;tgt+=goalH(ds);
  }
  return {days:days,h:h,tgt:tgt,elapsed:elapsed};
}
function weekReportText(wk){
  const we=addD(wk,6),st=weekStats(wk);
  const w=S.weeks[wk]||{};const md=(w.md||[]).filter(Boolean).length;
  const rev=(w.review||'').trim()?'已填':'未填';
  let chs=SUBJ.map(su=>{const d=chDone(su);return d?su.name+' '+d+'/'+su.ch.length+'章':''}).filter(Boolean).join('、');
  const over=st.h>=st.tgt;
  return `【考研周报 ${wk.slice(5).replace('-','/')}~${we.slice(5).replace('-','/')}】\n`
    +`打卡 ${st.days}/${st.elapsed} 天｜时长 ${st.h.toFixed(1)}h（目标 ${st.tgt.toFixed(1)}h，${over?'达标':'欠 '+(st.tgt-st.h).toFixed(1)+'h'}）\n`
    +`周目标完成 ${md}/3｜周日复盘：${rev}\n`
    +(chs?'章节：'+chs:'章节：暂无进度')
    +`\n连续打卡 ${streak()} 天。`;
}
function vWeek(){
  const wk=curWeek,we=addD(wk,6);
  const w=S.weeks[wk]||(S.weeks[wk]={must:['','',''],md:[false,false,false],ex:[],review:'',u:0});
  const isThis=wk===mondayOf(today());
  const st=weekStats(wk),rate=weekRate(wk);
  let html=`<div class="daynav">
    <button data-act="wprev">‹</button>
    <span class="dstr" style="min-width:130px">${wk.slice(5).replace('-','/')} ~ ${we.slice(5).replace('-','/')}</span>
    <button data-act="wnext" ${isThis?'disabled style="opacity:.35"':''}>›</button>
    <button data-act="wnow" ${isThis?'disabled style="opacity:.35"':''}>本周</button>
  </div>`;
  html+=`<div class="card">
    <div class="ph"><span class="nm">本周最低线完成率</span><span class="rg" style="font-size:20px;font-weight:800;color:var(--ac)">${rate}%</span></div>
    <div class="pgwrap"><div class="pg" style="width:${rate}%"></div></div>
    <div class="phds" style="margin-top:8px">打卡达标 ${st.days}/${st.elapsed} 天 · 时长 ${st.h.toFixed(1)}h / 目标 ${st.tgt.toFixed(1)}h</div>
  </div>`;
  html+=`<div class="card"><h3>必须完成（周目标 ×3）</h3>`+
    w.must.map((m,i)=>`<div class="rowline">
      <span class="ck ${w.md[i]?'on':''}" data-act="mtg" data-i="${i}"></span>
      <input type="text" data-field="must" data-i="${i}" value="${esc(m)}" placeholder="目标 ${i+1}，如：生理第 3-4 章网课+章节题">
    </div>`).join('')+
    `<button class="btn" data-act="wcopy" style="margin-top:10px">复制上周目标</button></div>`;
  html+=`<div class="card"><h3>弹性任务</h3>`+
    (w.ex.length?w.ex.map((x,i)=>`<div class="rowline">
      <span class="ck ${x.d?'on':''}" data-act="xtg" data-i="${i}"></span>
      <span class="lb" style="flex:1;${x.d?'color:var(--sub);text-decoration:line-through':''}">${esc(x.n)}</span>
      <button class="del" data-act="xdel" data-i="${i}">✕</button></div>`).join(''):'<div class="tip">本周可做可不做的，放这里</div>')+
    `<div class="addrow"><input type="text" id="xnew" placeholder="加一条弹性任务…"><button class="btn" data-act="xadd">添加</button></div></div>`;
  html+=`<div class="card"><h3>周日复盘（30 分钟）</h3>
    <textarea data-field="review" placeholder="本周完成度如何？没完成的原因？下周怎么调整？">${esc(w.review||'')}</textarea></div>`;
  html+=`<div class="card"><h3>自动周报</h3><div class="rep" id="rep">${esc(weekReportText(wk))}</div>
    <button class="bigbtn ghost" data-act="repcopy">📋 复制周报文字</button></div>`;
  return html;
}

/* ================= 统计 ================= */
function vStats(){
  const t=today();
  let totalDays=0,totalH=0;
  Object.keys(S.days).forEach(k=>{const r=S.days[k];if(anyDone(r))totalDays++;totalH+=(r.h||0)});
  // 科目时间分布
  const stm=subjTime();const stKeys=Object.keys(stm).filter(k=>stm[k]>0);
  const allTime=stKeys.reduce((a,k)=>a+stm[k],0);
  const colors={西综:'#2563eb',英语:'#059669',政治:'#d97706',其他:'#7c3aed'};
  const maxSt=Math.max(1,...stKeys.map(k=>stm[k]));
  let timeBars=stKeys.length?stKeys.sort((a,b)=>stm[b]-stm[a]).map(k=>
    `<div class="barrow"><span class="bl">${esc(k)}</span><div class="btk"><div class="bfl" style="width:${stm[k]/maxSt*100}%;background:${colors[k]||'#7c3aed'}"></div></div>
      <span class="bv">${stm[k].toFixed(1)}h</span></div>`).join(''):'<div class="tip">还没有科目时长记录（在今日页点「+0.5 西综」等按钮）</div>';
  // 近8周时长（带目标线）
  let maxW=1,weeks=[];
  for(let i=7;i>=0;i--){
    const m=addD(mondayOf(t),-7*i);let h=0,g=0;
    for(let j=0;j<7;j++){const ds=addD(m,j);if(ds>today())break;
      const r=dayRec(ds);h+=(r&&r.h)||0;g+=goalH(ds)}
    weeks.push([m,h,g]);maxW=Math.max(maxW,h,g);
  }
  const wbars='<div class="wbar">'+weeks.map(([m,h,g])=>
    `<div class="bw"><div class="bar ${h<g?'under':''}" style="height:${Math.max(2,h/maxW*100)}%"></div>
      <span class="wl">${m.slice(5).replace('-','/')}</span></div>`).join('')+'</div>';
  // 热力图（带月份标签与图例）
  const history=Object.keys(S.days).filter(ds=>ds<START&&ds<=t).sort();
  const beforeStart=t<START&&!history.length;
  const heatStart=history.length?history[0]:START;
  const sM0=mondayOf(heatStart),eM=mondayOf(t);
  const sM=sM0<=eM?sM0:eM;
  const nW=clamp(Math.round(dayDiff(eM,sM)/7)+1,1,90);
  let months='',cols='',lastMon='';
  for(let w=0;w<nW;w++){
    const m=addD(sM,7*w);let col='';
    for(let d=0;d<7;d++){
      const ds=addD(m,d);
      if(ds>today()){col+='<span class="cell" style="opacity:.25"></span>';continue}
      const r=dayRec(ds);let lv=0;
      if(r){const c=tasksFor(ds).filter(x=>isDone(r,x.id)).length;lv=c===0?0:(c<=2?1:(c<=4?2:3))}
      col+=`<span class="cell ${lv?'l'+lv:''}" title="${ds}"></span>`;
    }
    cols+=`<div class="col">${col}</div>`;
    const mon=m.slice(0,7);
    if(mon!==lastMon&&w<nW-1){months+=`<div class="mw">${m.slice(5,7)}月</div>`;lastMon=mon}
    else months+='<div class="mw"></div>';
  }
  const rate=weekRate(mondayOf(t));
  const bkd=S.bk?Math.floor((Date.now()-S.bk)/864e5):null;
  return `<div class="grid2">
    <div class="stat"><div class="v" style="color:var(--ac)">${rate}%</div><div class="k">本周最低线完成率</div></div>
    <div class="stat"><div class="v">🔥 ${streak()}</div><div class="k">连续打卡（天）</div></div>
    <div class="stat"><div class="v">${totalDays}</div><div class="k">累计打卡（天）</div></div>
    <div class="stat"><div class="v" style="color:#059669">${totalH.toFixed(1)} h</div><div class="k">累计学习时长</div></div>
    <div class="stat"><div class="v">🩹 ${mkLeft(t.slice(0,7))}</div><div class="k">本月补签卡剩余</div></div>
    <div class="stat"><div class="v" style="color:#d97706">${dayDiff(examDate(),t)}</div><div class="k">距初试（天）</div></div>
  </div>
  <div class="card" style="margin-top:12px"><h3>本周最低线完成率 ${rate}%</h3>
    <div class="pgwrap"><div class="pg" style="width:${rate}%"></div></div>
    <div class="phds" style="margin-top:8px">上周 ${weekRate(addD(mondayOf(t),-7))}% · 上上周 ${weekRate(addD(mondayOf(t),-14))}%</div></div>
  <div class="card"><h3>科目时间分布（按记录时长）</h3>${timeBars}</div>
  <div class="card"><h3>近 8 周学习时长（橙=未达目标）</h3>${wbars}
    <div class="hint">柱高为实际时长；橙色表示低于该阶段日目标累计值</div></div>
  <div class="card"><h3>打卡热力图（计划全程）</h3>
    ${beforeStart?`<div class="tip">开营（${START}）后开始记录，届时这里会显示每一天的完成热度。</div>`:
    `<div class="heat"><div class="months">${months}</div><div class="cols">${cols}</div></div>
    <div class="legend">少 <span class="cell"></span><span class="cell l1"></span><span class="cell l2"></span><span class="cell l3"></span> 多</div>
    <div class="hint">横向滑动查看 · 从最早显示的记录周到今天</div>`}</div>
  <div class="card"><h3>数据健康</h3><div class="tip">
    上次备份：${bkd===null?'<b style="color:var(--amber)">从未备份</b>':(bkd===0?'今天':bkd+' 天前')}${bkd!==null&&bkd>=(S.settings.backupDays||7)?' <span class="pill warn">该备份了</span>':''}<br>
    累计记录 ${Object.keys(S.days).length} 天 · 模考 ${(S.scores||[]).length} 次 · 章节数据 ${Object.keys(S.ch||{}).length} 科</div></div>`;
}

/* ================= 节点 ================= */
function vMile(){
  const t=today(),ex=examDate();
  const toStart=dayDiff(START,t);
  const pct=clamp(Math.round(dayDiff(t,START)/dayDiff(ex,START)*100),0,100);
  const days=dayDiff(ex,t);
  let html=`<div class="minline info">报名、确认、考试等日期仅供规划，尚未核实为官方时间。请提前查看研招网及报考点公告；导入日历不会自动跟随网页更新。</div><div class="card">
    <div class="ph"><span class="nm">备考全程进度</span><span class="rg">${START} ~ ${ex}</span></div>
    <div class="pgwrap" style="height:10px"><div class="pg" style="width:${pct}%"></div></div>
    <div class="phds" style="margin-top:8px">已走完 ${pct}% · ${days>=0?'距初试还有 '+days+' 天':'初试已结束'}${toStart>0?' · 距开营 '+toStart+' 天':''} · 全程 ${dayDiff(ex,START)} 天</div>
  </div>`;
  const ms=MILES.slice().sort((a,b)=>(a[0]||ex).localeCompare(b[0]||ex));
  html+='<div class="card"><h3>关键节点与检查点</h3>'+ms.map(([d,ti,txt])=>{
    const dd=dayDiff(d||ex,t);
    const chip=dd>0?`还有 ${dd} 天`:(dd===0?'就是今天':'已过 '+(-dd)+' 天');
    return `<details class="mi"><summary>
      <span class="mi-t">${esc(ti)}</span><span class="mi-d">${d||ex}</span>
      <span class="chip ${dd<0?'past':''}">${chip}</span></summary><p>${esc(txt)}</p></details>`;
  }).join('')+'</div>';
  html+=`<div class="card"><h3>日历提醒（推荐）</h3>
    <div class="tip">应用无法主动推送通知，但可以导出日历文件：包含<b>每天 19:00 打卡提醒</b>与<b>${MILES.length} 个关键节点</b>。导入安卓日历后请检查提醒是否生效。</div>
    <button class="bigbtn" data-act="icsdl">📅 导出日历文件（.ics）</button>
    <div class="hint">导入方法：下载后点开文件 → 选择用「日历」打开 → 保存。或在 Google 日历网页版「导入」。</div></div>`;
  html+=`<div class="card"><h3>按实际进度调整</h3><div class="tip">
    ① 平时按每天1–2小时规划，先保证英语与西综两项最低线；<br>
    ② 每月核对章节、英语阅读和可用时间；任务未完成时调整计划，不必堆积补课；<br>
    ③ 总时长偏紧，真题套卷可另找较长时间。结合多次练习、目标院校要求和可投入时间调整目标。</div></div>`;
  return html;
}

/* ================= 设置 ================= */
function needBackup(){return CheckinCore.needsBackup(S,Date.now())}
function hoursHTML(ds){
  const r=CheckinCore.normalizeHours(dayRec(ds)||{h:0,sh:{}});
  const keys=[...new Set(['西综','英语','政治','其他',...Object.keys(r.sh)])];
  const disabled=ds>today()?'disabled':'';
  return keys.map(k=>`<div class="hour-row"><span>${esc(k)}</span>
    <button class="hbtn" data-act="shminus" data-k="${esc(k)}" aria-label="减少${esc(k)}半小时" ${disabled}>−</button>
    <input type="number" min="0" max="24" step="0.5" inputmode="decimal" aria-label="${esc(k)}小时" data-field="hours" data-k="${esc(k)}" value="${r.sh[k]||0}" ${disabled}>
    <button class="hbtn" data-act="shplus" data-k="${esc(k)}" aria-label="增加${esc(k)}半小时" ${disabled}>＋</button></div>`).join('');
}
function updateHours(k,value){
  try{
    const r=CheckinCore.setHours(ensureDay(selDate),k,value);
    S={...S,days:{...S.days,[selDate]:{...r,u:Date.now()}}};
    lightSave();refreshToday();$('#hour-controls').innerHTML=hoursHTML(selDate);
  }catch(e){toast(e.message);$('#hour-controls').innerHTML=hoursHTML(selDate)}
}
function retireTask(index){
  S={...S,recur:S.recur.filter((_,i)=>i!==index)};save(true);
}
function stageBackup(raw){
  try{
    if(raw.length>5*1024*1024)throw new Error('备份超过 5 MB，请检查是否选错文件');
    const data=CheckinCore.normalizeState(JSON.parse(raw));pendingImport={raw,data};
    const dates=Object.keys(data.days).sort();
    $('#import-preview').innerHTML=`<p>已检查：${dates.length} 天记录 · ${data.scores.length} 次模考 · ${Object.keys(data.weeks).length} 周目标<br>${dates.length?dates[0]+' 至 '+dates[dates.length-1]:'没有每日记录'}</p>
      <p class="hint">合并：每日取较新记录，其余设置/章节/成绩按整份备份时间选择。完整恢复：使用该备份替换当前记录。操作前会留一份本机恢复副本。</p>
      <button class="bigbtn" data-act="importmerge">确认合并</button><button class="bigbtn ghost" data-act="importreplace">完整恢复这份备份</button>`;
  }catch(e){pendingImport=null;$('#import-preview').textContent='无法读取：'+e.message}
}
function importBackup(raw,mode='merge'){
  try{
    if(raw.length>5*1024*1024)throw new Error('备份超过 5 MB');
    const data=CheckinCore.normalizeState(JSON.parse(raw));
    const merged=(mode==='replace'||loadError)?data:mergeState(S,data);
    const candidate=CheckinCore.normalizeState({...merged,upd:Date.now(),_recovery:recoveryRaw||backupJSON(S)});
    // 主记录和恢复副本放入同一个键，一次 setItem 原子提交；配额失败不改动两者。
    localStorage.setItem(LS_KEY,JSON.stringify(candidate));
    S=candidate;loadError='';recoveryRaw=null;pendingImport=null;storageStatus('');syncPush();render();toast('恢复成功，原记录已留本机副本');return true;
  }catch(e){toast('未导入：'+e.message);return false}
}
function recoveryBackup(){
  if(S._recovery)return S._recovery;
  if(recoveryRaw){
    try{const raw=JSON.parse(recoveryRaw);if(typeof raw?._recovery==='string')return raw._recovery}catch(e){/* 非 JSON 原始存档只能先导出人工修复。 */}
  }
  return localStorage.getItem(LS_KEY+'_before_import');
}
function backupJSON(state){const {_recovery,...data}=state;return JSON.stringify(data)}
function exportBackup(){
  try{download('kaoyan-checkin-'+today()+'.json',backupJSON(S),'application/json');toast('已请求下载，请确认文件已保存');showBackupConfirm()}
  catch(e){toast('下载未启动，请尝试复制备份')}
}
function showBackupConfirm(){
  const panel=$('#backup-confirm')||$('#backup-confirm-global');panel.hidden=false;
}
function confirmBackup(){$('#backup-confirm-global').hidden=true;S={...S,bk:Date.now()};save(true);toast('已记录备份时间，请另存到手机外')}
function vSet(){
  const bkd=S.bk?Math.floor((Date.now()-S.bk)/864e5):null;
  let html=`<div class="card"><h3>初试日期</h3>
    <input type="date" data-field="exam" value="${examDate()}">
    <div class="hint">默认 2027-12-25 仅为规划占位，并非官方确认日期。请以正式公告为准。修改会影响倒计时与冲刺区间。</div></div>`;
  html+=`<div class="card"><h3>固定每日任务（自定义）</h3>`+
    (S.recur.length?S.recur.map((n,i)=>`<div class="rowline"><span class="lb" style="flex:1">${esc(n)}</span>
      <button class="del" data-act="rdel" data-i="${i}">停用</button></div>`).join(''):'<div class="tip">没有固定任务。适合放"跟导师课题推进"这类长期项。</div>')+
    `<div class="addrow"><input type="text" id="rnew" maxlength="200" placeholder="任务名…"><button class="btn" data-act="radd">加入每天</button></div></div>`;
  html+=`<div class="card"><h3>补签卡</h3><div class="tip">
    每月 2 张。某天漏了最低线，可在「今日」页切到那天点「🩹 用补签卡」，连胜即可保住。<br>
    本月已用 ${mkUsedIn(today().slice(0,7))} 张，剩余 <b>${mkLeft(today().slice(0,7))}</b> 张。
    ${(S.mk||[]).length?'<br>已使用：'+S.mk.slice(-6).join('、'):''}</div></div>`;
  html+=`<div class="card"><h3>备份与恢复</h3>
    <div class="tip" style="margin-bottom:8px">数据存在本机浏览器。上次备份：${bkd===null?'<b style="color:var(--amber)">从未备份</b>':(bkd===0?'今天':bkd+' 天前')}。
    ${needBackup()?'<b style="color:var(--amber)">建议现在备份一次。</b>':''}</div>
    <button class="bigbtn ghost" data-act="expdl">⬇️ 导出备份（下载 JSON）</button>
    <button class="bigbtn ghost" data-act="expcp">📋 复制备份到剪贴板</button>
    <div id="backup-confirm" class="minline info" hidden><span>请先确认 JSON 已下载或复制，并保存到网盘等手机外位置。</span><button class="btn" data-act="backupok">已保存备份</button></div>
    <label class="fl" for="backup-days" style="margin-top:14px">备份提醒周期</label>
    <select id="backup-days" data-field="backupdays">${[7,14,30].map(n=>`<option value="${n}" ${S.settings.backupDays===n?'selected':''}>${n} 天</option>`).join('')}</select>
    <label class="fl" for="backup-file" style="margin-top:18px">从 JSON 文件恢复</label>
    <input id="backup-file" type="file" accept=".json,application/json">
    <details style="margin-top:12px"><summary>也可以粘贴备份文本</summary><textarea id="impt" aria-label="备份 JSON" placeholder="把备份 JSON 粘贴到这里…" style="margin-top:10px"></textarea>
    <button class="bigbtn ghost" data-act="impdo">检查备份内容</button></details>
    <div id="import-preview" role="status"></div>
    <button class="bigbtn ghost" data-act="restoreprevious">恢复上次导入前的副本</button>
    <details style="margin-top:18px"><summary>危险操作</summary><button class="bigbtn danger" data-act="wipe">清空全部数据</button></details></div>`;
  html+=`<div class="card"><h3>同步状态</h3><div class="tip">
    ${syncOn?'🟢 已连接同源同步服务，多设备按天合并。':'⚪ 独立模式：数据完全保存在本机浏览器，不依赖任何电脑。'}<br><br>
    <b>手机安装（安卓）：</b>Chrome → ⋮ → 「添加到主屏幕」或「安装应用」，可全屏运行且断网可用。<br><br>
    <b>数据说明：</b>离线状态：<span id="offline-status">${offlineReady?'资源已就绪，可尝试飞行模式重新打开验证':'尚未确认，请联网稍候或重新打开'}</span>。<br>本机副本仍在同一浏览器里，不是异地备份；换手机或清浏览器数据前务必导出。<br><br>版本：2026.10 · 11月2日新计划</div></div>`;
  return html;
}
