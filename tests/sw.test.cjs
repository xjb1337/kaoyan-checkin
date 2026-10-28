const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const SCOPE = 'https://example.github.io/kaoyan-checkin/';
const CURRENT = 'kaoyan-v6';
const ASSETS = ['./', './index.html', './core.js', './views.js', './manifest.webmanifest', './icon-180.png', './icon-512.png'];
const html = (body = 'new') => new Response(body, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
function deferred() {
  let resolve;
  const promise = new Promise(yes => { resolve = yes; });
  return { promise, resolve };
}
function harness({ fetch = async () => html(), put = async () => {}, stores = new Map(), version = '6' } = {}) {
  const listeners = new Map();
  const calls = { fetch: [], put: [], skipWaiting: 0, claim: 0 };
  const key = value => new URL(typeof value === 'string' ? value : value.url, SCOPE).href;
  const open = async name => {
    if (!stores.has(name)) stores.set(name, new Map());
    const store = stores.get(name);
    return {
      match: async request => store.get(key(request))?.clone(),
      put: async (request, response) => {
        calls.put.push([name, key(request)]);
        await put(request, response);
        store.set(key(request), response.clone());
      },
    };
  };
  const caches = {
    open, keys: async () => [...stores.keys()], delete: async name => stores.delete(name),
    match: async request => {
      for (const name of stores.keys()) {
        const response = await (await open(name)).match(request);
        if (response) return response;
      }
    },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../sw.js'), 'utf8').replace('${CACHE_PREFIX}6', '${CACHE_PREFIX}' + version), {
    URL, Response, Request, location: new URL(`${SCOPE}sw.js`), caches,
    fetch: (...args) => { calls.fetch.push(args); return fetch(...args); },
    self: {
      registration: { scope: SCOPE }, location: new URL(`${SCOPE}sw.js`),
      addEventListener: (name, handler) => listeners.set(name, handler),
      skipWaiting: async () => { calls.skipWaiting++; },
      clients: { claim: async () => { calls.claim++; } },
    },
  });
  function dispatch(type, properties = {}) {
    const waits = [];
    let response;
    listeners.get(type)?.({ ...properties,
      waitUntil: promise => waits.push(Promise.resolve(promise)),
      respondWith: promise => { response = Promise.resolve(promise); },
    });
    return { response, done: Promise.all(waits) };
  }
  const seed = async (name, url, response) => {
    await open(name);
    stores.get(name).set(key(url), response);
  };
  const navigate = () => dispatch('fetch', { request: { url: SCOPE, method: 'GET', mode: 'navigate' } });
  const cached = async (url = './index.html') => (await (await open(CURRENT)).match(url))?.text();
  return { calls, caches, stores, dispatch, seed, navigate, cached };
}

test('install waits for all writes, preserves asset list, and does not skip waiting', async () => {
  const gate = deferred();
  const h = harness({ put: () => gate.promise });
  let settled = false;
  const install = h.dispatch('install').done.then(() => { settled = true; });
  await new Promise(resolve => setImmediate(resolve));
  const premature = settled;
  gate.resolve();
  await install;
  assert.equal(premature, false);
  assert.equal(h.calls.put.length, ASSETS.length);
  assert.equal(h.calls.skipWaiting, 0);
  assert.deepEqual(h.calls.fetch.map(([url]) => url), ASSETS);
  assert.ok(h.calls.fetch.every(([, options]) => options.cache === 'reload'));
  assert.equal(h.stores.get(CURRENT).size, ASSETS.length);
});
for (const failure of ['404', 'network', 'write']) {
  test(`failed install (${failure}) preserves previous app version`, async () => {
    const h = harness({
      fetch: async url => {
        if (url === './icon-512.png') {
          if (failure === '404') return new Response('missing', { status: 404 });
          if (failure === 'network') throw new Error('offline');
        }
        return html();
      },
      put: async url => { if (failure === 'write' && url === './icon-512.png') throw new Error('quota'); },
    });
    await h.seed('kaoyan-v5', './index.html', html('old'));
    await assert.rejects(h.dispatch('install').done);
    assert.equal(await (await (await h.caches.open('kaoyan-v5')).match('./index.html')).text(), 'old');
    assert.equal(h.calls.skipWaiting, 0);
  });
}
test('activate removes only obsolete app caches', async () => {
  const h = harness();
  for (const name of ['kaoyan-v3', 'kaoyan-v4', 'kaoyan-v5', CURRENT, 'other-app-v1', 'kaoyan-notes-v1']) await h.caches.open(name);
  await h.dispatch('activate').done;
  assert.deepEqual([...h.stores.keys()], [CURRENT, 'other-app-v1', 'kaoyan-notes-v1']);
  assert.equal(h.calls.claim, 1);
});
test('cached navigation never fetches or overwrites the pinned HTML', async () => {
  const h = harness({ fetch: async () => html('HTML-B') });
  await h.seed(CURRENT, './index.html', html('HTML-A'));
  for (let i = 0; i < 2; i++) {
    const event = h.navigate();
    assert.equal(await (await event.response).text(), 'HTML-A');
    await event.done;
  }
  assert.equal(await h.cached(), 'HTML-A');
  assert.equal(h.calls.fetch.length, 0);
  assert.equal(h.calls.put.length, 0);
});

test('missing pinned HTML fails closed rather than mixing network HTML with cached core', async () => {
  const h = harness({ fetch: async () => html('HTML-B') });
  await h.seed(CURRENT, './core.js', new Response('Core-A'));
  const event = h.navigate();
  assert.equal((await event.response).status, 503);
  await event.done;
  assert.equal(h.calls.fetch.length, 0);
  assert.equal(h.calls.put.length, 0);
});

