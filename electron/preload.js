'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const call = (channel) => (arg) => ipcRenderer.invoke(channel, arg);
const on = (channel) => (cb) => {
  const handler = (_e, value) => cb(value);
  ipcRenderer.on(channel, handler);
  return () => ipcRenderer.removeListener(channel, handler);
};

contextBridge.exposeInMainWorld('api', {
  list: call('dm:list'),
  get: call('dm:get'),
  patch: call('dm:patch'),
  confirm: call('dm:confirm'),
  cancelAsk: call('dm:cancelAsk'),
  probe: call('dm:probe'),
  categoryDir: call('dm:category-dir'),
  deleteCompleted: call('dm:deleteCompleted'),
  toolStatus: call('tools:status'),
  installTool: call('tools:install'),
  openProgress: call('dialog:progress'),
  resizeWindow: call('win:resize'),
  closeWindow: call('win:close'),
  onCommand: on('ui:cmd'),
  add: call('dm:add'),
  apiCall: (route, body) => ipcRenderer.invoke('dm:apiCall', { route, body }),
  action: call('dm:action'),
  startAll: call('dm:startAll'),
  pauseAll: call('dm:pauseAll'),
  getSettings: call('settings:get'),
  setSettings: call('settings:set'),
  saveQueue: call('queue:save'),
  deleteQueue: call('queue:delete'),
  startQueue: call('queue:start'),
  stopQueue: call('queue:stop'),
  batchPreview: call('batch:preview'),
  grab: call('grab:run'),
  grabCancel: call('grab:cancel'),
  chooseDir: call('dialog:dir'),
  openPath: call('shell:open'),
  showInFolder: call('shell:show'),
  appInfo: call('app:info'),
  openExtensionFolder: call('app:openExtensionFolder'),
  copy: call('app:copy'),
  onUpdate: on('dm:update'),
  onAddUrl: on('ui:add-url'),
});
