<script setup lang="ts">
// The community list (PLAN.md 9.3), as scripts/community.mjs wrote it: in its one order, by name. The Add button is a
// plain link to the add-source page and Report goes to the source's own tracker; neither tells this site anything.
import { computed, ref } from 'vue'
import { useData, withBase } from 'vitepress'
import { data } from '../community.data'
import { ISSUES, SUBMIT_TITLE, describe, key, repoOf } from '../sources.js'

const { lang } = useData()
const he = computed(() => lang.value === 'he')
const t = (en: string, hebrew: string) => (he.value ? hebrew : en)
// ExtApp.kavMade: Kav's own source, judged by its address. It comes first; the rest stay in the list's own order.
const KAV = key('ImNoammm/kav-extensions')
const sources = computed(() => [...data.sources].sort((a, b) => Number(b.key === KAV) - Number(a.key === KAV)))
const count = (n: number) => (he.value ? (n === 1 ? 'תוסף אחד' : `${n} תוספים`) : n === 1 ? '1 extension' : `${n} extensions`)

// Submitting: an issue on Kav's repository, filled in here. scripts/community.mjs reads it on its next run.
const typed = ref('')
const repo = computed(() => repoOf(typed.value))
const submit = computed(() => {
  if (!repo.value) return ''
  const shown = describe(repo.value).shown
  const body = [
    'Please list this source on kavapp.link/community.',
    '',
    `Source: ${shown}`,
    '',
    "Nobody at Kav reads this. A script lists the source on its next run if it passes Kav's automatic checks, and its log on GitHub says why if it doesn't.",
    '',
    '- [ ] I accept the developer terms (https://kavapp.link/docs/extensions#developer-terms).',
  ].join('\n')
  return `${ISSUES}/new?title=${encodeURIComponent(SUBMIT_TITLE + shown)}&body=${encodeURIComponent(body)}`
})
</script>

<template>
  <div class="kav-community">
    <ul v-if="sources.length" class="kav-sources">
      <li v-for="s in sources" :key="s.key" class="kav-source">
        <img v-if="s.image" class="kav-source__tile" :src="withBase('/community/' + s.image)" alt="" width="56" height="56" loading="lazy">
        <div v-else class="kav-source__tile" aria-hidden="true">{{ Array.from(s.name)[0]?.toUpperCase() }}</div>
        <div class="kav-source__body">
          <p class="kav-source__name"><span dir="auto" class="kav-name">{{ s.name }}</span><span class="kav-source__count">{{ count(s.extensions) }}</span></p>
          <p class="kav-source__meta">
            <span class="kav-add__chip" :class="s.key === KAV ? 'kav-add__chip--kav' : 'kav-add__chip--third'">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path v-if="s.key === KAV" d="M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z" />
                <path v-else d="M12 2 4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5l-8-3zm1 13h-2v-2h2v2zm0-4h-2V7h2v4z" />
              </svg>
              {{ s.key === KAV ? t('Made by Kav', 'נוצר על ידי Kav') : t('Third-party', 'צד שלישי') }}
            </span>
          </p>
          <div class="kav-source__actions">
            <a class="kav-button kav-button--primary" :href="withBase((he ? '/he' : '') + '/add-source') + '?' + s.add">{{ t('Add to Kav', 'הוספה ל-Kav') }}</a>
            <a :href="s.page" rel="noopener nofollow ugc">{{ t('Source code', 'קוד המקור') }}</a>
          </div>
        </div>
      </li>
    </ul>
    <p v-else class="kav-community__empty">{{ t('No sources yet.', 'עוד אין מקורות.') }}</p>

    <h2 id="add-yours">{{ t('Add your source', 'הוספת המקור שלכם') }}</h2>
    <div class="kav-badge-maker">
      <input id="kav-submit" v-model="typed" type="text" dir="ltr" spellcheck="false" autocomplete="off" placeholder="github.com/owner/repo"
        :aria-label="t('Your source', 'המקור שלכם')">
      <p v-if="typed && !repo" class="kav-caption">{{ t('Not a GitHub repository.', 'זה לא מאגר GitHub.') }}</p>
      <p class="kav-add__actions">
        <a v-if="submit" class="kav-button kav-button--primary" :href="submit" rel="noopener">{{ t('Submit on GitHub', 'שליחה ב-GitHub') }}</a>
      </p>
    </div>
    <p class="kav-caption">{{ t('Or give the repository the kav-extension topic.', 'או הוסיפו למאגר את הנושא kav-extension.') }}</p>
  </div>
</template>
