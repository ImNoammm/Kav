// Writes the community list of extension sources (kavapp.link/community, PLAN.md 9.3): fully automatic, with nobody at
// Kav looking at what goes in. A source is a GitHub repository, and a candidate when it is
//   - public, with the kav-extension topic,
//   - a line in extensions/submissions.txt, or
//   - named in an issue on Kav's repository titled "Kav source: <address>" with the developer terms ticked (the site's
//     Submit button opens one already filled in),
// and it is listed if, and only if, its latest GitHub Release carries exactly one .kavb bundle that the developer kit
// reads as Kav would (kavx verify), with at least one extension this Kav runs, and it is on neither
// extensions/exclusions.txt nor the security block list. What is left out, and why, goes to the log, never to the page.
// The list is in one order, by name, and nothing in it is featured, pinned or placed by hand.
//
//   node scripts/community.mjs --kit <kit checkout>              the real thing (GITHUB_TOKEN, if set, for GitHub's API)
//   node scripts/community.mjs --kit <kit> --offline <folder>    every https GET read from <folder>/<host>/<path> instead
//   node scripts/community.mjs --topic-only                      only ask GitHub which repositories have the topic
//   node scripts/community.mjs --kit <kit> --bundles <folder>    save listed bundles for the APK hook check
//
// Writes extensions/listing.json (the page's data), extensions/community.log, and the sources' pictures into
// public/community/, so the page asks no other host for anything.
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve as resolvePath } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'
import { SUBMIT_TITLE, describe, key, link, query, repoOf } from '../.vitepress/theme/sources.js'

const site = join(dirname(fileURLToPath(import.meta.url)), '..')
const { values: o } = parseArgs({
  options: {
    kit: { type: 'string' }, offline: { type: 'string' }, now: { type: 'string' }, 'topic-only': { type: 'boolean' },
    out: { type: 'string' }, log: { type: 'string' }, images: { type: 'string' }, dir: { type: 'string' },
    bundles: { type: 'string' },
  },
})
const data = o.dir || join(site, 'extensions')
const OUT = o.out || join(data, 'listing.json')
const LOG = o.log || join(data, 'community.log')
const IMAGES = o.images || join(site, 'public', 'community')
const REPO = 'ImNoammm/kav'
const TOPIC = 'kav-extension'
const ACCEPT = /^\s*[-*]\s*\[[xX]\]\s*I accept the developer terms/m
// The security block list (PLAN.md 9.1), as Kav reads it (BlockList.kt), checked with the key Kav has (BlockList.KEY).
// KAV_BLOCK_KEY (base64 SubjectPublicKeyInfo) and KAV_BLOCK_LIST (a file) override them, for tests.
const BLOCK_KEY = process.env.KAV_BLOCK_KEY ?? 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOEx5RfetEpOsTXCocAwyynzC1FqPyoJhEjT2Whp//iefb5nO8JnwDjsp22BeMmD1v7ADztvuzq0p6hiEGKjjHg=='
const BLOCK_FILE = process.env.KAV_BLOCK_LIST || join(site, 'public', 'extensions', 'block-list.json')
const BLOCK_REASONS = new Set(['malware', 'credential-theft', 'impersonation', 'kav-bug-abuse'])

const log = []
const say = (line) => { log.push(line); console.log(line) }

// ---- Reading the web, or a folder that stands in for it --------------------------------------------------------------

class Missing extends Error {
  constructor(message, status) { super(message); this.status = status }
}

