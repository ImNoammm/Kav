// Copies the extension kit's docs, its Kotlin starter and its source template onto this site, under
// docs/extensions/, so the developer page links here and not to the kit's repository. The kit lives outside Kav's
// repository, so the pages are written once and committed; run this again when the kit changes:
//
//   node scripts/kit-docs.mjs <kit checkout>      (or KAV_KIT=<kit checkout>)
//
// Links between the docs become site links, and the API's package is written as Kav 5 names it (kav.kavapp.api).
// It also writes the file for a developer's AI agent: everything above and the API's own sources in one plain file,
// public/docs/extensions/agent.txt (and the same as a page, docs/extensions/agent.md), and the API jar beside it.
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const site = join(dirname(fileURLToPath(import.meta.url)), '..')
const kit = process.argv[2] || process.env.KAV_KIT
if (!kit || !existsSync(join(kit, 'docs'))) throw new Error('give the kit checkout: node scripts/kit-docs.mjs <kit>')
const out = join(site, 'docs', 'extensions')
mkdirSync(out, { recursive: true })

// Kit doc -> page, in the order the developer page lists them.
const DOCS = [
  ['native.md', 'native'],
  ['hooks.md', 'hooks'],
  ['screens.md', 'screens'],
  ['format.md', 'format'],
  ['what-riders-see.md', 'what-riders-see'],
  ['riders-wording.md', 'riders-wording'],
  ['testing.md', 'testing'],
]
const PAGES = new Map([...DOCS, ['../starter-kotlin', 'starter'], ['../template', 'template']])

// What the template's README points to beside it, as this site has it.
const OTHER = { 'add-to-kav.svg': '/img/add-to-kav.svg', LICENSE: '/docs/license' }

// HTML a doc may really mean; any other <word> is a placeholder like <folder>, escaped so Vue doesn't read it.
const HTML = new Set(['a', 'img', 'br', 'p', 'div', 'span', 'details', 'summary', 'sub', 'sup', 'kbd', 'b', 'i', 'em',
  'strong', 'code', 'pre', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'ul', 'ol', 'li', 'hr'])

// The API's package as Kav 5 has it, whatever an older kit wrote.
const renamed = (s) => s.replace(/\b(?:uk\.noammm\.kav|link\.kavapp|kav\.kavapp)\.api\b/g, 'kav.kavapp.api')
  .replace(/\b(?:uk\.noammm\.kavexamples|link\.kavapp\.examples)\b/g, 'kav.kavapp.examples')

