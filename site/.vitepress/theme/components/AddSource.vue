<script setup lang="ts">
// kavapp.link/add-source: the page a shared source link lands on when Kav doesn't open it itself (PLAN.md 9.6), one card
// like an app store's install page. It reads the link the way Kav does (sources.js) and sends it nowhere; the only
// request it makes is the source's picture, its owner's GitHub avatar (SourceImages.githubAvatar), or Kav's icon for
// Kav's own source.
import { computed, onMounted, ref } from 'vue'
import { useData, withBase } from 'vitepress'
import { describe, fromQuery, intentLink, key, link } from '../sources.js'

const { lang } = useData()
const he = computed(() => lang.value === 'he')
const t = (en: string, hebrew: string) => (he.value ? hebrew : en)

// ExtApp.DEFAULT_SOURCES and kavMade: Kav's own source, judged by its address.
const KAV_SOURCE = 'ImNoammm/kav-extensions'

const state = ref<'reading' | 'bad' | 'ready'>('reading')
// The repository the link names, "owner/repo".
const read = ref<string | null>(null)
const phone = ref(false)
const qr = ref('')
const copied = ref(false)
const pictureFailed = ref(false)

const info = computed(() => (read.value ? describe(read.value) : null))
const kav = computed(() => !!read.value && key(read.value) === key(KAV_SOURCE))
const name = computed(() => (kav.value ? t('Kav extensions', 'התוספים של Kav') : info.value?.fallbackName ?? ''))
const picture = computed(() =>
  kav.value ? withBase('/img/icon-512.png') : info.value ? `https://github.com/${encodeURIComponent(info.value.owner)}.png?size=256` : '',
)
const open = computed(() =>
  read.value ? intentLink(read.value, location.origin + withBase(he.value ? '/he/download' : '/download')) : '',
)

function android() {
  const nav = navigator as Navigator & { userAgentData?: { platform?: string } }
  return /android/i.test(nav.userAgent) || nav.userAgentData?.platform === 'Android'
}

// The QR code is drawn here, by Kazuhiko Arase's qrcode-generator, which the build copies from Kav's self-hosted setup
// page (scripts/sync-docs.mjs) into this site. Without it the page still works, without the code.
function drawQr(text: string) {
  const w = window as Window & { qrcode?: any }
  const draw = () => {
    if (!w.qrcode) return
    w.qrcode.stringToBytes = w.qrcode.stringToBytesFuncs['UTF-8']
    const q = w.qrcode(0, 'M')
    q.addData(text)
    q.make()
    qr.value = q.createSvgTag({ cellSize: 4, margin: 0, scalable: true, alt: t('QR code of this link', 'קוד QR של הקישור הזה') })
  }
  if (w.qrcode) return draw()
  const s = document.createElement('script')
  s.src = withBase('/js/qrcode.js')
  s.onload = draw
  document.head.appendChild(s)
}

async function copy() {
  try {
    await navigator.clipboard.writeText(info.value!.shown)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1800)
  } catch {}
}

onMounted(() => {
  read.value = fromQuery(location.search ? location.search.slice(1) : null)
  state.value = read.value ? 'ready' : 'bad'
  phone.value = android()
  if (read.value && !phone.value) drawQr(link(read.value))
})
</script>

<template>
  <div class="kav-add">
    <noscript><p class="kav-caption">{{ t('This page needs JavaScript. Kav opens these links without it.', 'הדף הזה צריך JavaScript. Kav פותחת את הקישורים האלה גם בלעדיו.') }}</p></noscript>

    <!-- Laid out like Morphe's add-source page: the picture with GitHub's mark in its corner, the name, the owner, then
         one question and the buttons stacked full width; on a computer, the QR card instead of Add to Kav. -->
    <div v-if="state === 'ready' && read && info" class="kav-add__body">
      <div class="kav-add__avatar">
        <img v-if="!pictureFailed" class="kav-add__picture" :class="{ 'kav-add__picture--app': kav }" :src="picture" alt="" width="112" height="112" @error="pictureFailed = true">
        <div v-else class="kav-add__picture kav-add__picture--letter" aria-hidden="true">{{ Array.from(name)[0]?.toUpperCase() }}</div>
        <span class="kav-add__mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8-1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1 1.8 2.8 1.3 3.5 1 0-.8.4-1.3.7-1.6-2.7-.3-5.5-1.3-5.5-6 0-1.2.5-2.3 1.3-3.1-.2-.4-.6-1.6 0-3.2 0 0 1-.3 3.4 1.2a11.5 11.5 0 0 1 6 0C17.3 4.6 18.3 5 18.3 5c.7 1.6.2 2.8.1 3.1.8.9 1.3 1.9 1.3 3.1 0 4.7-2.8 5.7-5.5 6 .5.4.9 1.1.9 2.2v3.3c0 .3.2.7.8.6A12 12 0 0 0 12 .3" /></svg></span>
      </div>
      <h1 class="kav-add__name"><span dir="auto" class="kav-name">{{ name }}</span></h1>
      <p class="kav-add__owner" dir="ltr">github.com/{{ info.owner }}</p>
      <a class="kav-add__trust" :class="kav ? 'kav-add__trust--kav' : 'kav-add__trust--third'"
        :href="withBase(he ? '/he/third-party-extensions' : '/third-party-extensions')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path v-if="kav" d="M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z" />
          <path v-else d="M12 2 4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5l-8-3zm1 13h-2v-2h2v2zm0-4h-2V7h2v4z" />
        </svg>
        {{ kav ? t('Made by Kav', 'נוצר על ידי Kav') : t('Third-party', 'צד שלישי') }}
      </a>

      <template v-if="phone">
        <p class="kav-add__question">{{ t('Add this source to Kav?', 'להוסיף את המקור הזה ל-Kav?') }}</p>
        <a class="kav-add__btn kav-add__btn--primary" :href="open">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z" /></svg>
          {{ t('Add to Kav', 'הוספה ל-Kav') }}
        </a>
      </template>
      <div v-else class="kav-add__scan">
        <div v-if="qr" class="kav-add__qr" v-html="qr" />
        <p class="kav-add__hint">{{ t('Scan with your phone to add it to Kav', 'סרקו בטלפון כדי להוסיף ל-Kav') }}</p>
      </div>
      <a v-if="!phone" class="kav-add__btn kav-add__btn--primary" :href="withBase(he ? '/he/download' : '/download')">{{ t('Download Kav', 'הורדת Kav') }}</a>
      <button type="button" class="kav-add__btn kav-add__btn--secondary" @click="copy">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" /></svg>
        {{ copied ? t('Copied', 'הועתק') : t('Copy address', 'העתקת הכתובת') }}
      </button>
      <a v-if="phone" class="kav-add__link" :href="withBase(he ? '/he/download' : '/download')">{{ t('Download Kav', 'הורדת Kav') }}</a>
    </div>

    <div v-else-if="state === 'bad'" class="kav-add__body">
      <h1 class="kav-add__name">{{ t("This link doesn't name a source", 'הקישור הזה לא מוביל למקור') }}</h1>
      <p class="kav-add__owner" dir="ltr">kavapp.link/add-source?github=owner/repo</p>
      <a class="kav-add__btn kav-add__btn--secondary" :href="withBase(he ? '/he/download' : '/download')">{{ t('Download Kav', 'הורדת Kav') }}</a>
    </div>
  </div>
</template>
