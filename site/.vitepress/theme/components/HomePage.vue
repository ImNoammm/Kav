<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'
import DownloadButton from './DownloadButton.vue'
import { REPO } from '../releases'

const { lang } = useData()
const he = computed(() => lang.value === 'he')
const features = [
  {
    title: 'Trips, step by step',
    text: 'A few ways there, then a guide that moves on by itself as you walk and ride, with the map turning with you.',
    heTitle: 'מהדלת עד היעד',
    heText: 'בוחרים מסלול, ו-Kav מלווה אתכם כל הדרך, ברגל ובאוטובוס.',
    glyph: 'M6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm12-10a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM8 17h6.5a3.5 3.5 0 0 0 0-7h-5a3.5 3.5 0 0 1 0-7H16',
  },
  {
    title: 'Where your bus really is',
    text: "Live positions from the Ministry of Transport's own feed, on the map and along your trip. Free.",
    heTitle: 'איפה האוטובוס באמת נמצא',
    heText: 'רואים בזמן אמת איפה האוטובוס, ישר מהנתונים של משרד התחבורה. על המפה ולאורך כל הנסיעה, בחינם.',
    glyph: 'M12 21s-7-5.5-7-11a7 7 0 0 1 14 0c0 5.5-7 11-7 11Zm0-8.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z',
  },
  {
    title: 'Nothing about you',
    text: 'No ads, no account, no analytics. Private search keeps your exact location out of what you look up.',
    heTitle: 'לא אוספים עליכם כלום',
    heText: 'אין פרסומות, לא צריך חשבון ואף אחד לא עוקב אחריכם. גם כשמחפשים כתובת, המיקום המדויק שלכם לא נשלח איתה.',
    glyph: 'M12 3 5 6v5c0 4.5 3 8.3 7 10 4-1.7 7-5.5 7-10V6l-7-3Zm-3 9 2 2 4-4',
  },
  {
    title: 'Works without signal',
    text: "The map and the week's timetable stay on your phone, so you can check stops, lines and scheduled departures without signal.",
    heTitle: 'עובדת גם בלי קליטה',
    heText: 'המפה ולוחות הזמנים לשבוע שמורים אצלכם בטלפון, אז אפשר לבדוק תחנות, קווים ושעות גם בלי אינטרנט.',
    glyph: 'M4 7.5 9 5l6 3 5-2.5v11L15 19l-6-3-5 2.5v-11ZM9 5v11m6-8v11',
  },
  {
    title: 'Every station, every line',
    text: 'Departure boards for every station, and every line with its route, its stops and its buses on the road.',
    heTitle: 'כל תחנה, כל קו',
    heText: 'לכל תחנה רואים מה יוצא ממנה, ולכל קו את המסלול, התחנות ואיפה האוטובוסים שלו עכשיו.',
    glyph: 'M5 5h14v14H5zM5 10h14M10 10v9',
  },
  {
    title: 'Your choice of source',
    text: 'Plan with Kav or your own server. Choose OpenStreetMap or Google for place search.',
    heTitle: 'הבחירה שלכם',
    heText: 'מתכננים דרך השרת של Kav או דרך שרת משלכם, ומחפשים מקומות ב-OpenStreetMap או ב-Google.',
    glyph: 'M4 7h10m4 0h2M4 17h4m4 0h8M14 5v4m-6 6v4',
  },
]
</script>

<template>
  <div class="kav-home">
    <section class="kav-hero">
      <img class="kav-hero__icon kav-enter" style="--i: 0" src="/img/icon.svg" alt="" width="128" height="128" />
      <h1 class="kav-hero__title kav-enter" style="--i: 1">Kav</h1>
      <p class="kav-hero__tagline kav-enter" style="--i: 2">{{ he ? 'תחבורה ציבורית בישראל, בלי\u00a0פרסומות, בלי הרשמה ובלי מעקב.' : 'Public transport in Israel, without the ads, the account or the tracking.' }}</p>
      <div class="kav-hero__actions kav-enter" style="--i: 3">
        <DownloadButton caption />
      </div>
      <a class="kav-link-chevron kav-enter" style="--i: 4" :href="REPO">{{ he ? 'הקוד ב-GitHub' : 'Explore on GitHub' }}</a>
    </section>

    <section class="kav-features" :aria-label="he ? 'מה אפשר לעשות עם Kav' : 'What Kav does'">
      <div v-for="(f, i) in features" :key="f.title" class="kav-feature kav-reveal" :style="{ '--i': i % 3 }">
        <svg class="kav-feature__glyph" viewBox="0 0 24 24" aria-hidden="true"><path :d="f.glyph" /></svg>
        <h2 class="kav-feature__title">{{ he ? f.heTitle : f.title }}</h2>
        <p class="kav-feature__text">{{ he ? f.heText : f.text }}</p>
      </div>
    </section>

    <section class="kav-band">
      <div class="kav-band__inner kav-reveal">
        <h2 class="kav-band__title">{{ he ? 'להריץ על השרת שלכם' : 'Run it on your own server.' }}</h2>
        <p class="kav-band__text">
          <template v-if="he">כל מה שהשרת של Kav עושה, על המחשב שלכם: אוטובוסים בזמן אמת, תכנון מסלולים, זמני הגעה ולוח הזמנים של השבוע. פקודה אחת להתקנה, ואת השאר מגדירים מהדפדפן.</template>
          <template v-else>Everything Kav's server does, on a machine of yours: live buses, the trip planner, arrival times and the week's
          timetable. One line to install, then a setup page in your browser.</template>
        </p>
        <pre class="kav-band__code"><code>curl -fsSL https://raw.githubusercontent.com/ImNoammm/kav/main/server/selfhost/install.sh | sh</code></pre>
        <a class="kav-link-chevron" :href="he ? '/he/docs/self-hosting' : '/docs/self-hosting'">{{ he ? 'למדריך ההתקנה' : 'Read the self-hosting guide' }}</a>
      </div>
    </section>
  </div>
</template>