function link(target) {
  if (/^([a-z]+:|#|\/)/i.test(target)) return target
  const [path, hash = ''] = target.split('#')
  const page = PAGES.get(path.replace(/^\.\//, '').replace(/\/$/, ''))
  return page ? `/docs/extensions/${page}${hash ? '#' + hash : ''}` : (OTHER[path] ?? target)
}

function prose(text) {
  return text.split(/(`+[^`]*`+)/).map((part, i) => {
    if (i % 2) return part
    part = part.replace(/(\]\()([^)\s]+)(\))/g, (_, a, target, b) => a + link(target) + b)
    return part.replace(/<\/?([A-Za-z][\w-]*)([^>]*)>/g, (tag, name, rest) =>
      HTML.has(name.toLowerCase()) || /^:\/\/|@/.test(rest) ? tag : tag.replace(/</g, '&lt;'))
  }).join('')
}

function convert(markdown) {
  const lines = []
  let fence = null
  for (const line of renamed(markdown).split('\n')) {
    const open = line.match(/^\s*(`{3,}|~{3,})/)
    if (fence) {
      if (open && open[1][0] === fence[0] && open[1].length >= fence.length) fence = null
      lines.push(line)
    } else if (open) {
      fence = open[1]
      lines.push(line)
    } else lines.push(prose(line))
  }
  return lines.join('\n')
}

// v-pre: {{ }} in a doc is text, not a Vue expression.
function page(name, title, body) {
  const front = `---\ntitle: ${JSON.stringify(title)}\neditLink: false\n---\n\n`
  writeFileSync(join(out, name + '.md'), `${front}<div v-pre>\n\n${body.trim()}\n\n</div>\n`)
}

const titleOf = (md, fallback) => (md.match(/^#\s+(.+)$/m) || [, fallback])[1].trim()

for (const [file, name] of DOCS) {
  const md = readFileSync(join(kit, 'docs', file), 'utf8')
  page(name, titleOf(md, name), convert(md))
}

// The starter and the template are code: their README, then each file that matters, as it is.
const FENCE = { kt: 'kotlin', java: 'java', json: 'json', yml: 'yaml' }
function codePage(folder, name, title, lead, files) {
  const readme = readFileSync(join(kit, folder, 'README.md'), 'utf8').replace(/^#\s+.+\n+/, '')
  const shown = files.map((f) => {
    const text = renamed(readFileSync(join(kit, folder, f), 'utf8')).trimEnd()
    return `### \`${f}\`\n\n\`\`\`${FENCE[f.split('.').pop()] ?? ''}\n${text}\n\`\`\``
  }).join('\n\n')
  page(name, title, `# ${title}\n\n${lead}\n\n${convert(readme)}\n\n## The files\n\n${shown}`)
}

codePage('starter-kotlin', 'starter', 'The Kotlin starter',
  'One extension in one Kotlin file, to copy and rename. It comes with the developer kit, in `starter-kotlin/`.',
  ['manifest.json', 'src/org/example/hello/Hello.kt'])
codePage('template', 'template', 'The source template',
  'A GitHub repository ready to be a source: one extension, a README for riders, and a workflow that builds the bundle ' +
  'and attaches it to each release. It comes with the developer kit, in `template/`.',
  ['extensions/my-extension/manifest.json', '.github/workflows/release.yml'])

// ---- For a developer's AI agent: one self-contained file -----------------------------------------------------------

const SITE = 'https://kavapp.link'
const RAW = '/docs/extensions/agent.txt'
const JAR = '/docs/extensions/kav-api.jar'
const publicDir = join(site, 'public', 'docs', 'extensions')
mkdirSync(publicDir, { recursive: true })
copyFileSync(join(kit, 'api', 'kav-api.jar'), join(publicDir, 'kav-api.jar'))

// A kit doc inside the one file: its headings one level down, its links to the other docs as links on this site.
const nested = (md) => renamed(md).replace(/(\]\()([^)\s]+)(\))/g, (_, a, t, b) => {
  const l = link(t)
  return a + (l.startsWith('/') ? SITE + l : l) + b
}).replace(/^(#{1,5}) /gm, (_, h) => '#' + h + ' ')
const codeBlock = (label, file) => `### \`${label}\`\n\n\`\`\`${FENCE[file.split('.').pop()] ?? ''}\n${renamed(readFileSync(file, 'utf8')).trimEnd()}\n\`\`\``
const apiDir = join(kit, 'api', 'src', 'kav', 'kavapp', 'api')
const api = readdirSync(apiDir).filter((f) => f.endsWith('.java')).sort()

const agent = [
  '# Building a Kav extension',
  '',
  `Everything an AI coding agent needs to build an extension for Kav, the Android transit app for Israel, in one file. Nothing else is needed. The same file as a page: ${SITE}/docs/extensions/agent. For Kav 5 and newer, API level 3.`,
  '',
  '## What a Kav extension is',
  '',
  '- Android code, in Kotlin or Java, compiled into one `classes.dex` that Kav loads and runs inside itself, with full access: it can do anything Kav can. There is no other kind.',
  '- A folder with a `manifest.json` (what it is, what it adds, its settings and the words riders see) and that dex. Its entry class implements `kav.kavapp.api.KavExtension`.',
  '- Published as a source: a public GitHub repository whose latest GitHub Release carries one bundle file (`.kavb`) with all its extensions. Riders add the repository in Kav, under Settings, Extensions, Sources, or with a link: `https://kavapp.link/add-source?github=OWNER/REPO`. A rider can also add a `.kavb` file from the phone.',
  '- Each extension comes in off. It runs once the rider switches it on, and then each time Kav starts.',
  '',
  '## Rules',
  '',
  '- Declare what the extension does in its manifest, and nothing more. Riders see it on the extension\'s page.',
  '- You are the sole author and publisher of your extension and fully responsible for it.',
  '- You must have the right to use every service, API and piece of data your extension touches, and follow their terms.',
  '- Don\'t present the extension as made, endorsed or approved by Kav, or use Kav\'s name or logo as your branding. "for Kav" in a description is fine.',
  '- Kav can block an extension found stealing, spreading malware, impersonating someone or abusing a bug in Kav.',
  '',
  '## What you need',
  '',
  `- Kav's API jar: ${SITE}${JAR} (a few KB). Or compile the four Java files under "The API" below yourself.`,
  '- A JDK, the Android SDK (its `android.jar` and `d8`) and, for Kotlin, the Kotlin compiler.',
  '- The developer kit\'s `kavx` command line does every step (check, bundle, verify). Without it, follow "Kotlin and Java" and "The bundle format" below by hand.',
  '- A phone or an emulator with Kav 5 or newer to try it.',
  '',
  ...DOCS.filter(([f]) => f === 'native.md').map(([f]) => nested(readFileSync(join(kit, 'docs', f), 'utf8'))),
  '',
  '## The API',
  '',
  'Kav\'s own sources for `kav.kavapp.api`, as they are in Kav 5. Every interface and method an extension can call.',
  '',
  ...api.map((f) => codeBlock(`kav/kavapp/api/${f}`, join(apiDir, f)) + '\n'),
  ...DOCS.filter(([f]) => f !== 'native.md').map(([f]) => nested(readFileSync(join(kit, 'docs', f), 'utf8')) + '\n'),
  '## The Kotlin starter',
  '',
  'One extension in one Kotlin file. Copy it, rename it, and build it.',
  '',
  nested(readFileSync(join(kit, 'starter-kotlin', 'README.md'), 'utf8').replace(/^#\s+.+\n+/, '')),
  '',
  codeBlock('manifest.json', join(kit, 'starter-kotlin', 'manifest.json')),
  '',
  codeBlock('src/org/example/hello/Hello.kt', join(kit, 'starter-kotlin', 'src/org/example/hello/Hello.kt')),
  '',
  '## Publishing: the source template',
  '',
  nested(readFileSync(join(kit, 'template', 'README.md'), 'utf8').replace(/^#\s+.+\n+/, '')).replace(/\]\(add-to-kav\.svg\)/, `](${SITE}/img/add-to-kav.svg)`).replace(/\]\(LICENSE\)/, `](${SITE}/docs/license)`),
  '',
  codeBlock('.github/workflows/release.yml', join(kit, 'template', '.github/workflows/release.yml')),
  '',
].join('\n')
writeFileSync(join(publicDir, 'agent.txt'), agent)
page('agent', 'For your agent', `> This page as plain text, to give an AI coding agent: <a href="${RAW}">${SITE}${RAW}</a>\n\n${convert(agent.replace(/^# .+\n/, '# For your agent\n'))}`)

console.log(`kit-docs: ${DOCS.length} docs, the starter and the template written to docs/extensions/`)