// An https GET of at most max bytes, the way Kav's own fetch does it (ExtApp.kt, Http): https only, at most five
// redirects, each https, and a deadline.
async function web(url, max, headers = {}) {
  if (o.offline) {
    const u = new URL(url)
    const file = resolvePath(o.offline, u.hostname, decodeURIComponent(u.pathname).replace(/^\/+/, ''))
    if (!file.startsWith(resolvePath(o.offline) + '/')) throw new Missing(`${url}: outside the fixtures`)
    if (!existsSync(file)) throw new Missing(`${url}: HTTP 404`)
    const bytes = readFileSync(file)
    if (bytes.length > max) throw new Missing(`${url}: larger than ${max} bytes`)
    return bytes
  }
  let at = url
  for (let hop = 0; hop < 5; hop++) {
    if (!at.startsWith('https://')) throw new Missing(`${url}: not https`)
    const res = await fetch(at, {
      redirect: 'manual', signal: AbortSignal.timeout(Math.min(300_000, Math.max(30_000, max / 16))),
      headers: { 'User-Agent': 'kav-site-community', ...headers },
    })
    if (res.status >= 300 && res.status < 400 && res.headers.get('location')) {
      at = new URL(res.headers.get('location'), at).href
      continue
    }
    if (!res.ok) throw new Missing(`${url}: HTTP ${res.status}`, res.status)
    const bytes = Buffer.from(await res.arrayBuffer())
    if (bytes.length > max) throw new Missing(`${url}: larger than ${max} bytes`)
    return bytes
  }
  throw new Missing(`${url}: too many redirects`)
}

