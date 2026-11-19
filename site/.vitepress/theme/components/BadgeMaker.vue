<script setup lang="ts">
// The "Add to Kav" badge for a source's README, with its link, made here in the browser (PLAN.md 9.6). Arriving from
// an add-source page (?github=owner/repo...), the source is filled in.
import { computed, onMounted, ref } from 'vue'
import { useData, withBase } from 'vitepress'
import { appLink, badge, describe, fromQuery, repoOf } from '../sources.js'

const { lang } = useData()
const he = computed(() => lang.value === 'he')
const t = (en: string, hebrew: string) => (he.value ? hebrew : en)

const typed = ref('')
const hebrewBadge = ref<boolean | null>(null)
const copied = ref('')

const repo = computed(() => repoOf(typed.value))
const useHebrew = computed(() => hebrewBadge.value ?? he.value)
const out = computed(() => (repo.value ? badge(repo.value, useHebrew.value) : null))
const app = computed(() => (repo.value ? appLink(repo.value) : ''))

async function copy(text: string, which: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = which
    setTimeout(() => { if (copied.value === which) copied.value = '' }, 1800)
  } catch {}
}

onMounted(() => {
  const read = fromQuery(location.search ? location.search.slice(1) : null)
  if (read) typed.value = describe(read).shown
})
</script>

<template>
  <div class="kav-badge-maker">
    <label for="kav-badge-source">{{ t('Your source', 'המקור שלכם') }}</label>
    <input id="kav-badge-source" v-model="typed" type="text" dir="ltr" spellcheck="false" autocomplete="off" placeholder="github.com/owner/repo">
    <p v-if="typed && !repo" class="kav-caption">{{ t('Not a GitHub repository. Give owner/repo or github.com/owner/repo.',
      'זה לא מאגר GitHub. כתבו owner/repo או github.com/owner/repo.') }}</p>

    <template v-if="out">
      <div class="kav-badge-maker__preview">
        <a :href="withBase((he ? '/he' : '') + '/add-source') + '?' + out.link.split('?')[1]">
          <img :src="withBase(useHebrew ? '/img/add-to-kav-he.svg' : '/img/add-to-kav.svg')" :alt="useHebrew ? 'הוספה ל-Kav' : 'Add to Kav'">
        </a>
      </div>
      <p class="kav-caption">
        <a href="#" role="button" @click.prevent="hebrewBadge = !useHebrew">{{ useHebrew ? t('Use the English badge', 'לתג באנגלית') : t('Use the Hebrew badge', 'לתג בעברית') }}</a>
      </p>
      <div class="kav-badge-maker__row"><span>Markdown</span><button type="button" class="kav-add__button" @click="copy(out.markdown, 'md')">{{ copied === 'md' ? t('Copied', 'הועתק') : t('Copy', 'העתקה') }}</button></div>
      <pre dir="ltr"><code>{{ out.markdown }}</code></pre>
      <div class="kav-badge-maker__row"><span>HTML</span><button type="button" class="kav-add__button" @click="copy(out.html, 'html')">{{ copied === 'html' ? t('Copied', 'הועתק') : t('Copy', 'העתקה') }}</button></div>
      <pre dir="ltr"><code>{{ out.html }}</code></pre>
      <div class="kav-badge-maker__row"><span>{{ t('The link', 'הקישור') }}</span><button type="button" class="kav-add__button" @click="copy(out.link, 'link')">{{ copied === 'link' ? t('Copied', 'הועתק') : t('Copy', 'העתקה') }}</button></div>
      <pre dir="ltr"><code>{{ out.link }}</code></pre>
      <div class="kav-badge-maker__row"><span>{{ t('The same for Kav directly', 'אותו קישור ישירות ל-Kav') }}</span><button type="button" class="kav-add__button" @click="copy(app, 'app')">{{ copied === 'app' ? t('Copied', 'הועתק') : t('Copy', 'העתקה') }}</button></div>
      <pre dir="ltr"><code>{{ app }}</code></pre>
    </template>
  </div>
</template>
