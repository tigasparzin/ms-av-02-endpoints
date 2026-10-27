// Real Microsoft Edge rendering via the Chrome DevTools Protocol. No simulated images.
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, readdir, stat, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const source = path.dirname(fileURLToPath(import.meta.url));
const root = path.dirname(source);
const local = path.join(root, '.local', 'observability');
const profile = path.join(local, 'browsers', String(process.pid));
const action = process.argv[2] || 'inspect';
const url = process.argv[3] || 'http://127.0.0.1:3000/d/av02-seguranca-observabilidade?orgId=1&refresh=5s';
await mkdir(profile, { recursive: true });
await mkdir(path.join(source, 'evidence'), { recursive: true });
await mkdir(path.join(local, 'browser-downloads'), { recursive: true });
const browser = spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--disable-background-networking', '--disable-component-update', '--disable-sync',
  '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
  `--user-data-dir=${profile}`, `--disk-cache-dir=${path.join(profile, 'cache')}`, 'about:blank'
], { windowsHide: true, stdio: 'ignore', env: { ...process.env, TEMP: path.join(local, 'tmp'), TMP: path.join(local, 'tmp') } });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let socket;
let seq = 0;
const pending = new Map();
const events = [];
function command(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 20000);
    pending.set(id, { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
}
async function until(predicate, timeout = 45000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await predicate()) return; await pause(400); }
  throw new Error('Browser readiness timeout');
}
try {
  let debugPort;
  await until(async () => {
    try { debugPort = Number((await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); return debugPort > 0; }
    catch { return false; }
  });
  const tabs = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();
  const tab = tabs.find(item => item.type === 'page');
  socket = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = message => {
    const data = JSON.parse(message.data);
    if (data.id) {
      const item = pending.get(data.id); if (!item) return; pending.delete(data.id);
      if (data.error) item.reject(new Error(JSON.stringify(data.error))); else item.resolve(data.result);
    } else { events.push(data); }
  };
  await command('Page.enable');
  await command('Network.enable');
  await command('Emulation.setDeviceMetricsOverride', { width: 1600, height: 650, deviceScaleFactor: 1, mobile: false });
  await command('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: path.join(local, 'browser-downloads'), eventsEnabled: true });
  await command('Page.navigate', { url });
  await until(async () => await evaluate('document.readyState === "complete" && document.body.innerText.length > 100'));
  await pause(7000);
  const text = await evaluate('document.body.innerText');
  const panelExpression = 'Array.from({length:3},(_,i)=>Number(document.querySelector(`[data-testid="stat-panel-${i+1}"] [data-testid="data-testid panel content"]`)?.innerText))';
  const buttons = await evaluate('Array.from(document.querySelectorAll("button,a[role=button]")).map(e=>({text:e.innerText,label:e.getAttribute("aria-label"),title:e.getAttribute("title"),testid:e.getAttribute("data-testid")}))');
  if (action === 'inspect') {
    console.log(JSON.stringify({ text, buttons, stats: await evaluate('Array.from(document.querySelectorAll("[data-testid]")).filter(e=>/stat|panel/i.test(e.getAttribute("data-testid"))).slice(0,30).map(e=>({testid:e.getAttribute("data-testid"),text:e.innerText}))') }, null, 2));
  } else if (['export-inspect','code-inspect','advanced-inspect','export'].includes(action)) {
    await evaluate('document.querySelector("button[aria-label=Export]").click()');
    await pause(800);
    if (action !== 'export-inspect') {
      await evaluate('Array.from(document.querySelectorAll("[role=menuitem],button")).find(e=>e.innerText.trim()==="Export as code").click()');
      await pause(1000);
    }
    if (action === 'advanced-inspect' || action === 'export') {
      await evaluate('Array.from(document.querySelectorAll("*")).find(e=>!e.children.length && e.textContent.trim()==="Advanced options").click()');
      await evaluate('Array.from(document.querySelectorAll("label")).find(e=>e.getAttribute("aria-label")==="Share dashboard with another instance").click()');
      await pause(600);
    }
    if (action === 'export') {
      await evaluate('Array.from(document.querySelectorAll("label")).find(e=>e.innerText.trim()==="Classic").click()');
      await pause(600);
      const downloadStarted = Date.now();
      await evaluate('Array.from(document.querySelectorAll("button")).find(e=>e.innerText.trim()==="Download file").click()');
      let downloaded;
      await until(async () => {
        for (const name of await readdir(path.join(local,'browser-downloads'))) {
          const candidate = path.join(local,'browser-downloads',name);
          if (name.endsWith('.json') && (await stat(candidate)).mtimeMs >= downloadStarted-1000) { downloaded=candidate; return true; }
        }
        return false;
      });
      const content = await readFile(downloaded,'utf8');
      const model = JSON.parse(content);
      if (model.title !== 'AV-02 — Segurança e Observabilidade' || model.panels?.length !== 3 || !model.__inputs?.length || content.includes('grafana.app/sourcePath')) throw new Error('Export is not the expected portable Classic dashboard');
      const destination = path.join(source,'dashboard','av02-dashboard.json');
      await copyFile(downloaded,destination);
      const proof = {timeUtc:new Date().toISOString(),method:'Grafana UI > Export > Export as code > Download file',model:'Classic',shareWithAnotherInstance:true,downloadedFilename:path.basename(downloaded),sha256:createHash('sha256').update(content).digest('hex'),inputNames:model.__inputs.map(i=>i.name),panels:model.panels.map(p=>({title:p.title,query:p.targets[0].expr}))};
      await writeFile(path.join(source,'evidence','export-verification.json'),JSON.stringify(proof,null,2));
      console.log(JSON.stringify(proof,null,2));
    } else {
      console.log(JSON.stringify({text: (await evaluate('document.body.innerText')).slice(0,1600), items: await evaluate('Array.from(document.querySelectorAll("[role=menuitem],button,input,select,label,[role=combobox],[role=option]")).map(e=>({tag:e.tagName,text:e.innerText,label:e.getAttribute("aria-label"),role:e.getAttribute("role"),type:e.getAttribute("type"),checked:e.checked,value:e.tagName==="INPUT"?e.value:null}))')}, null, 2));
    }
  } else if (action === 'check') {
    const values = await evaluate(panelExpression);
    if (values.some(value=>!Number.isFinite(value)) || /No data|Query error/i.test(text)) throw new Error('Imported dashboard panels are not healthy');
    const expected = process.argv[4]?.split(',').map(Number);
    if (expected && JSON.stringify(values)!==JSON.stringify(expected)) throw new Error('Imported dashboard values differ from integration evidence');
    const proof={timeUtc:new Date().toISOString(),url,values,noData:false,queryError:false};
    await writeFile(path.join(source,'evidence','import-browser.json'),JSON.stringify(proof,null,2));
    console.log(JSON.stringify(proof,null,2));
  } else if (action === 'refresh') {
    // Validate the recorded process and its explicit demo datasource before generating an event.
    const guard = path.join(source,'common.ps1').replaceAll("'", "''");
    execFileSync('pwsh', ['-NoProfile','-ExecutionPolicy','Bypass','-Command',`. '${guard}'`], {windowsHide:true,stdio:'pipe',env:{...process.env,TEMP:path.join(local,'tmp'),TMP:path.join(local,'tmp')}});
    const before = await evaluate(panelExpression);
    if (before.some(value => !Number.isFinite(value))) throw new Error('Panels are not numeric');
    const requestsBefore = events.filter(e => e.method === 'Network.requestWillBeSent' && e.params.request.url.includes('/api/ds/query')).length;
    const response = await fetch('http://127.0.0.1:8080/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({username:'nonexistent-refresh-probe',senha:crypto.randomUUID()}) });
    if (response.status !== 401) throw new Error('Unexpected demo login status');
    await until(async () => {
      const state = await evaluate(panelExpression);
      return state[0] === before[0] && state[1] === before[1]+1 && state[2] === before[2];
    }, 25000);
    const after = await evaluate(panelExpression);
    const requestsAfter = events.filter(e => e.method === 'Network.requestWillBeSent' && e.params.request.url.includes('/api/ds/query')).length;
    if (requestsAfter <= requestsBefore) throw new Error('No automatic query refresh observed');
    const evidence = {timeUtc:new Date().toISOString(), before, after, requestsBefore, requestsAfter, manualRefresh:false};
    await writeFile(path.join(source,'evidence','browser-refresh.json'), JSON.stringify(evidence,null,2));
    const screenshot = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    await writeFile(path.join(source,'evidence','dashboard-grafana.png'), Buffer.from(screenshot.data,'base64'));
    console.log(JSON.stringify(evidence,null,2));
  } else if (action === 'screenshot') {
    if (!text.includes('Logins válidos') || !text.includes('Usuários bloqueados') || /No data|Query error/i.test(text)) throw new Error('Dashboard is not ready for screenshot');
    const screenshot = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    const destination = path.join(source, 'evidence', 'dashboard-grafana.png');
    await writeFile(destination, Buffer.from(screenshot.data, 'base64'));
    console.log(JSON.stringify({ screenshot: destination, text }, null, 2));
  } else { throw new Error(`Unknown action: ${action}`); }
} finally {
  if (socket?.readyState === WebSocket.OPEN) {
    try { await command('Browser.close'); } catch { }
    socket.close();
  }
  if (browser.exitCode === null) {
    await Promise.race([new Promise(resolve => browser.once('exit', resolve)), pause(3000)]);
    if (browser.exitCode === null) browser.kill();
  }
}