const api = (url) => web(url, 8 << 20, {
  Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
  ...(process.env.GITHUB_TOKEN && !o.offline ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
}).then((b) => JSON.parse(b.toString('utf8')))

// ---- Candidates -------------------------------------------------------------------------------------------------

// Public GitHub repositories with the topic. GitHub's search gives at most 1000.
async function topic() {
  const found = []
  for (let page = 1; page <= 10; page++) {
    const r = await api(`https://api.github.com/search/repositories?q=${encodeURIComponent(`topic:${TOPIC} is:public`)}&per_page=100&page=${page}`)
    const items = Array.isArray(r.items) ? r.items : []
    for (const item of items) if (typeof item.full_name === 'string') found.push(item.full_name)
    if (items.length < 100) break
  }
  return found
}

// Submitted through the site: issues on Kav's repository, open or closed, titled "Kav source: <address>", with the
// developer terms ticked in the body. Only the title's address is taken from what anyone typed.
async function issues() {
  const found = []
  for (let page = 1; page <= 10; page++) {
    const q = encodeURIComponent(`repo:${REPO} is:issue in:title "${SUBMIT_TITLE.trim()}"`)
    const r = await api(`https://api.github.com/search/issues?q=${q}&per_page=100&page=${page}`)
    const items = Array.isArray(r.items) ? r.items : []
    for (const it of items) {
      if (typeof it.title !== 'string' || !it.title.startsWith(SUBMIT_TITLE)) continue
      found.push({ number: it.number, text: it.title.slice(SUBMIT_TITLE.length).trim(), accepted: ACCEPT.test(it.body || '') })
    }
    if (items.length < 100) break
  }
  return found
}

// A list file: one address per line, as it would be typed into Kav; # starts a comment.
function listFile(name) {
  const file = join(data, name)
  if (!existsSync(file)) return []
  return readFileSync(file, 'utf8').split('\n').map((l, i) => ({ line: i + 1, text: l.replace(/#.*/, '').trim() })).filter((l) => l.text)
}

// ---- Checking a source ------------------------------------------------------------------------------------------

// GitHub.latest: the latest release's one .kavb bundle, as Kav finds it.
async function latest(repo) {
  let r
  try { r = await api(`https://api.github.com/repos/${repo}/releases/latest`) } catch (e) {
    if (e instanceof Missing && / HTTP 404$/.test(e.message)) throw new Missing('no such repository, or it has no release yet')
    throw e
  }
  if (!Array.isArray(r.assets)) throw new Missing('its latest release has no files')
  const bundles = r.assets.filter((a) => typeof a?.name === 'string' && a.name.toLowerCase().endsWith('.kavb'))
  if (bundles.length === 0) throw new Missing('its latest release has no .kavb bundle')
  if (bundles.length > 1) throw new Missing('its latest release has more than one .kavb bundle')
  const url = bundles[0].browser_download_url
  if (typeof url !== 'string' || !url.startsWith('https://')) throw new Missing("GitHub's answer isn't readable")
  return { url, published: typeof r.published_at === 'string' ? r.published_at : null }
}

// Width and height from a PNG's or a JPEG's header, within Kav's limits (SourceImages.size); null for anything else.
function imageKind(b) {
  if (b.length > 256 << 10) return null
  let w, h, kind
  if (b.length >= 24 && b.readUInt32BE(0) === 0x89504e47 && b.toString('latin1', 12, 16) === 'IHDR') {
    kind = 'png'; w = b.readUInt32BE(16); h = b.readUInt32BE(20)
  } else if (b.length >= 4 && b[0] === 0xff && b[1] === 0xd8) {
    for (let i = 2; i + 9 < b.length; ) {
      if (b[i] !== 0xff) return null
      const marker = b[i + 1], len = b.readUInt16BE(i + 2)
      if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) { kind = 'jpg'; h = b.readUInt16BE(i + 5); w = b.readUInt16BE(i + 7); break }
      if (len < 2) return null
      i += 2 + len
    }
  }
  return kind && w >= 1 && w <= 1024 && h >= 1 && h <= 1024 ? kind : null
}

// SourceImages: the picture its bundle brings, else its owner's picture on GitHub.
async function picture(repo, bundle) {
  if (bundle.icon) {
    const kind = imageKind(bundle.icon)
    if (kind) return { bytes: bundle.icon, kind }
  }
  try {
    const b = await web(`https://github.com/${encodeURIComponent(repo.split('/')[0])}.png?size=128`, 256 << 10)
    const kind = imageKind(b)
    return kind ? { bytes: b, kind } : null
  } catch { return null }
}

// The block list, verified with its key the way Kav reads it: its entries of a kind and a reason Kav knows.
function blockList(kit) {
  const env = kit.open(readFileSync(BLOCK_FILE), 'block-list', 1 << 20)
  const p = env.payloadFrom([kit.fingerprint(kit.decode64(BLOCK_KEY))], 512 << 10)
  if (p.format !== 1) throw new Error('unknown block list format')
  return (Array.isArray(p.entries) ? p.entries : []).flatMap((e) => {
    const kind = ['github', 'bundle'].find((k) => e && typeof e[k] === 'string')
    if (!kind || !BLOCK_REASONS.has(e.reason)) return []
    return [{ kind, value: e[kind].toLowerCase(), reason: e.reason }]
  })
}

// BlockList.source: the entry that names this repository, or the bundle it carries.
function blocked(list, repo, sha256 = '') {
  const e = list.find((e) => (e.kind === 'github' && e.value === repo.toLowerCase()) || (e.kind === 'bundle' && sha256 !== '' && e.value === sha256))
  return e ? e.reason : null
}

// ---- The run ----------------------------------------------------------------------------------------------------

async function loadKit(dir) {
  if (!dir || !existsSync(join(dir, 'lib', 'package.js'))) throw new Error('give the developer kit with --kit <folder> or KAV_KIT')
  const lib = (f) => import(pathToFileURL(join(resolvePath(dir), 'lib', f)).href)
  const [pkg, signing, format] = await Promise.all([lib('package.js'), lib('signing.js'), lib('format.js')])
  return { readBundle: pkg.readBundle, open: signing.open, fingerprint: signing.fingerprint, decode64: signing.decode64, maxArchive: format.ARCHIVE.maxArchive }
}

async function main() {
  if (o['topic-only']) {
    const names = await topic()
    console.log(`GitHub: ${names.length} public repositories with the ${TOPIC} topic${names.length ? ': ' + names.join(', ') : ''}`)
    return
  }
  const kit = await loadKit(o.kit || process.env.KAV_KIT)
  const now = o.now ? Date.parse(o.now) : Date.now()
  say(`community list, ${new Date(now).toISOString()}${o.offline ? `, offline from ${o.offline}` : ''}`)

  const candidates = new Map()
  const add = (repo, via) => {
    const k = key(repo)
    if (!candidates.has(k)) candidates.set(k, { repo, via: [] })
    candidates.get(k).via.push(via)
  }
  try {
    for (const name of await topic()) {
      const r = repoOf(name)
      if (r) add(r, 'topic')
      else say(`skipped topic result ${JSON.stringify(name)}: not a GitHub repository Kav can use`)
    }
  } catch (e) { say(`the topic search failed, its sources are missing from this run: ${e.message}`); process.exitCode = 1 }
  for (const l of listFile('submissions.txt')) {
    const r = repoOf(l.text)
    if (r) add(r, `submissions.txt:${l.line}`)
    else say(`skipped submissions.txt line ${l.line}: not a GitHub repository Kav can use`)
  }
  try {
    for (const it of await issues()) {
      const r = repoOf(it.text)
      if (!r) say(`skipped issue #${it.number}: not a GitHub repository Kav can use`)
      else if (!it.accepted) say(`skipped issue #${it.number} (${key(r)}): the developer terms aren't ticked`)
      else add(r, `issue #${it.number}`)
    }
  } catch (e) { say(`the issue search failed, its sources are missing from this run: ${e.message}`); process.exitCode = 1 }

  const excluded = new Set(listFile('exclusions.txt').map((l) => repoOf(l.text)).filter(Boolean).map(key))
  let blocks
  try {
    blocks = blockList(kit)
    say(`block list: ${blocks.length} entries`)
  } catch (e) {
    say(`the block list didn't verify, so nothing is listed this run: ${e.message}`)
    process.exitCode = 1
    return
  }

  rmSync(IMAGES, { recursive: true, force: true })
  const listed = []
  for (const [k, { repo, via }] of candidates) {
    const from = `(from ${via.join(', ')})`
    if (excluded.has(k)) { say(`left out ${k} ${from}: on exclusions.txt`); continue }
    const early = blocked(blocks, repo)
    if (early) { say(`left out ${k} ${from}: on the block list (${early})`); continue }
    let release, bytes
    try {
      release = await latest(repo)
      bytes = await web(release.url, kit.maxArchive)
    } catch (e) {
      if (o.bundles && (!(e instanceof Missing) || (e.status && e.status !== 404))) throw e
      say(`left out ${k} ${from}: ${e.message}`); continue
    }
    let bundle
    try { bundle = kit.readBundle(bytes) } catch (e) { say(`left out ${k} ${from}: ${e.message}`); continue }
    const runs = bundle.extensions.filter((x) => !x.older)
    if (!runs.length) { say(`left out ${k} ${from}: it has no extension this Kav runs`); continue }
    const late = blocked(blocks, repo, bundle.sha256)
    if (late) { say(`left out ${k} ${from}: on the block list (${late})`); continue }

    if (o.bundles) {
      const [owner, name] = repo.toLowerCase().split('/')
      mkdirSync(join(o.bundles, owner), { recursive: true })
      writeFileSync(join(o.bundles, owner, `${name}.kavb`), bytes)
    }

    const d = describe(repo)
    const pic = await picture(repo, bundle)
    let image = null
    if (pic) {
      mkdirSync(IMAGES, { recursive: true })
      image = `${createHash('sha256').update(k).digest('hex').slice(0, 16)}.${pic.kind}`
      writeFileSync(join(IMAGES, image), pic.bytes)
    }
    const name = bundle.name || d.fallbackName
    listed.push({
      key: k, path: repo, name, owner: d.owner, page: d.page, image, extensions: runs.length,
      updated: release.published, add: query(repo), issues: `${d.page}/issues`,
    })
    say(`listed ${k} ${from}: "${name}", ${runs.length} extensions, ${link(repo)}`)
  }

  // The one order: by name, A to Z, letters compared without case or accents; the address breaks a tie.
  listed.sort((x, y) => x.name.localeCompare(y.name, 'en', { sensitivity: 'base' }) || x.key.localeCompare(y.key))
  writeFileSync(OUT, JSON.stringify({ generated: new Date(now).toISOString(), order: 'name', sources: listed }, null, 2) + '\n')
  say(`${listed.length} listed of ${candidates.size} candidates`)
}

try { await main() } catch (e) { say(`failed: ${e.message}`); process.exitCode = 1 }
if (!o['topic-only']) writeFileSync(LOG, log.join('\n') + '\n')
