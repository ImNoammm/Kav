// Things marked .kav-reveal rise into place as they scroll into view. Until this has run they show as they are, so the
// page never depends on it: the hidden state only applies under html.kav-motion, which this sets.
let watcher: IntersectionObserver | null = null

export function reveal() {
  if (typeof window === 'undefined' || matchMedia('(prefers-reduced-motion: reduce)').matches) return
  watcher ??= new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        entry.target.classList.add('is-in')
        watcher!.unobserve(entry.target)
      }
    },
    { rootMargin: '0px 0px -8% 0px' },
  )
  const items = document.querySelectorAll<HTMLElement>('.kav-reveal:not(.is-in)')
  const fold = window.innerHeight
  // What's already on screen goes in now, in order, without waiting for a scroll.
  items.forEach((item) => (item.getBoundingClientRect().top < fold ? item.classList.add('is-in') : watcher!.observe(item)))
  document.documentElement.classList.add('kav-motion')
}
