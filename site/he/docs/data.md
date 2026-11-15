---
title: למפתחים
description: תחנות, קווים, תוואים, מחירים, עומס וצי אוטובוסים בישראל, בקבצים חופשיים להורדה.
---

<script setup lang="ts">
import { onMounted, ref } from 'vue'

type Manifest = {
  built?: unknown
  files?: { name?: string; sources?: unknown }[]
  sources?: Record<string, { published?: unknown }>
}
const manifest = ref<Manifest>({})
const lastBuilt = ref('')
const timetablePublished = ref('')

function date(value: unknown, time = false) {
  if (typeof value !== 'string') return ''
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(Z|[+-]\d{2}:\d{2})?$/.exec(value)
  if (!match || (time && !match[7])) return ''
  const parsed = new Date(value.slice(0, 19) + 'Z')
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 19) !== value.slice(0, 19)) return ''
  const instant = time ? new Date(value) : parsed
  if (!Number.isFinite(instant.getTime())) return ''
  return new Intl.DateTimeFormat('he-IL', {
    day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC',
    ...(time ? { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'short' } as const : {}),
  }).format(instant)
}

function freshness(name: string) {
  const fallback = 'בכל לילה'
  const entry = manifest.value.files?.find((file) => file?.name === name)
  if (!Array.isArray(entry?.sources)) return fallback
  // Track data needs the extract's own date, not the timetable's.
  const source = entry.sources.includes('osm') ? 'osm' : entry.sources.includes('mot-busy') ? 'mot-busy' : entry.sources.includes('mot-fleet') ? 'mot-fleet' : entry.sources.includes('mot-gtfs') ? 'mot-gtfs' : ''
  return date(manifest.value.sources?.[source]?.published) || fallback
}

onMounted(async () => {
  try {
    const response = await fetch('/data/v1/manifest.json')
    if (!response.ok) return
    const data = await response.json()
    if (!data || !Array.isArray(data.files)) return
    manifest.value = data
    lastBuilt.value = date(data.built, true)
    timetablePublished.value = date(data.sources?.['mot-gtfs']?.published)
  } catch {}
})
</script>


# למפתחים

בכל לילה Kav קוראת את נתוני התחבורה הציבורית בישראל ומתקנת בהם מה שאפשר. אפשר לבנות על זה גם בפרויקט שלכם:

- **כל מה שאפשר לשים בקובץ זמין להורדה בחינם**: תחנות, קווים והתוואי שלהם, מפעילים, המחירים הרשמיים, כמה עמוסים הקווים בדרך כלל וסוגי האוטובוסים.
  אפשר להוריד את הקבצים ישר מהכתובות שלמטה, מהאפליקציה או מהשרת שלכם, בלי מפתח API ובלי חשבון.
