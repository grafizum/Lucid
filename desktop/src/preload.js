'use strict';

const { contextBridge, ipcRenderer } = require('electron');

function subscribe(channel) {
  return (cb) => {
    const handler = (_event, payload) => cb(payload);
    ipcRenderer.on(channel, handler);
    return () => ipcRenderer.removeListener(channel, handler);
  };
}

contextBridge.exposeInMainWorld('lucid', {
  /* lens */
  readNow: () => ipcRenderer.send('read:now'),
  dragStart: (dx, dy) => ipcRenderer.send('lens:drag-start', { dx, dy }),
  dragEnd: () => ipcRenderer.send('lens:drag-end'),
  hover: (state) => ipcRenderer.send('lens:hover', state),
  menu: () => ipcRenderer.send('lens:menu'),
  requestState: () => ipcRenderer.send('lens:state-request'),
  onLensState: subscribe('lens:state'),

  /* panel */
  onPanelState: subscribe('panel:state'),
  setPanelHeight: (h) => ipcRenderer.send('panel:height', h),
  closePanel: () => ipcRenderer.send('panel:close'),
  copy: (text) => ipcRenderer.invoke('panel:copy', text),
  retry: () => ipcRenderer.send('panel:retry'),
  toggleAuto: () => ipcRenderer.send('panel:auto'),

  /* sight */
  onSightMode: subscribe('sight:mode'),

  /* setup / first run */
  setupState: () => ipcRenderer.invoke('setup:state'),
  grant: () => ipcRenderer.invoke('setup:grant'),
  closeSetup: () => ipcRenderer.send('setup:close'),
  quit: () => ipcRenderer.send('app:quit'),
});
