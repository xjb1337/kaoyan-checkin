const CACHE_PREFIX = 'kaoyan-v';
const C = `${CACHE_PREFIX}6`;
const ASSETS = ['./', './index.html', './core.js', './views.js', './manifest.webmanifest', './icon-180.png', './icon-512.png'];
const API_URL = new URL('./api', self.registration.scope);

// 任一资源下载或写入失败都会阻止安装；旧版本仅在新版本激活后清理。
self.addEventListener('install', event => {
  event.waitUntil(caches.open(C).then(cache => Promise.all(
    ASSETS.map(async url => {
      const response = await fetch(url, { cache: 'reload' });
      if (!response.ok) throw new Error(`Precache failed: ${url} (${response.status})`);
      await cache.put(url, response);
    })
  )));
});

self.addEventListener('message', event => {
  if (event.data?.type === 'SKIP_WAITING') {
    event.waitUntil(self.skipWaiting());
  }
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(
    keys.filter(key => key.startsWith(CACHE_PREFIX) && key !== C)
      .map(key => caches.delete(key))
  )).then(() => self.clients.claim()));
});

function shellUnavailable() {
  return new Response('App cache is incomplete. Please reconnect and update the app.', {
    status: 503,
    headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function navigate(event) {
  // App shell 只在新版 SW 安装时写入；导航不得绕过 waiting 更新确认。
  event.respondWith(caches.open(C).then(async cache => {
    return await cache.match('./index.html') || await cache.match('./') || shellUnavailable();
  }));
}

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET') return;
  if (url.origin === API_URL.origin &&
      (url.pathname === API_URL.pathname || url.pathname.startsWith(`${API_URL.pathname}/`))) return;

  if (request.mode === 'navigate') {
    navigate(event);
    return;
  }

  // 预缓存资源固定在当前版本，包括带查询参数的引用。
  const asset = ASSETS.find(path => {
    const pinned = new URL(path, self.registration.scope);
    return url.origin === pinned.origin && url.pathname === pinned.pathname;
  });
  if (asset) {
    event.respondWith(caches.open(C).then(async cache => {
      return await cache.match(asset) || shellUnavailable();
    }));
    return;
  }

  // 非 app shell 资源正常联网，不把其它版本资源补写进固定版本缓存。
  event.respondWith(fetch(request));
});
