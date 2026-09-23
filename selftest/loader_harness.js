/*
 * Runs the loaders that ScriptCryptoTest rendered, inside a vm sandbox with
 * frida's globals stubbed out. Verifies the whole generated-loader logic
 * (decrypt -> hand off to Script.load / eval -> logcat) without a device.
 *
 * Usage: node loader_harness.js <genDir> [expectedPlainFile]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');

const genDir = process.argv[2];
if (!genDir) {
  console.error('usage: node loader_harness.js <genDir> [expectedPlainFile]');
  process.exit(2);
}
const expectedFile = process.argv[3] || path.join(genDir, '..', '..', '..', 'myheroes-pro', 'dist', 'inject_payload.js');

let expectedHash = null;
if (fs.existsSync(expectedFile)) {
  expectedHash = crypto.createHash('sha256').update(fs.readFileSync(expectedFile)).digest('hex');
}

let pass = 0;
let fail = 0;
function check(label, ok, extra) {
  if (ok) { pass++; console.log('  PASS  ' + label); }
  else { fail++; console.log('  FAIL  ' + label + (extra ? '  -> ' + extra : '')); }
}

async function run(loaderFile, opts) {
  const toasts = [];
  const loaded = [];
  const logcatCalls = [];
  const consoleCalls = [];
  const sandbox = {
    console: Object.fromEntries(['log', 'info', 'warn', 'error', 'debug'].map(level =>
      [level, (...args) => consoleCalls.push({level, args})])),
    send: () => {},
    setTimeout: (f) => f(),
    File: class {
      constructor(p) { this.p = p; }
      readText() {
        if (opts.fileError) throw new Error('fixture read failure');
        return fs.readFileSync(this.p, 'utf8');
      }
      close() {}
    },
  };
  if (opts.withScriptLoad) {
    sandbox.Script = { load: (name, src) => {
      if (opts.rejectLoad) return Promise.reject(new Error('async load failure'));
      loaded.push({ name, src });
      return Promise.resolve({});
    } };
  }
  if (opts.withLiblog) {
    // frida 17 style: Module.getGlobalExportByName + NativeFunction + Memory
    sandbox.Module = {
      getGlobalExportByName: (n) =>
        (n === '__android_log_write' ? { addr: n } : null),
    };
    sandbox.Memory = { allocUtf8String: (s) => s };
    sandbox.NativeFunction = function (addr, ret, args) {
      return function (prio, tag, msg) { logcatCalls.push({ prio, tag, msg }); };
    };
    sandbox.Process = { getModuleByName: () => null };
  }
  if (opts.withJava) {
    sandbox.Java = {
      perform: (f) => f(),
      scheduleOnMainThread: (f) => f(),
      use: (name) => {
        if (name === 'android.app.ActivityThread') {
          return { currentApplication: () => ({ getApplicationContext: () => ({}) }) };
        }
        if (name === 'android.widget.Toast') {
          return { makeText: (ctx, msg) => ({ show: () => toasts.push(String(msg)) }) };
        }
        if (name === 'java.lang.String') {
          return { $new: (s) => s };
        }
        throw new Error('unexpected class ' + name);
      },
    };
  }
  const ctx = vm.createContext(sandbox);
  if (opts.withEvaluate) sandbox.Script = { evaluate: (name, src) => vm.runInContext(src, ctx, {filename: name}) };
  vm.runInContext(fs.readFileSync(path.join(genDir, loaderFile), 'utf8'), ctx,
    { filename: loaderFile });
  await new Promise(resolve => setImmediate(resolve));
  return { toasts, loaded, logcatCalls, consoleCalls, ctx };
}

async function main() {
console.log('[1] encrypted payload (cipher imported as is) + Script.load + Java bridge');
const r1 = await run('loader_real.js', { withScriptLoad: true, withJava: true });
check('Script.load called once', r1.loaded.length === 1, 'calls=' + r1.loaded.length);
check('registered name is /index.js', !!r1.loaded[0] && r1.loaded[0].name === '/index.js');
if (expectedHash && r1.loaded[0]) {
  const got = crypto.createHash('sha256').update(r1.loaded[0].src, 'utf8').digest('hex');
  check('decrypted source sha256 matches the payload', got === expectedHash, got);
}
check('manager does not show Toast with Java available', r1.toasts.length === 0, JSON.stringify(r1.toasts));

console.log('[2] manager-encrypted ciphertext (encrypt mode) + eval fallback');
const raw = fs.readFileSync(path.join(genDir, 'small.enc'), 'utf8');
check('ciphertext is single line base64', /^[A-Za-z0-9+/=]+$/.test(raw.trim()),
  raw.slice(0, 24));
const r2 = await run('loader_small.js', { withScriptLoad: false, withJava: true });
check('fallback executed the payload', r2.ctx.__ZFM_HIT === 1, 'HIT=' + r2.ctx.__ZFM_HIT);
check('eval fallback does not add Toast', r2.toasts.length === 0, JSON.stringify(r2.toasts));

console.log('[3] plain mode');
const r3 = await run('loader_plain.js', { withScriptLoad: true, withJava: true });
check('plain source handed to Script.load',
  r3.loaded.length === 1 && /__ZFM_HIT/.test(r3.loaded[0].src));

console.log('[4] no Java bridge: loading and logcat diagnostics still work');
const r4 = await run('loader_real.js', { withScriptLoad: true, withJava: false, withLiblog: true });
check('still loaded', r4.loaded.length === 1);
check('no toast without a Java bridge', r4.toasts.length === 0, JSON.stringify(r4.toasts));
check('logcat notification written', r4.logcatCalls.length >= 2,
  'calls=' + r4.logcatCalls.length);
check('logcat tag is ZFM', r4.logcatCalls.every((c) => c.tag === 'ZFM'));
check('logcat carries the start + ready messages',
  r4.logcatCalls.some((c) => /已注入/.test(c.msg))
  && r4.logcatCalls.some((c) => /脚本已加载/.test(c.msg)),
  JSON.stringify(r4.logcatCalls.map((c) => c.msg)));
console.log('        logcat = ' + JSON.stringify(r4.logcatCalls.map((c) => c.msg)));

console.log('[5] Java bridge available: diagnostics stay in logcat');
const r5 = await run('loader_real.js', { withScriptLoad: true, withJava: true, withLiblog: true });
check('manager still does not show Toast', r5.toasts.length === 0, JSON.stringify(r5.toasts));
check('logcat fired as well', r5.logcatCalls.length >= 2);

console.log('[6] user-owned Toast remains intact');
const r6 = await run('loader_user.js', { withScriptLoad: false, withJava: true, withLiblog: true });
check('only the user script shows Toast', r6.toasts.length === 1 && r6.toasts[0] === 'USER_TOAST');
check('user script continues after Toast', r6.ctx.__USER_HIT === 1);

console.log('[7] loading failure does not add Toast');
const r7 = await run('loader_real.js', { withScriptLoad: true, withJava: true, withLiblog: true, fileError: true });
check('failed payload is not loaded', r7.loaded.length === 0);
check('failure is written to logcat', r7.logcatCalls.some(c => /bootstrap error/.test(c.msg)));
check('failure does not show Toast', r7.toasts.length === 0);
check('generated bootstrap contains no Toast bridge', !fs.readFileSync(path.join(genDir, 'loader_real.js'), 'utf8').includes('android.widget.Toast'));

console.log('[8] plain and encrypted payload consoles reach logcat');
for (const loader of ['loader_console_plain.js', 'loader_console_cipher.js']) {
  const result = await run(loader, {withEvaluate: true, withLiblog: true});
  const messages = result.logcatCalls.filter(c => c.tag === 'ZFM-Script');
  check(loader + ': object and multiple arguments', messages.some(c => c.msg.includes('hello {"n":7}')));
  check(loader + ': warning severity', messages.some(c => c.prio === 5 && c.msg.includes('warn-fixture')));
  check(loader + ': error stack', messages.some(c => c.prio === 6 && c.msg.includes('error-fixture')));
  check(loader + ': debug severity', messages.some(c => c.prio === 3));
  check(loader + ': circular objects do not stop execution', result.ctx.__CONSOLE_DONE === true);
  check(loader + ': original console still called', result.consoleCalls.some(c => c.args[0] === 'hello'));
  check(loader + ': long Unicode message is chunked', messages.filter(c => c.msg.includes('长')).length >= 3);
  check(loader + ': no oversized Android log records', messages.every(c => Buffer.byteLength(c.msg, 'utf8') < 4000));
}
const rejected = await run('loader_plain.js', {withScriptLoad: true, withLiblog: true, rejectLoad: true});
check('async module rejection is reported', rejected.logcatCalls.some(c => /async load failure/.test(c.msg)));
check('async rejection does not claim success', !rejected.logcatCalls.some(c => /done: ok=true/.test(c.msg)));
check('async rejection does not execute fallback twice', rejected.ctx.__ZFM_HIT === undefined);

console.log('\n结果: pass=' + pass + ' fail=' + fail);
process.exit(fail === 0 ? 0 : 1);
}
main().catch(error => { console.error(error); process.exit(1); });
