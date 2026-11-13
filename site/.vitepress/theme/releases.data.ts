// Kav's releases as they were when the site was built, so the pages work before (or without) the browser asking GitHub.
import { defineLoader } from 'vitepress'
import { RELEASES_API, appReleases, type Release } from './releases'

declare const data: Release[]
export { data }

export default defineLoader({
  async load(): Promise<Release[]> {
    try {
      const response = await fetch(RELEASES_API, {
        headers: { Accept: 'application/vnd.github.html+json', 'User-Agent': 'kav-site-build' },
      })
      if (!response.ok) throw new Error(`GitHub answered ${response.status}`)
      return appReleases(await response.json())
    } catch (error) {
      // A build without GitHub still works: the pages fall back to the releases page and ask again in the browser.
      console.warn(`releases.data: ${error}`)
      return []
    }
  },
})
