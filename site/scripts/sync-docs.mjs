// Copies the repo's own Markdown into docs/ before every build, so the site never keeps a second copy of them.
// Links between these files become site links; links to anything else in the repo go to it on GitHub.
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join, normalize, posix } from 'node:path'
import { fileURLToPath } from 'node:url'

const site = join(dirname(fileURLToPath(import.meta.url)), '..')
// The repo root: the site's parent folder, or KAV_REPO when the site is built away from the repo.
const repo = process.env.KAV_REPO || join(site, '..')
const GITHUB = 'https://github.com/ImNoammm/kav/blob/main/'
const RAW = 'https://raw.githubusercontent.com/ImNoammm/kav/main/'

// Repo file -> page under docs/, in sidebar order.
export const DOCS = [
  ['server/selfhost/README.md', 'self-hosting'],
  ['server/API.md', 'api'],
  ['server/LIVE.md', 'live-sources'],
  ['server/PLANNER.md', 'planner'],
  ['server/PREDICTOR.md', 'predictor'],
  ['server/eta/train/README.md', 'training'],
]
const PAGES = new Map(DOCS)

// HTML a doc may really mean; any other <word> is text (a placeholder like <code> or <stop>) and is escaped, or Vue
// would read it as an element.
const HTML = new Set(['a', 'img', 'br', 'p', 'div', 'span', 'details', 'summary', 'sub', 'sup', 'kbd', 'b', 'i', 'em',
  'strong', 'code', 'pre', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'ul', 'ol', 'li', 'picture', 'source', 'hr'])

function link(target, from) {
  if (/^([a-z]+:|#|\/)/i.test(target)) return target
  const [path, hash = ''] = target.split('#')
  const file = posix.normalize(posix.join(posix.dirname(from), path))
  const page = PAGES.get(file)
  if (page) return `/docs/${page}${hash ? '#' + hash : ''}`
  if (/\.(png|jpe?g|gif|svg|webp)$/i.test(file)) return RAW + file
  return GITHUB + file + (hash ? '#' + hash : '')
}

function prose(text, from) {
  // Inline code stays as it is; Markdown links and bare tags in the rest are fixed.
  return text.split(/(`+[^`]*`+)/).map((part, i) => {
    if (i % 2) return part
    part = part.replace(/(\]\()([^)\s]+)(\))/g, (_, a, target, b) => a + link(target, from) + b)
    part = part.replace(/(<(?:img|a|source)\b[^>]*?\s(?:src|href|srcset)=")([^"]+)(")/g, (_, a, target, b) => a + link(target, from) + b)
    return part.replace(/<\/?([A-Za-z][\w-]*)([^>]*)>/g, (tag, name, rest) =>
      HTML.has(name.toLowerCase()) || /^:\/\/|@/.test(rest) ? tag : tag.replace(/</g, '&lt;'))
  }).join('')
}

function convert(markdown, from) {
  const out = []
  let fence = null
  for (const line of markdown.split('\n')) {
    const open = line.match(/^\s*(`{3,}|~{3,})/)
    if (fence) {
      if (open && open[1][0] === fence[0] && open[1].length >= fence.length) fence = null
      out.push(line)
    } else if (open) {
      fence = open[1]
      out.push(line)
    } else {
      out.push(prose(line, from))
    }
  }
  // v-pre: {{ }} in a doc is text, not a Vue expression.
  return `<div v-pre>\n\n${out.join('\n')}\n\n</div>\n`
}

let missing = 0
for (const [file, page] of DOCS) {
  const source = join(repo, file)
  if (!existsSync(source)) {
    console.warn(`sync-docs: ${file} is missing, docs/${page}.md left out`)
    missing++
    continue
  }
  const markdown = readFileSync(source, 'utf8')
  const title = (markdown.match(/^#\s+(.+)$/m) || [, page])[1].trim()
  const front = `---\ntitle: ${JSON.stringify(title)}\neditLink: true\nsource: ${JSON.stringify(file)}\n---\n\n`
  writeFileSync(join(site, 'docs', page + '.md'), front + convert(markdown, file))
}
console.log(`sync-docs: ${DOCS.length - missing} of ${DOCS.length} docs copied from ${normalize(repo)}`)
if (missing) process.exitCode = 1
