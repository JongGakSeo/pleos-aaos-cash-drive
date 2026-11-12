#!/usr/bin/env node
// Renders one self-made briefing scene to the silent 15-second mp4 the app plays for a ticket.
//
// Usage:
//   node tools/video/render_briefing.mjs <id> <out.mp4> [--sheet <png>] [--crf <n>]
//   node tools/video/render_briefing.mjs <id> --stills <dir> [--at 1,3,5.5]
//
//   <id>      right-turn | school-zone | emergency-yield (tools/video/briefings/<id>.html)
//   --sheet   also write the review contact sheet: frames at 1, 3, 5, 7, 9, 11, 13 and 14.5 s,
//             4x2 tiles of 540 px (planning/evidence/...)
//   --crf     first x264 CRF to try (default 22); raised by 2 until the file fits in MAX_BYTES
//   --stills  write single PNG frames instead of a video (art iteration); --at picks the seconds
//
// Zero npm dependencies. Headless Chrome is driven over the DevTools protocol with Node's global
// WebSocket (Node 22+): a 1080x1080 viewport at scale 1 opens the page with window.__BRIEFING_RENDER__
// set, so the page skips its real-time preview loop; then every frame i of 360 (24 fps x 15 s) is
// drawn by the page's own deterministic window.renderAt(i / 24) and captured as a PNG. ffmpeg (on PATH)
// encodes them: H.264 Main at level 4.0, yuv420p with a BT.709 matrix and tags, 24 fps, exactly 360 frames
// (15.000 s), a keyframe every second (-g 24 -keyint_min 24 -sc_threshold 0) so a resume seeks near
// the stored position, no audio track, +faststart. The result is checked with ffprobe and the script
// exits non-zero when a check fails. Chrome is closed and the temporary profile and frames removed
// on every exit path. The three pages are self-contained, so each carries the same series kit; the
// script warns when the copies differ.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const FPS = 24;
const SECONDS = 15;
const FRAMES = FPS * SECONDS;
const SIZE = 1080;
/** The theater's budget per creative (the PSA is about 1.1 MB). */
const MAX_BYTES = 2_000_000;
const SHEET_TIMES = [1, 3, 5, 7, 9, 11, 13, 14.5];
const IDS = ['right-turn', 'school-zone', 'emergency-yield'];
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const HERE = dirname(fileURLToPath(import.meta.url));

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error('usage: node tools/video/render_briefing.mjs <id> <out.mp4> [--sheet <png>] [--crf <n>]');
  console.error('       node tools/video/render_briefing.mjs <id> --stills <dir> [--at 1,3,5.5]');
  console.error(`ids: ${IDS.join(', ')}`);
  process.exit(2);
}

function parseArgs(argv) {
  const opts = { crf: 22, at: SHEET_TIMES };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--sheet') opts.sheet = argv[++i];
    else if (a === '--crf') opts.crf = Number(argv[++i]);
    else if (a === '--stills') opts.stills = argv[++i];
    else if (a === '--at') opts.at = String(argv[++i]).split(',').map(Number);
    else if (a.startsWith('--')) usage(`unknown option ${a}`);
    else rest.push(a);
  }
  const [id, out] = rest;
  if (!IDS.includes(id)) usage(id ? `unknown id ${id}` : 'missing id');
  if (!opts.stills && !out) usage('missing output path');
  if (!Number.isFinite(opts.crf) || opts.crf < 0 || opts.crf > 51) usage('--crf takes 0..51');
  if (opts.at.some((t) => !Number.isFinite(t) || t < 0 || t > SECONDS)) usage(`--at takes seconds in 0..${SECONDS}`);
  return { id, out, ...opts };
}

/** A minimal DevTools protocol client over one browser WebSocket with flattened page sessions. */
class Cdp {
  constructor(url) {
    this.url = url;
    this.next = 0;
    this.pending = new Map();
    this.waiters = [];
    this.exceptions = [];
  }

  open() {
    return new Promise((resolveOpen, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      ws.onopen = () => resolveOpen();
      ws.onerror = () => reject(new Error(`cannot open ${this.url}`));
      ws.onclose = () => {
        for (const { reject: fail, method } of this.pending.values()) fail(new Error(`browser closed during ${method}`));
        this.pending.clear();
      };
      ws.onmessage = (event) => this.receive(JSON.parse(event.data));
    });
  }

