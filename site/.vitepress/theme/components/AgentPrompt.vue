<script setup lang="ts">
// The developer page's "For your agent": one line to paste into an AI coding agent, pointing it at the file that holds
// everything it needs (public/docs/extensions/agent.txt, written by scripts/kit-docs.mjs).
import { computed, ref } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()
const t = (en: string, hebrew: string) => (lang.value === 'he' ? hebrew : en)
const URL = 'https://kavapp.link/docs/extensions/agent.txt'
const prompt = `Build a Kav extension. Read ${URL} first: it has everything you need.`
const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(prompt)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1800)
  } catch {}
}
</script>

<template>
  <div class="kav-agent">
    <code dir="ltr">{{ prompt }}</code>
    <button type="button" class="kav-add__button" @click="copy">{{ copied ? t('Copied', 'הועתק') : t('Copy', 'העתקה') }}</button>
  </div>
</template>