test('navigation falls back to precached root', async () => {
  const h = harness({ fetch: async () => { throw new Error('offline'); } });
  await h.seed(CURRENT, './', html('root'));
  const event = h.navigate();
  assert.equal(await (await event.response).text(), 'root');
  await event.done;
});
test('non-GET and scoped API requests are not intercepted', async () => {
  const h = harness();
  for (const [url, method] of [[SCOPE, 'POST'], [SCOPE, 'HEAD'], [`${SCOPE}api/sync`, 'GET'], [`${SCOPE}api/`, 'GET'], [`${SCOPE}api`, 'GET']]) {
    assert.equal(h.dispatch('fetch', { request: { url, method, mode: 'navigate' } }).response, undefined);
  }
  assert.equal(h.calls.fetch.length, 0);
});
test('static hits use only this app version', async () => {
  const h = harness();
  await h.seed('other-app-v1', './icon-180.png', new Response('foreign'));
  await h.seed(CURRENT, './icon-180.png', new Response('own'));
  const event = h.dispatch('fetch', { request: { url: `${SCOPE}icon-180.png`, method: 'GET', mode: 'cors' } });
  assert.equal(await (await event.response).text(), 'own');
  await event.done;
});
test('only explicit SKIP_WAITING message activates waiting worker', async () => {
  const h = harness();
  for (const data of [undefined, null, {}, { type: 'OTHER' }]) await h.dispatch('message', { data }).done;
  assert.equal(h.calls.skipWaiting, 0);
  await h.dispatch('message', { data: { type: 'SKIP_WAITING' } }).done;
  assert.equal(h.calls.skipWaiting, 1);
});

test('missing core.js prevents installation', async () => {
  const h = harness({ fetch: async url => url === './core.js' ? new Response('missing', { status: 404 }) : html() });
  await h.seed('kaoyan-v4', './index.html', html('old'));
  await assert.rejects(h.dispatch('install').done);
  assert.ok(h.stores.has('kaoyan-v4'));
  assert.equal(h.calls.skipWaiting, 0);
});

test('missing pinned core fails closed instead of downloading a different version', async () => {
  const h = harness({ fetch: async () => new Response('Core-B') });
  await h.seed(CURRENT, './index.html', html('HTML-A'));
  const event = h.dispatch('fetch', { request: { url: `${SCOPE}core.js`, method: 'GET', mode: 'cors' } });
  assert.equal((await event.response).status, 503);
  await event.done;
  assert.equal(h.calls.fetch.length, 0);
});

async function assertPair(worker, version) {
  const navigation = worker.navigate();
  assert.equal(await (await navigation.response).text(), `HTML-${version}`);
  await navigation.done;
  const core = worker.dispatch('fetch', { request: { url: `${SCOPE}core.js`, method: 'GET', mode: 'cors' } });
  assert.equal(await (await core.response).text(), `Core-${version}`);
  await core.done;
}

for (const failed of [false, true]) {
  test(`HTML/Core stay paired while next installation ${failed ? 'fails' : 'waits for confirmation'}`, async () => {
    const stores = new Map();
    const active = harness({ stores, fetch: async () => html('HTML-B') });
    await active.seed(CURRENT, './index.html', html('HTML-A'));
    await active.seed(CURRENT, './core.js', new Response('Core-A'));
    const waiting = harness({ stores, version: '7', fetch: async url => {
      if (url === './core.js') return new Response('Core-B', { status: failed ? 404 : 200 });
      return html('HTML-B');
    } });
    const installation = waiting.dispatch('install').done;
    if (failed) await assert.rejects(installation);
    else await installation;
    await assertPair(active, 'A');
    await assertPair(active, 'A');
    assert.equal(active.calls.fetch.length, 0);
    assert.equal(waiting.calls.skipWaiting, 0);
    assert.equal(waiting.calls.claim, 0);
    assert.ok(stores.has(CURRENT));
    if (!failed) {
      await waiting.dispatch('message', { data: { type: 'SKIP_WAITING' } }).done;
      assert.equal(waiting.calls.skipWaiting, 1);
      // Message alone does not change the active worker or its pinned resources.
      await assertPair(active, 'A');
      await waiting.dispatch('activate').done;
      await assertPair(waiting, 'B');
      assert.equal(stores.has(CURRENT), false);
    }
  });
}

test('cross-origin resources and failed static responses are not cached', async () => {
  for (const [url, status] of [['https://cdn.example.com/a.js', 200], [`${SCOPE}missing.js`, 404]]) {
    const h = harness({ fetch: async () => new Response('body', { status }) });
    const event = h.dispatch('fetch', { request: { url, method: 'GET', mode: 'cors' } });
    assert.equal((await event.response).status, status);
    await event.done;
    assert.equal(h.calls.put.length, 0);
  }
});

test('query parameters cannot bypass pinned shell resources', async () => {
  const h = harness({ fetch: async () => new Response('Core-B') });
  await h.seed(CURRENT, './core.js', new Response('Core-A'));
  const event = h.dispatch('fetch', { request: { url: `${SCOPE}core.js?v=B`, method: 'GET', mode: 'cors' } });
  assert.equal(await (await event.response).text(), 'Core-A');
  assert.equal(h.calls.fetch.length, 0);
});


test('missing views.js prevents installation and preserves the old version', async () => {
  const h = harness({ fetch: async url => url === './views.js' ? new Response('missing', { status: 404 }) : html() });
  await h.seed('kaoyan-v4', './index.html', html('old'));
  await assert.rejects(h.dispatch('install').done);
  assert.equal(await (await (await h.caches.open('kaoyan-v4')).match('./index.html')).text(), 'old');
  assert.equal(h.calls.skipWaiting, 0);
  assert.equal(h.calls.claim, 0);
});
