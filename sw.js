const C='kaoyan-v4';
const ASSETS=['./','./index.html','./manifest.webmanifest','./icon-180.png','./icon-512.png'];
// 安装时用 cache:'reload' 绕过浏览器 HTTP 缓存，确保预缓存的是最新版本
self.addEventListener('install',e=>{
  e.waitUntil(caches.open(C).then(c=>Promise.all(
    ASSETS.map(u=>fetch(u,{cache:'reload'}).then(r=>{if(r.ok)c.put(u,r.clone())}))
  )).then(()=>self.skipWaiting()));
});
self.addEventListener('activate',e=>{
  e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));
});
self.addEventListener('fetch',e=>{
  const u=new URL(e.request.url);
  if(u.pathname.indexOf('/api/')===0)return; // 同步接口不走缓存
  if(e.request.mode==='navigate'){
    // 导航：联网时强制取最新（绕过 HTTP 缓存），断网时回退到缓存
    e.respondWith(fetch(e.request,{cache:'reload'}).then(r=>{
      const cl=r.clone();caches.open(C).then(c=>c.put('./index.html',cl));return r;
    }).catch(()=>caches.match('./index.html').then(r=>r||caches.match('./'))));
  }else{
    e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request).then(r=>{
      if(r.ok&&u.origin===location.origin){const cl=r.clone();caches.open(C).then(c=>c.put(e.request,cl))}
      return r;
    })));
  }
});
