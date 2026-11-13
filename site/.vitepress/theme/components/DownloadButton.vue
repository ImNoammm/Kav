<script setup lang="ts">
import { computed } from 'vue'
import { data as built } from '../releases.data'
import { LATEST_PAGE, megabytes, useLiveReleases } from '../releases'

defineProps<{ caption?: boolean }>()

const live = useLiveReleases()
// The newest APK GitHub has now, else the newest at build time, else the releases page.
const latest = computed(() => (live.value?.length ? live.value : built)[0] ?? null)
const href = computed(() => latest.value?.apk?.url ?? LATEST_PAGE)
</script>

<template>
  <a class="kav-button kav-button--primary" :href="href">
    <svg class="kav-button__glyph" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 19.5h14" />
    </svg>
    Download for Android
  </a>
  <p v-if="caption" class="kav-caption">
    <template v-if="latest?.apk">Version {{ latest.version }} · {{ megabytes(latest.apk.size) }} · </template>Android 8.0 or newer
  </p>
</template>