- **נתונים בזמן אמת** (הגעות לתחנות, מיקומי אוטובוסים והתראות) לא פתוחים כ-API. פרטים ב[נתונים בזמן אמת](#live-data).

## הקבצים {#the-files}

הנתונים מגיעים מלוח הזמנים של משרד התחבורה, מקווי סוף השבוע של תל אביב ומ-OpenStreetMap, ונמצאים בכתובות:

- `https://kavapp.link/data/v1/<file>`, באתר של Kav
- `https://github.com/ImNoammm/kav/releases/download/data/manifest.json`, ב-GitHub. המניפסט אומר מאיזה לילה הוא,
  והקבצים שלו נמצאים לידו בשם `<night>.<file>`, למשל `20261113T235802Z.stops.csv.gz`

<p>
  כל הקבצים נבנים מחדש בכל לילה.
  <template v-if="timetablePublished">הקבצים שנבנים מלוח הזמנים של משרד התחבורה מגיעים מהגרסה שפורסמה ב-{{ timetablePublished }}.</template>
  <span v-if="lastBuilt">{{ ' ' }}הבנייה האחרונה: {{ lastBuilt }}</span>
</p>

| קובץ | מה יש בו | גודל | רישיון | תאריך המקור |
| --- | --- | --- | --- | --- |
| [`stops.csv.gz`](https://kavapp.link/data/v1/stops.csv.gz) | כל תחנה, כולל תחנות מרכזיות ותחנות רכבת: קוד, שמות בעברית, באנגלית ובערבית, עיר, רציף, מיקום והמסלולים שעוצרים בה | 2.1 MB | CC BY 4.0 | {{ freshness('stops.csv.gz') }} |
| [`routes.csv.gz`](https://kavapp.link/data/v1/routes.csv.gz) | כל מסלול (קו בכיוון אחד): מפעיל, מספר, מאיפה ולאן, כיוון, סוג וצבע | 0.4 MB | CC BY 4.0, `color` GPL-3.0 | {{ freshness('routes.csv.gz') }} |
| [`operators.csv.gz`](https://kavapp.link/data/v1/operators.csv.gz) | כל מפעיל, עם השם שלו באנגלית והצבע שלו | 1 KB | CC BY 4.0, `color` GPL-3.0 | {{ freshness('operators.csv.gz') }} |
| [`roads.geojson.gz`](https://kavapp.link/data/v1/roads.geojson.gz) | התוואי של כל מסלול אוטובוס, רכבת קלה ורכבל | 28 MB | CC BY 4.0 | {{ freshness('roads.geojson.gz') }} |
| [`rail-roads.geojson.gz`](https://kavapp.link/data/v1/rail-roads.geojson.gz) | תוואי הרכבות, לאורך המסילות של OpenStreetMap | 0.4 MB | ODbL 1.0 | {{ freshness('rail-roads.geojson.gz') }} |
| [`fares.csv.gz`](https://kavapp.link/data/v1/fares.csv.gz) | המחיר הרשמי בין כל שתי תחנות שהמשרד קבע להן מחיר | 2.9 MB | CC BY 4.0 | {{ freshness('fares.csv.gz') }} |
| [`fares.json.gz`](https://kavapp.link/data/v1/fares.json.gz) | למה משמש כל תעריף, תעריפים לכל הקו, חופשי יומי והכללים | 13 KB | CC BY 4.0 | {{ freshness('fares.json.gz') }} |
| [`busy.csv.gz`](https://kavapp.link/data/v1/busy.csv.gz) | רמת העומס הרגילה לפי Kav, לכל מסלול, כיוון ושעה בשבוע | משתנה | תנאי Data.gov.il | {{ freshness('busy.csv.gz') }} |
| [`fleet.csv.gz`](https://kavapp.link/data/v1/fleet.csv.gz) | גודל האוטובוס, מושבים, שנה והנעה, לפי מספר רישוי | משתנה | תנאי Data.gov.il | {{ freshness('fleet.csv.gz') }} |
| [`manifest.json`](https://kavapp.link/data/v1/manifest.json) | מתי נבנו הקבצים, מאיזה מקור, והגודל וה-sha256 של כל קובץ | 5 KB | | {{ freshness('manifest.json') }} |

ה-sha256 של קובץ במניפסט משתנה רק כשהתוכן שלו משתנה, אז מהמניפסט יודעים אם יש משהו חדש להוריד.

### להישאר מעודכנים {#staying-up-to-date}

1. מורידים את [`manifest.json`](https://kavapp.link/data/v1/manifest.json) לכל היותר פעם ביום.
2. משווים את ה-sha256 של כל קובץ למה שכבר יש אצלכם.
3. מורידים רק את הקבצים שהשתנו.
4. כדי שכל הקבצים יהיו מאותו לילה, משתמשים בכתובות של הלילה עצמו: `https://kavapp.link/data/v1/<folder>/<file>`.
   השדה `folder` במניפסט הוא השם של הלילה, ושני הלילות האחרונים נשארים זמינים.
5. אותו מניפסט נמצא גם ב-[GitHub](https://github.com/ImNoammm/kav/releases/download/data/manifest.json).

אם לאפליקציה שלכם יש הרבה משתמשים, עדיף להוריד את הקבצים לשרת שלכם ולהעביר אותם משם, במקום שכל טלפון יוריד אותם לבד.
בשימוש כבד, קחו את אותם קבצים מה[עותק ב-GitHub](https://github.com/ImNoammm/kav/releases/download/data/manifest.json).

רק ב-`rail-roads.geojson.gz` יש נתונים מ-OpenStreetMap. התחנות נמצאות איפה שלוח הזמנים של המשרד שם אותן (או של תל אביב, בתחנות של קווי סוף השבוע שלה).

כל קובצי הנתונים דחוסים ב-gzip. קובצי ה-CSV הם UTF-8 עם שורת כותרות. קראו עמודות לפי השם שלהן, לא לפי המיקום.
ה-GeoJSON לפי RFC 7946, אז קו האורך בא ראשון. הקודים והמזהים הם של המשרד, אותם שמופיעים בנתוני זמן האמת שלו וב[API של Kav](/he/docs/api).

הקבצים נמצאים תחת `/data/v1/`, והשדות `format` ו-`version` במניפסט (`kav-open-data`, `1`) אומרים באיזה מבנה הם.

### manifest.json {#manifestjson}

```json
{"format": "kav-open-data", "version": 1, "built": "2026-11-13T23:58:02+00:00", "folder": "20261113T235802Z",
 "files": [{"name": "stops.csv.gz", "size": 2057183, "sha256": "5b74b8b1...", "rows": 35422, "licence": "CC-BY-4.0",
            "credit": "Israel Ministry of Transport; Tel Aviv-Yafo Municipality; Kav (kavapp.link)",
            "sources": ["mot-gtfs", "tel-aviv-weekend", "kav"], "changed": "English and Arabic names from ..."}],
 "sources": {"mot-gtfs": {"name": "Israel Ministry of Transport, GTFS timetable", "sha256": "3477...",
                          "published": "2026-11-12T19:44:16", "from": "20261113", "to": "20261213"},
             "osm": {"name": "OpenStreetMap, railway tracks (Geofabrik's israel-and-palestine extract)", "sha256": "..."}},
 "licences": {"CC-BY-4.0": {"name": "Creative Commons Attribution 4.0", "url": "https://creativecommons.org/licenses/by/4.0/"}}}
```

- `files`: כל קובץ עם הגודל, ה-sha256, מספר השורות (ב-GeoJSON, מספר ה-features), הרישיון, הקרדיט שהרישיון דורש (למקורות של הקובץ), מאיפה הוא מגיע ומה Kav שינתה בו.
- `sources`: לוח הזמנים של המשרד כמו שהיה באותו לילה (`published` הוא הזמן שבתוך ה-zip שלו, ו-`from` ו-`to` הם הימים שהלוח מכסה),
  קובץ ה-OpenStreetMap שהרכבות הותאמו אליו (וה-sha256 שלו), ושאר המקורות.
- `folder`: הלילה. הקבצים שלו נמצאים גם ב-`https://kavapp.link/data/v1/<folder>/<file>`, הכתובת הקבועה של הלילה, עם מטמון לשנה.
  הכתובות הרגילות נותנות את הלילה האחרון: הקבצים במטמון לשעה, ו-`manifest.json` והחתימה לחמש דקות.
  שני הלילות האחרונים נשמרים, באתר של Kav וב-GitHub.

ליד המניפסט יש `manifest.json.sig`: חתימת ECDSA P-256 SHA-256 שלו, ב-base64. ככה בודקים הורדה:

```sh
curl -sO https://kavapp.link/data/v1/manifest.json -sO https://kavapp.link/data/v1/manifest.json.sig
curl -fsS https://kavapp.link/data/public-key.b64 | base64 -d | openssl pkey -pubin -inform DER -out kav-data.pem
base64 -d manifest.json.sig > manifest.sig
openssl dgst -sha256 -verify kav-data.pem -signature manifest.sig manifest.json
jq -r '.files[] | "\(.sha256)  \(.name)"' manifest.json | sha256sum --check
```

ב-GitHub החתימה היא `<folder>.manifest.json.sig`, ליד `<folder>.manifest.json`, שהוא אותו מניפסט בשם של הלילה. בזמן המעבר ללילה חדש `manifest.json` יכול להיעלם לרגע. אם זה קורה, מבקשים שוב.

[המפתח הציבורי של הנתונים](https://kavapp.link/data/public-key.b64) מותקן גם יחד עם משימת המשיכה. שמרו עותק שלו מהתקנה שאתם סומכים עליה, ובדקו מולו את ההורדות הבאות.

### מה בודקים בכל לילה {#how-a-night-is-checked}

לפני שלילה נחתם ומתפרסם, `tools/open_data.py check` בודק ש:

- במניפסט יש כל שדה, מהסוג, בטווח ובמבנה הנכונים, לפי אותה רשימת כללים שהאתר של Kav בודק לפני שהוא מקבל לילה, ומופיעים בו תשעת הקבצים;
- כל קובץ תואם לגודל ול-sha256 שלו, יש בו את העמודות שהגרסה הזאת כותבת, מספר השורות שנספר בו תואם למניפסט, ויש בו לפחות בערך שבע עשיריות מהשורות של 13 בנובמבר 2026 (של 15 בנובמבר בעומס ובצי האוטובוסים);
- שני קובצי ה-GeoJSON נקראים כ-FeatureCollections של LineStrings, וכל זוגות הקואורדינטות בהם הם מספרים סופיים;
- אף קובץ לא ירד מתחת ל-90% מהשורות או מהבתים של הלילה הקודם;
- כל מסלול, מפעיל, תוואי ומחיר שמופיעים בקובץ אחד נמצאים גם בשאר הקבצים, התוואים מכסים 95% מהקווים של המשרד ומהרכבות שלו, ול-75% מהתחנות יש קו שעוצר בהן;
- כל תחנה נמצאת בישראל או בגדה המערבית: עד 2 קילומטר מקו הגבול של [Natural Earth](https://www.naturalearthdata.com), שהדיוק שלו הוא בערך קילומטר וחצי;
- לעומת התחנות של הלילה הקודם, אף תחנה לא זזה יותר מקילומטר, ולא יותר מ-0.1% מהתחנות זזו יותר מ-50 מטר.
  בנתונים של המשרד עצמו, מלילה ללילה בין 16 ביולי ל-13 בנובמבר 2026, תחנה זזה לכל היותר 751 מטר,
  ואף פעם לא זזו בלילה אחד יותר מ-0.026% מהתחנות יותר מ-50 מטר.

בלי הקבצים של הלילה הקודם להשוואה לא מתפרסם כלום, חוץ מהלילה הראשון ממש. ירידה או תזוזה אמיתית עוברת רק כשמישהו מריץ את הפרסום ידנית.

הבדיקה תופסת תקלה בתהליך של Kav עצמה, או מקור נתונים שבור. היא לא בודקת שהנתונים של המשרד נכונים: הקבצים שומרים את מיקומי התחנות, המחירים ולוח הזמנים של המשרד כמו שהם. לכן תחנה שהמשרד שם במקום הלא נכון בתוך הגבולות האלה, מחיר שגוי או נסיעה שגויה יעברו את הבדיקה, וכך גם כל שינוי שקטן מהגבולות.

### stops.csv.gz {#stopscsvgz}

שורה לכל תחנה של המשרד, כולל תחנות מרכזיות ותחנות רכבת, וגם לכמה תחנות של קווי סוף השבוע של תל אביב שלא שייכות למשרד.

| עמודה | מה יש בה |
| --- | --- |
| `stop_id` | ה-`stop_id` של המשרד ב-GTFS. התחנות של קווי סוף השבוע מתחילות מ-9,000,000 |
| `code` | המספר שעל שלט התחנה, שמופיע בנתוני זמן האמת וב-API של Kav. לרציפים בתחנה מרכזית יש את הקוד של התחנה |
| `name_he`, `name_en`, `name_ar` | שם התחנה, והשמות שהמשרד נתן לה באנגלית ובערבית (ריק אם אין) |
| `street_he`, `city_he`, `city_en` | נלקחים מתוך `stop_desc` של המשרד |
| `platform` | מספר הרציף בתחנה מרכזית |
| `lat`, `lon` | איפה שלוח הזמנים של המשרד שם את התחנה (של תל אביב, בתחנות של קווי סוף השבוע שלה) |
| `location_type`, `parent_station` | כמו ב-GTFS: `1` היא תחנה |
| `route_ids` | כל המסלולים שעוצרים בתחנה, עם רווח ביניהם (`routes.csv.gz`) |

```csv
stop_id,code,name_he,name_en,name_ar,street_he,city_he,city_en,platform,lat,lon,location_type,parent_station,route_ids
1,38831,בי''ס בר לב/בן יהודה,Bar Lev School/Ben Yehuda,مدرسة بار ليڤ/بن يهودا,בן יהודה 74,כפר סבא,Kfar Saba,,32.183985,34.917554,0,,26993
```

### routes.csv.gz {#routescsvgz}

שורה לכל מסלול: קו אחד, בכיוון אחד ובחלופה אחת, כמו בלוח הזמנים של המשרד. גם קווי סוף השבוע של תל אביב נמצאים כאן (מ-9,000,000 ומעלה).

| עמודה | מה יש בה |
| --- | --- |
| `route_id` | ה-`route_id` של המשרד ב-GTFS, אותו מסלול בנתוני זמן האמת שלו וב-API של Kav |
| `agency_id`, `operator_he` | מי מפעיל אותו (`operators.csv.gz`) |
| `number` | מספר הקו שעל האוטובוס. לרכבות אין |
| `name_he` | השם שהמשרד נתן למסלול |
| `from_he`, `from_city_he`, `to_he`, `to_city_he` | המוצא והיעד, כמו ש-Kav קוראת אותם מהשם ומציגה אותם |
| `from_en`, `from_city_en`, `to_en`, `to_city_en` | אותו דבר באנגלית של המשרד, אם יש |
| `headsign_he` | היעד שמופיע הכי הרבה בנסיעות שלו |
| `mot_line_id`, `direction`, `variant` | מספר הקו בקטלוג של המשרד (אותו מספר לשני הכיוונים ולכל החלופות), הכיוון והחלופה, מתוך `route_desc`. למסלול רכבת יש רק את הראשון |
| `type` | סוג המסלול ב-GTFS, כמו ש-Kav קוראת אותו: `3` אוטובוס, `0` רכבת קלה, `2` רכבת, `5` רכבל, `7` הכרמלית, `711` שאטל, `715` אוטובוס בהזמנה, `8` מונית שירות |
| `kind` | אותו סוג במילה: `bus`, `light_rail`, `train`, `cable_car`, `funicular`, `shuttle`, `on_demand_bus`, `share_taxi` |
| `mot_color` | ה-`route_color` של המשרד (hex בלי `#`), אם יש |
| `color` | הצבע ש-Kav מציירת בו את הקו (hex בלי `#`): ברכבות, ברכבת הקלה ובשאר, הצבע של סוג התחבורה, ואחרת הצבע של המפעיל |

```csv
route_id,agency_id,operator_he,number,name_he,from_he,from_city_he,to_he,to_city_he,from_en,from_city_en,to_en,to_city_en,headsign_he,mot_line_id,direction,variant,type,kind,mot_color,color
6768,3,אגד,550,מלון יפה נוף-ערד<->קניון איילון-בני ברק-2#,מלון יפה נוף,ערד,קניון איילון,בני ברק,Yefe Nof Hotel,Arad,Ayalon Mall,Bnei Brak,בני ברק_קניון איילון,10550,2,#,3,bus,,018369
```

### operators.csv.gz {#operatorscsvgz}

`agency_id`, `name_he`, `name_en`, `url`, `phone`, `color`: המפעילים של המשרד, עם השמות באנגלית והצבעים ש-Kav משתמשת בהם. צבעי המפעילים הם של Sammy Zaionz.

### `roads.geojson.gz` ו-`rail-roads.geojson.gz` {#roadsgeojsongz-and-rail-roadsgeojsongz}

FeatureCollection עם LineString אחד לכל תוואי, בדיוק של חמש ספרות אחרי הנקודה (בערך מטר). בכל feature מופיעים כל המסלולים שרוב הנסיעות שלהם עוברות בתוואי הזה.

```json
{"type": "Feature", "properties": {"route_ids": ["317"], "number": "24", "agency_id": 14, "type": 3},
 "geometry": {"type": "LineString", "coordinates": [[35.229, 33.09026], [35.22905, 33.09015], ...]}}
```

בלוח הזמנים של המשרד אין תוואים לרכבות. הם נמצאים ב-`rail-roads.geojson.gz`, שנבנה מהתאמה של הרכבות למסילות של OpenStreetMap עם [pfaedle](https://github.com/ad-freiburg/pfaedle). לכן אלה נתונים של OpenStreetMap, ברישיון של OpenStreetMap.

### `fares.csv.gz` ו-`fares.json.gz` {#farescsvgz-and-faresjsongz}

קובצי המחירים הם המחירים שהמשרד פרסם, כנתונים. הקבצים וה-API של Kav לא מוכרים כרטיסים ולא גובים תשלום.

`fares.csv.gz` הוא טבלת המחירים של המשרד כמו ש-Kav קוראת אותה: `from_code`, `to_code` (קודי תחנות), `fare_id` ו-`price_agorot` (100 אגורות לשקל).

```csv
from_code,to_code,fare_id,price_agorot
140,60270,202,1450
```

`fares.json.gz` מסביר מה עושים עם הטבלה:

- `products`: כל מזהה תעריף עם המחיר שלו, `kind` (`bus` כולל אוטובוסים, רכבת קלה ורכבלים), המרחק שהוא מתאים לו (`up_to_km`),
  `includes_changes_minutes` (90 בתעריף של עד 15 קילומטר, שכולל נסיעות המשך), והאם הוא של אילת, בלי מע"מ.
- `route_fares`: קווים עם מחיר אחד לכל הקו, לפי מזהה מסלול.
- `day_passes`: החופשי היומי לפי מרחק, לאוטובוסים בלבד (`bus`) או גם לרכבות (`rail`).
- `app_day_starts`: השעה שבה האפליקציה מתחילה יום חדש לחישוב המחיר. השדה נמצא ברמה העליונה של הקובץ.
- `unsure_stops`: תחנות שזזו מאז שנקבעו המחירים שלהן, ולכן Kav לא סומכת על המרחקים שלהן.
- `rules`: הכללים במילים, מצו הפיקוח על המחירים. אלה המחירים המלאים הרגילים: הנחות לפי פרופיל נוסע (סטודנטים, אזרחים ותיקים ואחרים) לא נמצאות בקבצים האלה.

### busy.csv.gz {#busycsvgz}

`route_id`, `direction`, `day`, `hour`, `level`: שורה לכל מסלול ושעה ש-Kav יכולה לתאר, בשעון ישראל.
`route_id` ו-`direction` תואמים ל-`routes.csv.gz`. `day` הוא מיום ראשון `0` עד שבת `6`, ו-`hour` מ-`0` עד `23`.
כל ימי החול חולקים את פרופיל ימי החול של השרת. `level` הוא בדיוק הערך של השרת: `3` אחת השעות העמוסות של הקו,
`2` בערך ממוצע, `1` אחת השעות השקטות שלו, `q` יום שקט, `0` אין מספיק נתונים. לסוג יום שאין עליו נתונים אין שורות.
ההשוואה היא בין השעות של אותו קו, לפי עליות לכל נסיעה מתוכננת. היא לא אומרת כמה האוטובוס מלא.

המקור הוא [ספירות התיקופים החודשיות](https://data.gov.il/dataset/tikufim-2021) של המשרד, יחד עם לוחות הזמנים היומיים השמורים שהשרת של Kav משתמש בהם.
כרגע אין רמות לשישי ולשבת: באוקטובר יש מעט מדי ימים בשעון חורף. הספירות של נובמבר, שצפויות בתחילת דצמבר,
עשויות להשלים אותן כשיהיו לשרת מספיק נתונים. המניפסט שומר את התאריכים של חודש הספירה, וטביעת אצבע וטווח תאריכים של לוחות הזמנים השמורים.

### fleet.csv.gz {#fleetcsvgz}

`plate`, `size`, `seats`, `year`, `power`: שורה לכל מספר רישוי ב[מאגר צי האוטובוסים](https://data.gov.il/dataset/bus_fleet) של המשרד, אחרי ניקוי בשרת של Kav.
`size` הוא `bus`, `midibus`, `minibus` או `articulated`, ו-`power` הוא `diesel`, `electric` או `gas`.
גודל והנעה לא ידועים נשארים ריקים, ומושבים ושנה לא ידועים או לא תקינים הם `0`. אוטובוסים שלא ידוע עליהם כלום לא נכללים.
מספרי הרישוי נשמרים כמחרוזות של ספרות. אין שדה ליצרן, לדגם או לנגישות.

### איחורים בקווים {#line-punctuality}

האפליקציה של Kav מראה כמה קו מאחר בדרך כלל. אין קובץ של איחורים.

### מה לא נמצא כאן {#what-isnt-here}

- **תיקוני המיקום שהמתכנן של Kav עושה לתחנות.** התיקון שלו מזיז תחנה למקום שבו היא מופיעה ב-OpenStreetMap, אז הקבצים האלה נשארים עם המיקומים של המשרד.
- **לוח הזמנים של רכבת ישראל עצמה**, שהמתכנן של Kav משתמש בו כדי לתקן את הרכבות של הימים הקרובים.
  Kav קוראת אותו מהשירות שמאחורי האתר של הרכבת, והוא לא מתפרסם כנתונים פתוחים.

## רישיונות {#licences}

- **תחנות, מסלולים, מפעילים, תוואים ומחירים**: Kav מפרסמת אותם ברישיון [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
  חוץ מעמודה אחת: העמודה `color` ב-`routes.csv.gz` וב-`operators.csv.gz` היא צבעי המפעילים והקווים מקוד המקור של Kav,
  שתרם Sammy Zaionz, והיא ברישיון של הקוד, GPL-3.0. כל השאר בשני הקבצים האלה ברישיון CC BY 4.0.
  זה הרישיון של Kav על העבודה שלה על הנתונים, והוא לא מדבר בשם המשרד. תנו קרדיט לכל קובץ כמו שכתוב ב-`credit` שלו במניפסט:
  המשרד, עיריית תל אביב-יפו בקבצים עם קווי סוף השבוע שלה (תחנות, מסלולים, מפעילים ותוואים), Sammy Zaionz על צבעי המפעילים
  (מסלולים ומפעילים), ו-Kav. המשרד מפרסם את לוח הזמנים שלו לפי [תנאי שימוש](https://www.gov.il/he/pages/gtfs_general_transit_feed_specifications)
  משלו, שחלים גם על מה שמגיע ממנו: המשרד לא לוקח אחריות על הנתונים, ויכול לשנות את התנאים.
  את הנתונים הפתוחים של תל אביב מותר לשתף ולעבד לכל מטרה, עם קרדיט ([התנאים שלה](https://opendatasource.tel-aviv.gov.il/en/Pages/FAQ.aspx)).
- **`rail-roads.geojson.gz`** מכיל נתונים של OpenStreetMap, המסילות שהרכבות הותאמו אליהן, ולכן הוא ברישיון
  [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/), © תורמי OpenStreetMap
  ([זכויות יוצרים](https://www.openstreetmap.org/copyright)), עם קרדיט גם למשרד ול-Kav. מה שנובע מזה תלוי במה שתעשו איתו:
  - בסיס נתונים שנעשה ממנו (ששיניתם, הרחבתם, או שהעברתם חלק משמעותי ממנו לבסיס אחר), שאתם משתפים או משתמשים בו בפומבי,
    חייב להיות גם הוא ברישיון ODbL (סעיף 4.4), ואתם צריכים להציע אותו, או את השינויים שעשיתם, בפורמט שמחשב יכול לקרוא (סעיף 4.6).
    שימוש בבסיס נתונים כזה בשביל משהו ציבורי נחשב שימוש בפומבי (4.4 c).
  - משהו שמופק ממנו, כמו תמונת מפה או הקווים שאפליקציה מציירת, צריך הודעה שהוא מכיל מידע מ-OpenStreetMap שזמין ברישיון ODbL (סעיף 4.3).
    הוא עצמו לא כפוף ל-ODbL (סעיף 4.5 b). [ההנחיה של OSMF לתוצרים](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Produced_Work_-_Guideline)
    מסמנת את הגבול: תוצר שנועד לכך שיוציאו ממנו את הנתונים בחזרה הוא בסיס נתונים.
- **`busy.csv.gz` ו-`fleet.csv.gz`**: הנתונים של המשרד כפופים ל[רישיון השימוש במאגרי Data.gov.il](https://data.gov.il/terms-of-use),
  שמופיע במניפסט כ-`data-gov-il`. הוא מתיר העתקה, הפצה מחדש, שינויים טכניים ויצירות נגזרות, לשימוש מסחרי ולא מסחרי,
  עם קרדיט למקור. הקרדיט: משרד התחבורה בישראל, data.gov.il; Kav (kavapp.link).
  קובץ העומס משתמש גם בלוח הזמנים של המשרד, לפי [תנאי ה-GTFS שלו](https://www.gov.il/he/pages/gtfs_general_transit_feed_specifications).
  אלה קבצים ש-Kav עיבדה, והם מסופקים כמו שהם, בלי חסות של המדינה. data.gov.il אוסר שימוש מטעה ופגיעה בפרטיות,
  וכל התנאים והחריגים שלו חלים. Kav לא מסמנת אף אחד משני הקבצים כ-CC BY.
- **הקוד של Kav**, כולל הסקריפט שמייצר את הקבצים (`tools/open_data.py`): [GPL-3.0](/he/docs/license).

הקבצים מסופקים כמו שהם. כל דבר שחשוב לכם, בדקו מול הנתונים של המשרד עצמו.

## נתונים בזמן אמת {#live-data}

ה-API לנתונים בזמן אמת של השרת של Kav, עם הגעות לתחנות, מיקומי אוטובוסים והתראות על קווים, לא פתוח. אם הייתם משתמשים בו, כתבו ל-[noam@kavapp.link](mailto:noam@kavapp.link) מה אתם בונים ובערך כמה בקשות תצטרכו.

## שרת משלכם, לשימוש כבד {#your-own-server-for-heavy-use}

כל מה שהשרת של Kav עושה, כולל תכנון מסלולים, רץ גם על מחשב שלכם, בלי שום מגבלה חוץ מהמגבלות של המחשב: [הרצה על השרת שלי](/he/docs/self-hosting).
המתכנן לכל ישראל צריך בערך 6 GB זיכרון כשהוא רץ ו-22 GB כשהוא נבנה מחדש בלילה ([מה צריך](/he/docs/self-hosting#what-you-need)),
ולאוטובוסים בזמן אמת צריך [מפתח משלכם מהמשרד](/he/docs/self-hosting#a-key-from-the-ministry-of-transport).
השרת שלכם עונה לאותו [API](/he/docs/api), אז קוד שכתבתם מול Kav עובד גם מולו.
