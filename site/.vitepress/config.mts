import { defineConfig } from 'vitepress'

const GITHUB = 'https://github.com/ImNoammm/kav'
const DESCRIPTION = 'Public transport in Israel, without the ads, the account or the tracking.'

function githubSlug(text: string) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s/g, '-')
}

export default defineConfig({
  lang: 'en',
  title: 'Kav',
  description: DESCRIPTION,
  cleanUrls: true,
  // Light or dark as the device is, with no switch of its own.
  appearance: 'force-auto',
  lastUpdated: false,
  srcExclude: ['README.md'],
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
  transformPageData(page) {
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
      { text: 'Privacy', link: '/privacy-policy' },
    ],
    socialLinks: [{ icon: 'github', link: GITHUB, ariaLabel: 'Kav on GitHub' }],
    sidebar: {
      '/docs/': [
        {
          text: 'Docs',
          items: [
            { text: 'Overview', link: '/docs/' },
            { text: 'How Kav works', link: '/docs/how-it-works' },
            { text: 'Building from source', link: '/docs/building' },
            { text: 'License', link: '/docs/license' },
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
    search: { provider: 'local' },
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
