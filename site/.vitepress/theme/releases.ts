// Kav's app releases from GitHub: those tagged v... with an APK. The timetable and map releases live there too.
import { ref } from 'vue'

export const REPO = 'https://github.com/ImNoammm/kav'
export const LATEST_PAGE = `${REPO}/releases/latest`
export const RELEASES_API = 'https://api.github.com/repos/ImNoammm/kav/releases?per_page=100'

export interface Release {
  version: string
  tag: string
  date: string
  url: string
  notes: string
  apk: { name: string; url: string; size: number } | null
}

interface GitHubRelease {
  tag_name: string
  name: string | null
  draft: boolean
  prerelease: boolean
  published_at: string
  html_url: string
  body_html?: string
  assets: { name: string; browser_download_url: string; size: number }[]
}

export function appReleases(list: GitHubRelease[]): Release[] {
  return list
    .filter((r) => !r.draft && /^v\d/.test(r.tag_name) && r.assets.some((a) => a.name.endsWith('.apk')))
    .map((r) => {
      const apk = r.assets.find((a) => a.name.endsWith('.apk'))!
      return {
        version: r.tag_name.slice(1),
        tag: r.tag_name,
        date: r.published_at,
        url: r.html_url,
        // GitHub's own rendering of the notes, which GitHub sanitizes.
        notes: r.body_html || '',
        apk: { name: apk.name, url: apk.browser_download_url, size: apk.size },
      }
    })
    .sort((a, b) => b.date.localeCompare(a.date))
}

const live = ref<Release[] | null>(null)
let asked: Promise<void> | null = null

// The releases as GitHub has them now, asked once per visit; null until then, or if GitHub can't be reached.
export function useLiveReleases() {
  if (typeof window !== 'undefined' && !asked) {
    asked = fetch(RELEASES_API, { headers: { Accept: 'application/vnd.github.html+json' } })
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((list) => {
        live.value = appReleases(list)
      })
      .catch(() => {})
  }
  return live
}

export function megabytes(bytes: number) {
  return `${(bytes / 1e6).toFixed(1)} MB`
}

export function day(iso: string) {
  // Israel's day, the same in the built page and in any browser.
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Asia/Jerusalem' })
}
