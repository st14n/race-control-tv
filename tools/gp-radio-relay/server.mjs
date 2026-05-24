import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const host = process.env.HOST ?? '0.0.0.0';
const port = Number(process.env.PORT ?? '8787');
const inputUrl = process.env.INPUT_URL
  ?? 'https://playerservices.streamtheworld.com/api/livestream-redirect/GRAND_PRIX_RADIO.mp3';
const workDir = process.env.WORK_DIR
  ?? path.join(os.tmpdir(), 'gp-radio-relay');
const playlistPath = path.join(workDir, 'live.m3u8');

let ffmpegProcess = null;
let shuttingDown = false;
let lastStartIso = null;

function contentTypeFor(filePath) {
  if (filePath.endsWith('.m3u8')) {
    return 'application/vnd.apple.mpegurl';
  }
  if (filePath.endsWith('.ts')) {
    return 'video/mp2t';
  }
  return 'application/octet-stream';
}

function networkUrls() {
  const interfaces = os.networkInterfaces();
  const urls = [];
  for (const addresses of Object.values(interfaces)) {
    for (const address of addresses ?? []) {
      if (address.family === 'IPv4' && !address.internal) {
        urls.push(`http://${address.address}:${port}/live.m3u8`);
      }
    }
  }
  return urls.sort();
}

async function ensureWorkDir() {
  await fs.mkdir(workDir, { recursive: true });
  const entries = await fs.readdir(workDir);
  await Promise.all(entries.map((entry) => {
    if (entry.startsWith('live') || entry.startsWith('segment_')) {
      return fs.rm(path.join(workDir, entry), { force: true });
    }
    return Promise.resolve();
  }));
}

async function startFfmpeg() {
  await ensureWorkDir();
  lastStartIso = new Date().toISOString();

  const ffmpegArgs = [
    '-hide_banner',
    '-loglevel', 'warning',
    '-reconnect', '1',
    '-reconnect_streamed', '1',
    '-reconnect_delay_max', '2',
    '-i', inputUrl,
    '-vn',
    '-c:a', 'aac',
    '-b:a', '192k',
    '-ar', '44100',
    '-f', 'hls',
    '-hls_time', '2',
    '-hls_list_size', '6',
    '-hls_delete_threshold', '1',
    '-hls_flags', 'delete_segments+append_list+omit_endlist+program_date_time+independent_segments',
    '-hls_segment_filename', path.join(workDir, 'segment_%05d.ts'),
    playlistPath
  ];

  ffmpegProcess = spawn('ffmpeg', ffmpegArgs, {
    cwd: workDir,
    stdio: ['ignore', 'pipe', 'pipe']
  });

  ffmpegProcess.stdout.on('data', (chunk) => {
    const message = chunk.toString().trim();
    if (message) {
      console.log(`[ffmpeg] ${message}`);
    }
  });

  ffmpegProcess.stderr.on('data', (chunk) => {
    const message = chunk.toString().trim();
    if (message) {
      console.log(`[ffmpeg] ${message}`);
    }
  });

  ffmpegProcess.on('exit', (code, signal) => {
    console.log(`[ffmpeg] exited code=${code ?? 'null'} signal=${signal ?? 'null'}`);
    ffmpegProcess = null;
    if (!shuttingDown) {
      setTimeout(() => {
        startFfmpeg().catch((error) => {
          console.error('[relay] failed to restart ffmpeg', error);
        });
      }, 1000);
    }
  });
}

async function serveFile(response, filePath) {
  try {
    const body = await fs.readFile(filePath);
    response.writeHead(200, {
      'Content-Type': contentTypeFor(filePath),
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      'Access-Control-Allow-Origin': '*'
    });
    response.end(body);
  } catch {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Not ready');
  }
}

const server = createServer(async (request, response) => {
  const requestUrl = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`);
  if (requestUrl.pathname === '/health') {
    let playlistReady = false;
    try {
      await fs.access(playlistPath);
      playlistReady = true;
    } catch {
      playlistReady = false;
    }
    response.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*'
    });
    response.end(JSON.stringify({
      inputUrl,
      playlistReady,
      lastStartIso,
      relayUrl: `http://localhost:${port}/live.m3u8`,
      networkUrls: networkUrls()
    }, null, 2));
    return;
  }

  if (requestUrl.pathname === '/' || requestUrl.pathname === '/index.txt') {
    response.writeHead(200, {
      'Content-Type': 'text/plain; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*'
    });
    response.end([
      'Grand Prix Radio HLS relay',
      `Input URL: ${inputUrl}`,
      `Emulator playlist: http://10.0.2.2:${port}/live.m3u8`,
      ...networkUrls().map((url) => `LAN playlist: ${url}`),
      `Health: http://localhost:${port}/health`
    ].join('\n'));
    return;
  }

  if (requestUrl.pathname === '/live.m3u8') {
    await serveFile(response, playlistPath);
    return;
  }

  if (requestUrl.pathname.startsWith('/segment_')) {
    await serveFile(response, path.join(workDir, path.basename(requestUrl.pathname)));
    return;
  }

  response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  response.end('Not found');
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    shuttingDown = true;
    server.close();
    if (ffmpegProcess) {
      ffmpegProcess.kill('SIGTERM');
    }
    setTimeout(() => process.exit(0), 250);
  });
}

await startFfmpeg();
server.listen(port, host, () => {
  console.log(`[relay] listening on http://${host}:${port}`);
  console.log(`[relay] emulator playlist: http://10.0.2.2:${port}/live.m3u8`);
  for (const url of networkUrls()) {
    console.log(`[relay] LAN playlist: ${url}`);
  }
});