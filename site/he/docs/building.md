---
title: לבנות מהקוד
description: לבנות את ה-APK ואת לוח הזמנים, ואיך יוצאות הגרסאות הרשמיות.
---

# לבנות מהקוד

האפליקציה כתובה ב-Kotlin עם Jetpack Compose ונבנית עם Gradle. כל הפקודות כאן עובדות בלי root ובלי להתקין חבילות מערכת.

## מה צריך {#what-you-need}

- Linux על x86-64 בשביל הסקריפט שלמטה. במערכת אחרת צריך להתקין JDK 21 ואת Android SDK (פלטפורמה 36,
  כלי בנייה 35.0.0) ולכוון אליהם את `JAVA_HOME` ואת `ANDROID_HOME`.
- Git, וגם Python 3 בשביל לוח הזמנים.

## להוריד את הקוד {#get-the-code}

```sh
git clone https://github.com/ImNoammm/kav.git
cd kav
```

## להכין את כלי הבנייה {#the-toolchain}

`tools/android_toolchain.sh` מוריד את JDK 21 ואת Android SDK לתוך `~/Android`, ולא כותב בשום מקום אחר.
`rm -rf ~/Android` מוחק את כל מה שהוא הוריד.

```sh
tools/android_toolchain.sh
```

## לבנות את ה-APK {#build-the-apk}

`tools/android_build.sh` מריץ את Gradle עם הכלים האלה. כל מה שכותבים אחריו עובר ל-Gradle.

```sh
tools/android_build.sh :app:assembleRelease
```

הקובץ נוצר ב-`android/app/build/outputs/apk/release/app-release.apk`. כדי להתקין אותו בטלפון ש-USB debugging מופעל בו:

```sh
~/Android/Sdk/platform-tools/adb install -r android/app/build/outputs/apk/release/app-release.apk
```

::: warning לבנייה שלכם יש חתימה משלכם
גם גרסת release שבניתם נחתמת במפתח ה-debug של המחשב שלכם (`~/.android/debug.keystore`), ולא במפתח של Kav.
אנדרואיד לא תתקין אותה מעל האפליקציה הרשמית, או את הרשמית מעליה, בלי להסיר קודם את מה שמותקן. גם קישורים של
Kav לא יפתחו אותה ישר מהדפדפן, כי הם מאומתים מול המפתח של Kav.
:::

::: info השרת של Kav עונה רק לבניות רשמיות
האפליקציה חותמת על הבקשות לשרת של Kav במפתח שלא נמצא במאגר. גרסה שבניתם בעצמכם תעבוד מול
[שרת משלכם](/he/docs/self-hosting).
:::

## לבנות את לוח הזמנים {#the-timetable}

אין לוח זמנים בתוך ה-APK. בכל לילה GitHub Actions בונה קובץ מה-GTFS של משרד התחבורה ומקווי סוף השבוע של תל אביב,
חותם על המניפסט שלו ומפרסם אותו בגרסת `timetable`. זה הקובץ שהאפליקציה מורידה, בערך 6 MB. כדי לבנות אותו בעצמכם:

```sh
tools/fetch.sh
KAV_FROM=today KAV_REGION=il KAV_BBOX=national KAV_MANIFEST=1 python3 tools/export_web_bundle.py
```

`tools/fetch.sh` מוריד את הקובץ של המשרד לתוך `.cache/` (בערך 180 MB). התוצאה נוצרת ב-`.cache/timetable/`,
או בתיקייה שהגדרתם ב-`KAV_OUT`.

## איך יוצאת גרסה רשמית {#how-releases-are-made}

כשדוחפים תג גרסה (`v4.0`), מופעל [תהליך ה-release](https://github.com/ImNoammm/kav/blob/main/.github/workflows/release.yml)
ב-GitHub. הוא בונה APK מאותו commit בדיוק, חותם עליו במפתח של Kav, בודק שזו אותה תעודה ששימשה בכל הגרסאות עד היום,
שומר אישור בנייה ומצרף את ה-APK לגרסה של התג. כל אחד יכול לבדוק שהקובץ הגיע משם:

```sh
gh attestation verify kav-4.0.apk --repo ImNoammm/kav
```

## השרת {#the-server}

השרת של Kav בנוי מ-Python (`server/live.py`), מ-OpenTripPlanner ומחיזוי זמני הגעה, והכול רץ ב-Docker. איך בונים
את האימג'ים מהקוד ואיך מפרסמים אותם, כתוב במדריך ההרצה על השרת, בחלק [למפתחים](/he/docs/self-hosting#for-developers).

## להשתתף בפיתוח {#contributing}

באגים ורעיונות אפשר לפתוח ב-[issues](https://github.com/ImNoammm/kav/issues), ותיקונים לשלוח ב-pull request.
Kav ברישיון GPL-3.0, וגם כל קוד שנכנס אליה.
