<script setup lang="ts">
import { useData } from 'vitepress'
import { computed } from 'vue'
import { data as built } from '../releases.data'
import { megabytes, useLiveReleases } from '../releases'

defineProps<{ caption?: boolean }>()

const { lang } = useData()
const he = computed(() => lang.value === 'he')

const live = useLiveReleases()
// Use the build snapshot only until GitHub answers. An empty live list stays empty.
const latest = computed(() => (live.value ?? built)[0] ?? null)
</script>

<template>
  <a v-if="latest?.apk" class="kav-button kav-button--primary" :href="latest.apk.url">
    <svg class="kav-button__glyph" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 19.5h14" />
    </svg>
    {{ he ? 'להורדה לאנדרואיד' : 'Download for Android' }}
  </a>
  <p v-else class="kav-caption">
    <template v-if="he">גרסה חדשה בדרך. כדאי לבדוק שוב בקרוב.</template>
    <template v-else>A new version is on its way. Check back soon.
    <br>
    <span lang="he" dir="rtl">גרסה חדשה בדרך. חזרו בקרוב.</span></template>
  </p>
  <p v-if="caption && latest?.apk" class="kav-caption">
    <template v-if="latest?.apk">{{ he ? 'גרסה' : 'Version' }} <bdi>{{ latest.version }}</bdi> · <bdi>{{ megabytes(latest.apk.size) }}</bdi> · </template>{{ he ? 'אנדרואיד 8.0 ומעלה' : 'Android 8.0 or newer' }}
  </p>
</template>
