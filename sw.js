const C='kaoyan-v1';
const ASSETS=['./','./index.html','./manifest.webmanifest','./icon-180.png','./icon-512.png'];
self.addEventListener('install',e=>{
  e.waitUntil(caches.open(C).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting()));
});
self.addEventListener('activate',e=>{
  e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));
});
self.addEventListener('fetch',e=>{
  const u=new URL(e.request.url);
  if(u.pathname.indexOf('/api/')===0)return; // 同步接口不走缓存
  if(e.request.mode==='navigate'){
    e.respondWith(fetch(e.request).then(r=>{
      const cl=r.clone();caches.open(C).then(c=>c.put('./index.html',cl));return r;
    }).catch(()=>caches.match('./index.html')));
  }else{
    e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request).then(r=>{
      if(r.ok&&u.origin===location.origin){const cl=r.clone();caches.open(C).then(c=>c.put(e.request,cl))}
      return r;
    })));
  }
});
