import type { DefaultTheme, LocaleSpecificConfig } from 'vitepress'

const guide = (text: string, page: string) => ({ text, link: `/he/docs/${page}` })
// The extension kit's pages are in English only, so these open the English page.
const kit = (text: string, page: string) => ({ text, link: `/docs/extensions/${page}` })

export const he: LocaleSpecificConfig<DefaultTheme.Config> & { label: string } = {
  label: 'עברית',
  lang: 'he',
  dir: 'rtl',
  description: 'תחבורה ציבורית בישראל, בלי פרסומות, בלי הרשמה ובלי מעקב.',
  themeConfig: {
    nav: [
      { text: 'להורדה', link: '/he/download' },
      { text: 'מדריכים', link: '/he/docs/', activeMatch: '^/he/docs/' },
      { text: 'תוספים', link: '/he/community' },
      { text: 'פרטיות', link: '/he/privacy-policy' },
    ],
    sidebar: {
      '/he/docs/': [
        { text: 'מדריכים', items: [
          guide('סקירה כללית', ''),
          guide('למפתחים', 'data'),
          { text: 'איך Kav עובדת', link: '/he/docs/how-it-works' },
          guide('לבנות מהקוד', 'building'), guide('רישיון', 'license'),
        ] },
        { text: 'יצירת תוספים', items: [
          guide('תוספים', 'extensions'),
          kit('Kotlin ו-Java', 'native'), kit('תבנית ההתחלה ל-Kotlin', 'starter'), kit('ה-hooks', 'hooks'),
          kit('מסכים', 'screens'), kit('הפורמט של קובץ החבילה', 'format'), kit('מה המשתמשים רואים', 'what-riders-see'),
          kit('המילים שהמשתמשים פוגשים', 'riders-wording'), kit('בדיקות', 'testing'), kit('התבנית למקור', 'template'),
          kit('בשביל הסוכן שלכם', 'agent'),
        ] },
        { text: 'שרת משלכם', items: [
          guide('הרצה על השרת שלי', 'self-hosting'), guide('ה-API של השרת', 'api'),
          guide('מקורות לזמן אמת', 'live-sources'), guide('מתכנן מסלולים משלכם', 'planner'),
          guide('זמני הגעה משלכם', 'predictor'), guide('לאמן מודל', 'training'),
        ] },
      ],
    },
    outline: { level: [2, 3], label: 'בדף הזה' },
    docFooter: { prev: 'הקודם', next: 'הבא' },
    lastUpdated: { text: 'עודכן לאחרונה' },
    editLink: {
      pattern: ({ filePath, frontmatter }) =>
        `https://github.com/ImNoammm/kav/edit/main/${frontmatter.source || 'site/' + filePath}`,
      text: 'לערוך את הדף ב-GitHub',
    },
    langMenuLabel: 'שפה', returnToTopLabel: 'חזרה למעלה', sidebarMenuLabel: 'תפריט',
    darkModeSwitchLabel: 'מראה', lightModeSwitchTitle: 'למצב בהיר', darkModeSwitchTitle: 'למצב כהה',
    skipToContentLabel: 'דילוג לתוכן',
    notFound: { code: '404', title: 'הדף לא נמצא', quote: 'אין כאן כלום. אולי הקישור השתנה.', linkLabel: 'לדף הבית', linkText: 'לדף הבית' },
    socialLinks: [{ icon: 'github', link: 'https://github.com/ImNoammm/kav', ariaLabel: 'Kav ב-GitHub' }],
    footer: { message:
      '<a href="https://github.com/ImNoammm/kav">קוד המקור</a> · <a href="https://github.com/ImNoammm/kav/issues">לדווח על בעיה</a> · ' +
      '<a href="/he/privacy-policy">פרטיות</a> · <a href="/he/docs/license">GPL-3.0</a> · ' +
      '<a href="https://www.buymeacoffee.com/Noamm">להזמין אותי לקפה</a>',
    },
  },
}

export const searchHebrew = {
  translations: {
    button: { buttonText: 'חיפוש', buttonAriaLabel: 'חיפוש' },
    modal: {
      displayDetails: 'להציג פרטים', resetButtonTitle: 'לנקות את החיפוש', backButtonTitle: 'לסגור את החיפוש',
      noResultsText: 'אין תוצאות לחיפוש',
      footer: { selectText: 'לבחור', selectKeyAriaLabel: 'Enter', navigateText: 'לנווט', navigateUpKeyAriaLabel: 'חץ למעלה',
        navigateDownKeyAriaLabel: 'חץ למטה', closeText: 'לסגור', closeKeyAriaLabel: 'Escape' },
    },
  },
}
