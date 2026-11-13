// VitePress's own theme, in the system font (no Inter download), Kav's colours, and motion between pages.
import DefaultTheme from 'vitepress/theme-without-fonts'
import { inBrowser, type Theme } from 'vitepress'
import { nextTick } from 'vue'
import HomePage from './components/HomePage.vue'
import DownloadButton from './components/DownloadButton.vue'
import ReleaseList from './components/ReleaseList.vue'
import { reveal } from './reveal'
import './style.css'

export default {
  extends: DefaultTheme,
  enhanceApp({ app, router }) {
    app.component('HomePage', HomePage)
    app.component('DownloadButton', DownloadButton)
    app.component('ReleaseList', ReleaseList)
    if (!inBrowser) return

    // Going to another page is a View Transition where the browser has them: the old page fades away and the new one
    // rises in, while the bar and the sidebar stay put. VitePress waits for onAfterPageLoad before it swaps the page,
    // so the old page is captured first; onAfterRouteChange comes once the new one is in place (back and forward too).
    // The first load goes through here too, onto the page already on screen; only later ones are transitions.
    let first = true
    let done: (() => void) | null = null
    const calm = matchMedia('(prefers-reduced-motion: reduce)')
    router.onAfterPageLoad = () => {
      if (first || !document.startViewTransition || calm.matches) return
      done?.()
      return new Promise<void>((captured) => {
        document.startViewTransition(() => {
          captured()
          return new Promise<void>((resolve) => {
            done = resolve
            // Never hold the page if the route fails to settle.
            setTimeout(resolve, 1500)
          })
        })
      })
    }
    router.onAfterRouteChange = async () => {
      first = false
      await nextTick()
      done?.()
      done = null
      reveal()
    }
  },
} satisfies Theme