  receive(message) {
    if (message.id !== undefined) {
      const call = this.pending.get(message.id);
      if (!call) return;
      this.pending.delete(message.id);
      if (message.error) call.reject(new Error(`${call.method}: ${message.error.message}`));
      else call.resolve(message.result);
      return;
    }
    if (message.method === 'Runtime.exceptionThrown') {
      const details = message.params.exceptionDetails;
      this.exceptions.push(details.exception?.description || details.text);
    }
    this.waiters = this.waiters.filter((w) => {
      if (w.method !== message.method || (w.sessionId && w.sessionId !== message.sessionId)) return true;
      clearTimeout(w.timer);
      w.resolve(message.params);
      return false;
    });
  }

  send(method, params = {}, sessionId, timeoutMs = 30_000) {
    const id = ++this.next;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    return new Promise((resolveCall, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.pending.set(id, {
        method,
        resolve: (v) => { clearTimeout(timer); resolveCall(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      this.ws.send(JSON.stringify(payload));
    });
  }

  waitFor(method, sessionId, timeoutMs = 30_000) {
    return new Promise((resolveWait, reject) => {
      const waiter = { method, sessionId, resolve: resolveWait };
      waiter.timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== waiter);
        reject(new Error(`no ${method} within ${timeoutMs} ms`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  close() {
    try { this.ws?.close(); } catch { /* already closed */ }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Starts headless Chrome on a throwaway profile and returns its browser endpoint. */
async function launchChrome(profile) {
  if (!existsSync(CHROME)) throw new Error(`Chrome not found at ${CHROME} (set CHROME_PATH)`);
  const args = [
    '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--hide-scrollbars',
    '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-sync',
    '--disable-background-networking', '--disable-component-update', '--mute-audio',
    '--force-color-profile=srgb', '--force-device-scale-factor=1', '--font-render-hinting=none',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows', `--window-size=${SIZE},${SIZE}`, 'about:blank',
  ];
  const child = spawn(CHROME, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  let stderr = '';
  child.stderr.on('data', (chunk) => { stderr = (stderr + chunk).slice(-4000); });
  const portFile = join(profile, 'DevToolsActivePort');
  for (let waited = 0; waited < 20_000; waited += 50) {
    if (child.exitCode !== null) throw new Error(`Chrome exited (${child.exitCode}): ${stderr.trim()}`);
    if (existsSync(portFile)) {
      const [port, path] = readFileSync(portFile, 'utf8').trim().split('\n');
      if (port && path) return { child, endpoint: `ws://127.0.0.1:${port}${path}` };
    }
    await sleep(50);
  }
  throw new Error(`Chrome did not report a DevTools port: ${stderr.trim()}`);
}

/** Closes the browser politely, then by signal; resolves once the process has exited. */
async function stopChrome(cdp, child) {
  const running = child && child.exitCode === null && child.signalCode === null;
  const exited = running ? new Promise((r) => child.once('exit', () => r('exit'))) : Promise.resolve('exit');
  if (cdp) {
    try { await cdp.send('Browser.close', {}, undefined, 3_000); } catch { /* fall through to the signals */ }
    cdp.close();
  }
  if (!running) return;
  const within = (ms) => Promise.race([exited, sleep(ms).then(() => 'timeout')]);
  if ((await within(5_000)) === 'exit') return;
  child.kill('SIGTERM');
  if ((await within(3_000)) === 'exit') return;
  child.kill('SIGKILL');
  await within(3_000);
}

/** Chrome's helper processes can still be writing into the profile for a moment after the browser exits. */
function removeDir(dir) {
  rmSync(dir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 });
}

/** Opens the briefing page in a 1080x1080 tab and returns a frame grabber bound to it. */
async function openPage(cdp, file) {
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
  const call = (method, params, timeoutMs) => cdp.send(method, params, sessionId, timeoutMs);
  await call('Page.enable');
  await call('Runtime.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: SIZE, height: SIZE, deviceScaleFactor: 1, mobile: false });
  await call('Emulation.setDefaultBackgroundColorOverride', { color: { r: 14, g: 15, b: 17, a: 1 } });
  await call('Page.addScriptToEvaluateOnNewDocument', { source: 'window.__BRIEFING_RENDER__ = true;' });
  const loaded = cdp.waitFor('Page.loadEventFired', sessionId);
  await call('Page.navigate', { url: pathToFileURL(file).href });
  await loaded;
  const evaluate = async (expression) => {
    const { result, exceptionDetails } = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (exceptionDetails) throw new Error(`page error: ${exceptionDetails.exception?.description || exceptionDetails.text}`);
    return result.value;
  };
  const kind = await evaluate('document.fonts.ready.then(() => window.briefingReady).then(() => typeof window.renderAt)');
  if (kind !== 'function') throw new Error(`${file} does not define window.renderAt`);
  if (cdp.exceptions.length) throw new Error(`page error: ${cdp.exceptions[0]}`);

  /** Draws second [t] with the page's renderAt, waits two animation frames, returns the PNG bytes. */
  return async function grab(t) {
    await evaluate(`window.renderAt(${t}); new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))`);
    if (cdp.exceptions.length) throw new Error(`page error at ${t}s: ${cdp.exceptions[0]}`);
    const { data } = await call('Page.captureScreenshot', {
      format: 'png', clip: { x: 0, y: 0, width: SIZE, height: SIZE, scale: 1 }, captureBeyondViewport: false, optimizeForSpeed: true,
    });
    return Buffer.from(data, 'base64');
  };
}

function run(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8' });
  if (r.error) throw new Error(`${cmd}: ${r.error.message}`);
  if (r.status !== 0) throw new Error(`${cmd} failed (${r.status}): ${(r.stderr || '').trim().slice(-2000)}`);
  return r.stdout;
}

/**
 * x264 Main at level 4.0 (4 reference frames: -tune animation would double them and push a 1080x1080
 * picture to level 5.0, which not every car's decoder advertises), BT.709 matrix, limited range, tagged.
 */
function encode(framesDir, out, crf) {
  run('ffmpeg', [
    '-v', 'error', '-y', '-framerate', String(FPS), '-start_number', '0', '-i', join(framesDir, 'f%04d.png'),
    '-frames:v', String(FRAMES), '-an',
    '-vf', 'scale=out_color_matrix=bt709:out_range=tv:flags=lanczos+accurate_rnd+full_chroma_int,format=yuv420p,'
      + 'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv',
    '-c:v', 'libx264', '-profile:v', 'main', '-level:v', '4.0', '-preset', 'slow', '-tune', 'animation', '-refs', '4',
    '-crf', String(crf), '-r', String(FPS), '-g', String(FPS), '-keyint_min', String(FPS), '-sc_threshold', '0',
    '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709', '-color_range', 'tv',
    '-movflags', '+faststart', out,
  ]);
}

/** The facts the record and the checks need, from ffprobe. */
function probe(file) {
  const json = JSON.parse(run('ffprobe', [
    '-v', 'error', '-count_frames', '-show_entries',
    'format=duration,size,bit_rate:stream=codec_type,codec_name,profile,level,width,height,pix_fmt,r_frame_rate,nb_frames,nb_read_frames,color_space,color_primaries,color_transfer',
    '-of', 'json', file,
  ]));
  const video = json.streams.filter((s) => s.codec_type === 'video');
  const keyframes = run('ffprobe', [
    '-v', 'error', '-select_streams', 'v:0', '-skip_frame', 'nokey', '-show_entries', 'frame=pts_time', '-of', 'csv=p=0', file,
  ]).trim().split('\n').filter(Boolean).map((s) => Number(s.replace(/,$/, '')));
  return { format: json.format, video: video[0], streams: json.streams.length, audio: json.streams.filter((s) => s.codec_type === 'audio').length, keyframes };
}

function check(facts) {
  const v = facts.video;
  const problems = [];
  if (!v) problems.push('no video stream');
  else {
    if (v.codec_name !== 'h264' || v.profile !== 'Main') problems.push(`codec ${v.codec_name} ${v.profile}, want h264 Main`);
    if (!(v.level <= 40)) problems.push(`level ${v.level}, want 4.0 or lower`);
    if ([v.color_space, v.color_primaries, v.color_transfer].some((c) => c !== 'bt709')) {
      problems.push(`colour tags ${v.color_space}/${v.color_primaries}/${v.color_transfer}, want bt709`);
    }
    if (v.width !== SIZE || v.height !== SIZE) problems.push(`size ${v.width}x${v.height}`);
    if (v.pix_fmt !== 'yuv420p') problems.push(`pix_fmt ${v.pix_fmt}`);
    if (v.r_frame_rate !== `${FPS}/1`) problems.push(`rate ${v.r_frame_rate}`);
    if (Number(v.nb_read_frames) !== FRAMES || Number(v.nb_frames) !== FRAMES) problems.push(`frames ${v.nb_frames}/${v.nb_read_frames}`);
  }
  if (facts.audio || facts.streams !== 1) problems.push(`${facts.streams} streams, ${facts.audio} audio`);
  if (Math.abs(Number(facts.format.duration) - SECONDS) > 0.0005) problems.push(`duration ${facts.format.duration}`);
  const wantKeys = Array.from({ length: SECONDS }, (_, i) => i);
  if (facts.keyframes.length !== wantKeys.length || facts.keyframes.some((k, i) => Math.abs(k - wantKeys[i]) > 1e-3)) {
    problems.push(`keyframes at ${facts.keyframes.join(',')}`);
  }
  return problems;
}

/**
 * The three pages are self-contained, so each carries the same series kit (style, defs and the shared
 * script up to the "end of series kit" marker). Warns when the copies drift apart.
 */
function checkSeriesKit(id) {
  const kitOf = (name) => {
    const text = readFileSync(join(HERE, 'briefings', `${name}.html`), 'utf8');
    const start = text.indexOf('<style>');
    const end = text.indexOf('/* ===== end of series kit ===== */');
    return start >= 0 && end > start ? text.slice(start, end) : null;
  };
  const mine = kitOf(id);
  for (const other of IDS.filter((name) => name !== id && existsSync(join(HERE, 'briefings', `${name}.html`)))) {
    if (kitOf(other) !== mine) console.warn(`warning: the series kit in ${id}.html differs from ${other}.html; keep the copies identical`);
  }
}

function contactSheet(video, sheet) {
  const picks = SHEET_TIMES.map((s) => `eq(n\\,${Math.round(s * FPS)})`).join('+');
  mkdirSync(dirname(resolve(sheet)), { recursive: true });
  run('ffmpeg', [
    '-v', 'error', '-y', '-i', video,
    '-vf', `select='${picks}',scale=540:540:flags=lanczos,tile=4x2`, '-fps_mode', 'vfr', '-frames:v', '1', sheet,
  ]);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const page = join(HERE, 'briefings', `${opts.id}.html`);
  if (!existsSync(page)) usage(`missing ${page}`);
  checkSeriesKit(opts.id);
  const profile = mkdtempSync(join(tmpdir(), 'briefing-chrome-'));
  const frames = mkdtempSync(join(tmpdir(), 'briefing-frames-'));
  let chrome;
  let cdp;
  const cleanup = async () => {
    await stopChrome(cdp, chrome?.child);
    cdp = undefined;
    removeDir(profile);
    removeDir(frames);
  };
  process.once('SIGINT', () => { cleanup().finally(() => process.exit(130)); });
  try {
    chrome = await launchChrome(profile);
    cdp = new Cdp(chrome.endpoint);
    await cdp.open();
    const grab = await openPage(cdp, page);

    if (opts.stills) {
      mkdirSync(opts.stills, { recursive: true });
      for (const t of opts.at) {
        const file = join(opts.stills, `${opts.id}-${t.toFixed(2)}s.png`);
        writeFileSync(file, await grab(t));
        console.log(`wrote ${file}`);
      }
      return;
    }

    const started = Date.now();
    let first;
    for (let i = 0; i < FRAMES; i++) {
      const png = await grab(i / FPS);
      if (i === 0) first = png;
      writeFileSync(join(frames, `f${String(i).padStart(4, '0')}.png`), png);
      if (i % FPS === FPS - 1) process.stdout.write(`\rcaptured ${i + 1}/${FRAMES} frames`);
    }
    process.stdout.write(`\rcaptured ${FRAMES}/${FRAMES} frames in ${((Date.now() - started) / 1000).toFixed(1)} s\n`);
    // renderAt must draw a time, not a history: frame 0 again after the whole run is the same picture.
    if (!first.equals(await grab(0))) throw new Error('renderAt(0) drew a different frame the second time: the page is not deterministic');
    await stopChrome(cdp, chrome.child);
    cdp = undefined;

    const out = resolve(opts.out);
    mkdirSync(dirname(out), { recursive: true });
    let crf = opts.crf;
    for (;;) {
      encode(frames, out, crf);
      const size = statSync(out).size;
      console.log(`crf ${crf}: ${size} bytes`);
      if (size <= MAX_BYTES) break;
      if (crf >= 30) throw new Error(`still ${size} bytes at crf ${crf}; simplify the scene`);
      crf += 2;
    }
    const facts = probe(out);
    const v = facts.video;
    console.log(`ffprobe: ${v.codec_name} ${v.profile} level ${v.level} ${v.width}x${v.height} ${v.pix_fmt} ${v.color_space}/${v.color_primaries}/${v.color_transfer}`
      + ` ${v.r_frame_rate} fps, ${v.nb_read_frames} frames, ${facts.format.duration} s, ${facts.format.size} bytes, ${facts.format.bit_rate} b/s,`
      + ` streams ${facts.streams} (audio ${facts.audio}), keyframes at ${facts.keyframes.join(' ')} s, crf ${crf}`);
    const problems = check(facts);
    if (problems.length) throw new Error(`check failed: ${problems.join('; ')}`);
    if (opts.sheet) {
      contactSheet(out, opts.sheet);
      console.log(`wrote ${opts.sheet}`);
    }
    console.log(`wrote ${out}`);
  } finally {
    await cleanup();
  }
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
