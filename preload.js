'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');

const call = (ch, ...a) => ipcRenderer.invoke(ch, ...a).then(r => { if (!r.ok) throw new Error(r.error); return r.data; });
const on = (ch, fn) => { const h = (_e, d) => fn(d); ipcRenderer.on(ch, h); return () => ipcRenderer.removeListener(ch, h); };

contextBridge.exposeInMainWorld('damoon', {
  info: () => call('app:info'),
  versions: (snap) => call('versions:list', snap),
  loaders: (mc) => call('loaders:available', mc),
  instances: {
    list: () => call('instances:list'),
    create: (o) => call('instances:create', o),
    update: (id, p) => call('instances:update', id, p),
    remove: (id) => call('instances:remove', id),
    openFolder: (id, sub) => call('instances:openFolder', id, sub),
    recommendedMemory: (mc) => call('instances:recommendedMemory', mc),
  },
  mods: {
    list: (id) => call('mods:list', id),
    search: (id, q, off) => call('mods:search', id, q, off),
    install: (id, p) => call('mods:install', id, p),
    toggle: (id, f) => call('mods:toggle', id, f),
    remove: (id, f) => call('mods:remove', id, f),
    optimize: (id) => call('mods:optimize', id),
    updateAll: (id) => call('mods:updateAll', id),
    addLocal: (id) => call('mods:addLocal', id),
    addFiles: (id, files) => call('mods:addPaths', id, [...files].map(f => webUtils.getPathForFile(f))),
  },
  accounts: {
    list: () => call('accounts:list'),
    login: () => call('accounts:login'),
    cancel: () => call('accounts:cancelLogin'),
    select: (u) => call('accounts:select', u),
    remove: (u) => call('accounts:remove', u),
  },
  settings: { get: () => call('settings:get'), set: (p) => call('settings:set', p) },
  play: (id, opts) => call('game:play', id, opts),
  kill: (id) => call('game:kill', id),
  open: (url) => call('shell:open', url),
  on,
});
