// Human-sounding voices for the app. The relay turns text into an MP3 and the app plays it.
// Free default: Microsoft Edge "Read Aloud" neural voices (unofficial, no key, can change without notice).
// Steadier paid options: set OPENAI_API_KEY or ELEVENLABS_API_KEY (and optionally TTS_PROVIDER) in Render -> Environment.
const PROVIDER = (process.env.TTS_PROVIDER ||
  (process.env.ELEVENLABS_API_KEY ? 'elevenlabs' : process.env.OPENAI_API_KEY ? 'openai' : 'edge')).toLowerCase();

const VOICES = {
  edge: [['en-US-AriaNeural', 'Aria (US, woman)'], ['en-GB-RyanNeural', 'Ryan (UK, man)'], ['en-US-GuyNeural', 'Guy (US, man)'],
    ['en-GB-SoniaNeural', 'Sonia (UK, woman)'], ['en-US-JennyNeural', 'Jenny (US, woman)'], ['en-AU-NatashaNeural', 'Natasha (Australia, woman)'],
    ['en-US-AndrewNeural', 'Andrew (US, man)'], ['en-US-EmmaNeural', 'Emma (US, woman)']],
  openai: [['nova', 'Nova'], ['onyx', 'Onyx'], ['shimmer', 'Shimmer'], ['echo', 'Echo'], ['fable', 'Fable'], ['alloy', 'Alloy']],
  elevenlabs: [['21m00Tcm4TlvDq8ikWAM', 'Rachel'], ['pNInz6obpgDQGcFmaJgB', 'Adam'], ['EXAVITQu4vr4xnSDxMaL', 'Sarah'],
    ['ErXwobaYiN019PkySvjV', 'Antoni'], ['MF3mGyEYCl7XYWbV9V6O', 'Elli'], ['TxGEqnHWrfWFTfGW9XjX', 'Josh']]
};

function keyMissing() {
  return (PROVIDER === 'openai' && !process.env.OPENAI_API_KEY) || (PROVIDER === 'elevenlabs' && !process.env.ELEVENLABS_API_KEY);
}

function config() {
  const bad = keyMissing();
  return {
    provider: PROVIDER,
    enabled: !bad && !!VOICES[PROVIDER],
    hint: bad ? 'Add the API key for ' + PROVIDER + ' in Render -> Environment.' : '',
    voices: (VOICES[PROVIDER] || []).map(([id, label]) => ({ id, label }))
  };
}

const hits = new Map(); // simple per-user limit: 40 voice requests a minute
function allowed(uid) {
  const now = Date.now(), a = (hits.get(uid) || []).filter(t => now - t < 60000);
  if (a.length >= 40) return false;
  a.push(now); hits.set(uid, a);
  if (hits.size > 500) for (const [k, v] of hits) if (!v.some(t => now - t < 60000)) hits.delete(k);
  return true;
}

const xmlEsc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');

function withTimeout(p, ms, what) {
  let t; return Promise.race([p, new Promise((_, rej) => { t = setTimeout(() => rej(new Error(what + ' timed out')), ms); })]).finally(() => clearTimeout(t));
}

async function synth(text, voice) {
  const list = VOICES[PROVIDER] || [];
  if (!list.length) throw new Error('No voices for provider ' + PROVIDER);
  if (!list.some(v => v[0] === voice)) voice = list[0][0];
  if (PROVIDER === 'openai') {
    const r = await withTimeout(fetch('https://api.openai.com/v1/audio/speech', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + process.env.OPENAI_API_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: process.env.OPENAI_TTS_MODEL || 'gpt-4o-mini-tts', voice, input: text, response_format: 'mp3' })
    }), 25000, 'OpenAI voice');
    if (!r.ok) throw new Error('OpenAI voice error ' + r.status);
    return Buffer.from(await r.arrayBuffer());
  }
  if (PROVIDER === 'elevenlabs') {
    const r = await withTimeout(fetch('https://api.elevenlabs.io/v1/text-to-speech/' + encodeURIComponent(voice) + '?output_format=mp3_44100_64', {
      method: 'POST',
      headers: { 'xi-api-key': process.env.ELEVENLABS_API_KEY, 'Content-Type': 'application/json', Accept: 'audio/mpeg' },
      body: JSON.stringify({ text, model_id: process.env.ELEVENLABS_MODEL || 'eleven_turbo_v2_5' })
    }), 25000, 'ElevenLabs voice');
    if (!r.ok) throw new Error('ElevenLabs voice error ' + r.status);
    return Buffer.from(await r.arrayBuffer());
  }
  // edge (free)
  const { MsEdgeTTS, OUTPUT_FORMAT } = require('msedge-tts');
  const tts = new MsEdgeTTS();
  try {
    await withTimeout(tts.setMetadata(voice, OUTPUT_FORMAT.AUDIO_24KHZ_48KBITRATE_MONO_MP3), 12000, 'Voice connection');
    const { audioStream } = tts.toStream(xmlEsc(text));
    const parts = [];
    await withTimeout(new Promise((resolve, reject) => {
      audioStream.on('data', d => parts.push(d));
      audioStream.on('close', resolve);
      audioStream.on('error', reject);
    }), 25000, 'Voice');
    return Buffer.concat(parts);
  } finally { try { tts.close && tts.close(); } catch {} }
}

module.exports = { config, synth, allowed, PROVIDER };
