<script setup lang="ts">
import { computed, onUpdated } from 'vue'
import { reveal } from '../reveal'
import { data as built } from '../releases.data'
import { REPO, day, megabytes, useLiveReleases } from '../releases'

const live = useLiveReleases()
const releases = computed(() => (live.value?.length ? live.value : built))
// A release GitHub has that the build didn't rises in like the rest.
onUpdated(reveal)
</script>

<template>
  <div class="kav-releases">
    <article v-for="(release, i) in releases" :key="release.tag" class="kav-release kav-reveal" :style="{ '--i': Math.min(i, 3) }">
      <header class="kav-release__head">
        <h3 :id="release.tag">
          Kav {{ release.version }}
          <span v-if="i === 0" class="kav-badge">Latest</span>
        </h3>
        <time :datetime="release.date">{{ day(release.date) }}</time>
      </header>
      <div class="kav-release__notes" v-html="release.notes" />
      <p class="kav-release__links">
        <a v-if="release.apk" :href="release.apk.url">{{ release.apk.name }}</a>
        <span v-if="release.apk" class="kav-muted"> · {{ megabytes(release.apk.size) }} · </span>
        <a :href="release.url">Release on GitHub</a>
      </p>
    </article>
    <p v-if="!releases.length" class="kav-muted">
      The version history couldn't be loaded here. Every version is on
      <a :href="`${REPO}/releases`">GitHub</a>.
    </p>
  </div>
</template>
