<script setup lang="ts">
import { computed, onMounted, ref, watchEffect } from 'vue'
import { useData, useRoute } from 'vitepress'
import DefaultTheme from 'vitepress/theme-without-fonts'

const { page, frontmatter, theme, lang } = useData()
watchEffect(() => {
  theme.value.i18nRouting = !!frontmatter.value.translated
})
// The same page in the other language, or the other language's home when this page has no twin.
const languageLink = computed(() => {
  const path = page.value.relativePath.replace(/^he\//, '').replace(/(?:index)?\.md$/, '')
  if (lang.value === 'he') return '/' + path
  return frontmatter.value.translated ? '/he/' + path : '/he/'
})
// A page that reads its link (keepQuery, the add-source page) keeps it in the other language too.
const route = useRoute()
const query = ref('')
onMounted(() => watchEffect(() => {
  query.value = frontmatter.value.keepQuery && route.path ? location.search : ''
}))
</script>

<template>
  <DefaultTheme.Layout>
    <template #nav-bar-content-after>
      <a class="kav-language-switch" :href="languageLink + query" :lang="lang === 'he' ? 'en' : 'he'" :dir="lang === 'he' ? 'ltr' : 'rtl'">
        {{ lang === 'he' ? 'English' : 'עברית' }}
      </a>
    </template>
  </DefaultTheme.Layout>
</template>
