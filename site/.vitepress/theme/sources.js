// Extension sources as Kav reads them, for the add-source page, the badge maker and the community list's generator
// (scripts/community.mjs), so the site never accepts a link Kav refuses, or the other way round. A source is a GitHub
// repository, "owner/repo", read the way the app's GitHub.repoOf reads it (android/app/src/main/java/uk/noammm/kav/ext/
// GitHub.kt). Plain JavaScript, so Node runs it as it is.

export const LINK = 'https://kavapp.link/add-source'
export const SCHEME = 'kav://add-source'
export const PACKAGE = 'uk.noammm.kav'
// Where the developer kit's docs will be. NOT PUBLIC YET: the one place to change once the kit has its address.
export const KIT = 'https://github.com/ImNoammm/kav-extension-kit'
// Where the community list takes submissions: issues on Kav's own repository, read by scripts/community.mjs.
export const ISSUES = 'https://github.com/ImNoammm/kav/issues'
export const SUBMIT_TITLE = 'Kav source: '

const OWNER = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/
const REPO = /^[A-Za-z0-9._-]{1,100}$/

// GitHub.plain: the first two parts of a path, when they name a repository.
function plain(p) {
  const parts = p.split('/')
  if (parts.length < 2) return null
  const [owner, repo] = parts
  if (!OWNER.test(owner) || !REPO.test(repo) || repo === '.' || repo === '..') return null
  return `${owner}/${repo}`
}

// java.net.URI's rules, as far as they decide what Kav takes: these characters, a % not followed by two hex digits, or
// more than one # make the whole address unreadable.
const URI_BAD = /[\x00-\x20"<>\\^`{|}\x7f-\x9f\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]|%(?![0-9A-Fa-f]{2})/
const LABEL = '[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?'
const HOST = new RegExp(`^(?:(?:${LABEL}\\.)*[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.?|\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:.]+\\])$`)

// new URI(text): its scheme, host, decoded path and raw query, or null where Java throws or finds no host.
function uri(text) {
  if (URI_BAD.test(text) || (text.match(/#/g) || []).length > 1) return null
  const m = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#.*)?$/.exec(text)
  if (!m) return null
  const hp = /^(?:[^@]*@)?(\[[^\]]*\]|[^:]*)(?::\d*)?$/.exec(m[2])
  const host = hp && HOST.test(hp[1]) ? hp[1] : null
  let path = m[3]
  try { path = decodeURIComponent(path) } catch {}
  return { scheme: m[1], host, path, query: m[4] ?? null }
}

// java.net.URLDecoder.decode: + is a space, %xy a byte, the bytes UTF-8.
function formDecode(s) {
  const bytes = []
  const utf8 = new TextEncoder()
  for (let i = 0; i < s.length; ) {
    const c = s[i]
    if (c === '+') { bytes.push(32); i++ }
    else if (c === '%' && /^[0-9A-Fa-f]{2}$/.test(s.slice(i + 1, i + 3))) { bytes.push(parseInt(s.slice(i + 1, i + 3), 16)); i += 3 }
    else { const ch = String.fromCodePoint(s.codePointAt(i)); bytes.push(...utf8.encode(ch)); i += ch.length }
  }
  return new TextDecoder().decode(new Uint8Array(bytes))
}

// The query of an add-source link: its github= (the last one when there are several), read as a repository.
function fromRawQuery(raw) {
  const q = new Map()
  for (const pair of (raw ?? '').split('&')) {
    const eq = pair.indexOf('=')
    q.set(eq < 0 ? pair : pair.slice(0, eq), formDecode(eq < 0 ? '' : pair.slice(eq + 1)))
  }
  return q.has('github') ? plain(q.get('github')) : null
}

// Kotlin's trim(): Character.isWhitespace or isSpaceChar at either end.
const WS = '[\\t-\\r\\x1c-\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]'
const trimKotlin = (s) => s.replace(new RegExp(`^${WS}+|${WS}+$`, 'g'), '')

// GitHub.repoOf: "owner/repo" from what the rider typed or a link: owner/repo, github.com/owner/repo, an https GitHub
// address, or an add-source link (kav://add-source?github=owner/repo, https://kavapp.link/add-source?github=...).
// Null for anything else.
export function repoOf(text) {
  const t = trimKotlin(text)
  const link = uri(t)
  if (link && ((link.scheme === 'kav' && link.host === 'add-source') ||
      (link.scheme === 'https' && link.host === 'kavapp.link' && link.path.replace(/\/+$/, '') === '/add-source'))) {
    return fromRawQuery(link.query)
  }
  let p = t
  for (const prefix of ['https://', 'http://', 'www.']) if (p.startsWith(prefix)) p = p.slice(prefix.length)
  if (p.slice(0, 11).toLowerCase() === 'github.com/') p = p.slice(11)
  else if (p.includes('://')) return null
  p = p.replace(/\/+$/, '')
  if (p.endsWith('.git')) p = p.slice(0, -4)
  return plain(p)
}

// What this site's own add-source page was given after its ?: read as Kav reads the same query on kavapp.link.
export const fromQuery = (raw) => (raw == null ? null : repoOf(`${LINK}?${raw}`))

export const query = (repo) => `github=${repo}`
export const link = (repo) => `${LINK}?${query(repo)}`
export const appLink = (repo) => `${SCHEME}?${query(repo)}`

// An Android intent for kav://add-source aimed at Kav's package: Kav opens it when it's installed, and the browser
// goes to fallback when it isn't.
export function intentLink(repo, fallback) {
  return `intent://add-source?${query(repo)}#Intent;scheme=kav;package=${PACKAGE};` +
    `S.browser_fallback_url=${encodeURIComponent(fallback)};end`
}

// Hub.Source.github: one key for one repository however it was typed.
export const key = (repo) => `github:${repo.toLowerCase()}`

// What a page shows for a repository: who owns it, its address and page, and the name Kav uses until it has read its
// bundle (the repository's).
export function describe(repo) {
  const [owner, name] = repo.split('/')
  return { owner, shown: `github.com/${repo}`, page: `https://github.com/${repo}`, fallbackName: name }
}

// The "Add to Kav" badge for a developer's README.
export const BADGE = 'https://kavapp.link/img/add-to-kav.svg'
export const BADGE_HE = 'https://kavapp.link/img/add-to-kav-he.svg'
export function badge(repo, hebrew = false) {
  const href = link(repo)
  const src = hebrew ? BADGE_HE : BADGE
  const alt = hebrew ? 'הוספה ל-Kav' : 'Add to Kav'
  return {
    link: href,
    markdown: `[![${alt}](${src})](${href})`,
    html: `<a href="${href}"><img src="${src}" alt="${alt}" height="40"></a>`,
  }
}
