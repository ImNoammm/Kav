<script setup lang="ts">
import { computed } from 'vue'
import { data as built } from '../releases.data'
import { megabytes, useLiveReleases } from '../releases'

defineProps<{ caption?: boolean }>()

const live = useLiveReleases()
// Use the build snapshot only until GitHub answers. An empty live list stays empty.
const latest = computed(() => (live.value ?? built)[0] ?? null)
</script>

<template>
  <a v-if="latest?.apk" class="kav-button kav-button--primary" :href="latest.apk.url">
    <svg class="kav-button__glyph" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 19.5h14" />
    </svg>
    Download for Android
  </a>
  <p v-else class="kav-caption">
    A new version is on its way. Check back soon.
    <br>
    <span lang="he" dir="rtl">גרסה חדשה בדרך. חזרו בקרוב.</span>
  </p>
  <p v-if="caption && latest?.apk" class="kav-caption">
    <template v-if="latest?.apk">Version {{ latest.version }} · {{ megabytes(latest.apk.size) }} · </template>Android 8.0 or newer
  </p>
</template>
