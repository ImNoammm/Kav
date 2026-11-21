import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig } from 'vitepress'
import { he, searchHebrew } from './he'

const GITHUB = 'https://github.com/ImNoammm/kav'
const DESCRIPTION = 'Public transport in Israel, without the ads, the account or the tracking.'

function githubSlug(text: string) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s/g, '-')
}

export default defineConfig({
  lang: 'en',
  locales: { root: { label: 'English', lang: 'en', dir: 'ltr' }, he },
  title: 'Kav',
  description: DESCRIPTION,
  cleanUrls: true,
  // Light or dark as the device is, with no switch of its own.
  appearance: 'force-auto',
  lastUpdated: false,
  // extensions/ holds the community list's data and notes (extensions/README.md), not pages.
  srcExclude: ['README.md', 'extensions/**'],
  head: [
    ['link', { rel: 'icon', href: '/favicon.ico', sizes: '32x32' }],
    ['link', { rel: 'icon', href: '/img/icon.svg', type: 'image/svg+xml' }],
    ['link', { rel: 'apple-touch-icon', href: '/img/apple-touch-icon.png' }],
    ['meta', { name: 'theme-color', content: '#ffffff', media: '(prefers-color-scheme: light)' }],
    ['meta', { name: 'theme-color', content: '#000000', media: '(prefers-color-scheme: dark)' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Kav' }],
    ['meta', { property: 'og:image', content: 'https://kavapp.link/img/icon-512.png' }],
    ['meta', { name: 'twitter:card', content: 'summary' }],
  ],
  transformPageData(page, { siteConfig }) {
    const path = '/' + page.relativePath.replace(/(?:index)?\.md$/, '')
    const englishPath = path.replace(/^\/he\//, '/')
    // A page has a Hebrew twin when site/he/ has the same file. The language link in Layout.vue reads this too.
    const english = page.relativePath.replace(/^he\//, '')
    const translated = existsSync(join(siteConfig.srcDir, 'he', english))
    page.frontmatter.translated = translated
    page.frontmatter.head ??= []
    page.frontmatter.head.push(['link', { rel: 'canonical', href: `https://kavapp.link${path}` }])
    if (translated) {
      for (const [lang, href] of [['en', englishPath], ['he', '/he' + (englishPath === '/' ? '/' : englishPath)], ['x-default', englishPath]]) {
        page.frontmatter.head.push(['link', { rel: 'alternate', hreflang: lang, href: `https://kavapp.link${href}` }])
      }
    }
    const title = page.frontmatter.title || page.title
    page.frontmatter.head ??= []
    page.frontmatter.head.push(
      ['meta', { property: 'og:title', content: title && title !== 'Kav' ? `${title} · Kav` : 'Kav' }],
      ['meta', { property: 'og:description', content: page.frontmatter.description || page.description || DESCRIPTION }],
    )
  },
  sitemap: { hostname: 'https://kavapp.link' },
  // Heading ids as GitHub makes them, so the docs' own links (written for GitHub) work here too: "something's" is
  // "somethings", where VitePress's own would make "something-s".
  markdown: { anchor: { slugify: githubSlug }, headers: { slugify: githubSlug } },
  themeConfig: {
    logo: { src: '/img/icon.svg', alt: '' },
    nav: [
      { text: 'Download', link: '/download' },
      { text: 'Docs', link: '/docs/', activeMatch: '^/docs/' },
      { text: 'Extensions', link: '/community' },
      { text: 'Privacy', link: '/privacy-policy' },
    ],
    socialLinks: [{ icon: 'github', link: GITHUB, ariaLabel: 'Kav on GitHub' }],
    sidebar: {
      '/docs/': [
        {
          text: 'Docs',
          items: [
            { text: 'Overview', link: '/docs/' },
            { text: 'For developers', link: '/docs/data' },
            { text: 'How Kav works', link: '/docs/how-it-works' },
            { text: 'Building from source', link: '/docs/building' },
            { text: 'License', link: '/docs/license' },
          ],
        },
        {
          text: 'Making extensions',
          items: [
            { text: 'Extensions', link: '/docs/extensions' },
            { text: 'Kotlin and Java', link: '/docs/extensions/native' },
            { text: 'The Kotlin starter', link: '/docs/extensions/starter' },
            { text: 'Hooks', link: '/docs/extensions/hooks' },
            { text: 'Screens', link: '/docs/extensions/screens' },
            { text: 'The bundle format', link: '/docs/extensions/format' },
            { text: 'What riders see', link: '/docs/extensions/what-riders-see' },
            { text: 'The words a rider meets', link: '/docs/extensions/riders-wording' },
            { text: 'Testing', link: '/docs/extensions/testing' },
            { text: 'The source template', link: '/docs/extensions/template' },
            { text: 'For your agent', link: '/docs/extensions/agent' },
          ],
        },
        {
          text: 'Your own server',
          items: [
            { text: 'Self-hosting', link: '/docs/self-hosting' },
            { text: 'Server API', link: '/docs/api' },
            { text: 'Live sources', link: '/docs/live-sources' },
            { text: 'A planner of your own', link: '/docs/planner' },
            { text: 'A predictor of your own', link: '/docs/predictor' },
            { text: 'Training a model', link: '/docs/training' },
          ],
        },
      ],
    },
    outline: { level: [2, 3], label: 'On this page' },
    search: { provider: 'local', options: { locales: { he: searchHebrew } } },
    editLink: {
      // The imported docs are edited where they live in the repo, not in site/docs. (This runs in the browser on its
      // own, so it can't use GITHUB.)
      pattern: ({ filePath, frontmatter }) =>
        `https://github.com/ImNoammm/kav/edit/main/${frontmatter.source || 'site/' + filePath}`,
      text: 'Edit this page on GitHub',
    },
    docFooter: { prev: 'Previous', next: 'Next' },
    externalLinkIcon: false,
    footer: {
      message:
        `<a href="${GITHUB}">Source</a> · <a href="${GITHUB}/issues">Report an issue</a> · ` +
        `<a href="/privacy-policy">Privacy</a> · <a href="/docs/license">GPL-3.0</a> · ` +
        `<a href="https://www.buymeacoffee.com/Noamm">Buy me a coffee</a>`,
    },
  },
})
