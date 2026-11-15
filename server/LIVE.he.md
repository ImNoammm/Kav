# מקורות לזמן אמת

כל מה שמתעדכן בזמן אמת ב-Kav מגיע ממקום אחד, **המקור לזמן אמת**:
- כלי הרכב על המפה
- לוחות התחנות
- העדכונים בזמן אמת של המתכנן
- המעברים בתחנות שנרשמים, ושהחיזוי לומד מהם

כברירת מחדל זה שירות הזמן אמת של משרד התחבורה, עם מפתח משלכם. אפשר לחבר גם כל מקור אחר, כל עוד הוא עונה על אותן שאלות באותה צורה:
 **SIRI StopMonitoring** (CEN/TS 15531, התקן האירופי שגם המשרד עובד איתו), ב-JSON, גרסה 2.8. ככה מכוונים
אליו את השרת של Kav:

```
LIVE_URL=http://my-source:8100/siri
```

מפתח הוא לא חובה. אם `MOT_KEY` מוגדר, הוא נשלח כ-`Key=` בכל בקשה.

## שתי השאלות {#the-two-questions}

השרת של Kav שואל את המקור שלכם בדיוק שני דברים, בבקשות GET עם הפרמטרים האלה:

| שאלה | Query | מתי |
|---|---|---|
| **כל הנסיעות הפעילות** | `MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal` | כל 15 שניות כשמישהו מסתכל באפליקציה, וכל 30 שניות אחרת |
| **הגעות לתחנות** | `MonitoringRef=<code>,<code>,...&StopVisitDetailLevel=normal&PreviewInterval=PT90M` | כשפותחים לוח של תחנה, לכל היותר פעם ב-15 שניות לכל תחנה |

התשובה לשתיהן באותו מבנה:

```json
{"Siri": {"ServiceDelivery": {
  "ResponseTimestamp": "2026-11-07T09:00:05+02:00",
  "StopMonitoringDelivery": [{"MonitoredStopVisit": [ <visit>, ... ]}]
}}}
```

## דיווח (visit) {#a-visit}

```json
{"RecordedAtTime": "2026-11-07T09:00:01+02:00",
 "MonitoringRef": "21345",
 "MonitoredVehicleJourney": {
   "LineRef": "10966",
   "PublishedLineName": "8",
   "OriginAimedDepartureTime": "2026-11-07T08:40:00+02:00",
   "VehicleRef": "7712345",
   "FramedVehicleJourneyRef": {"DataFrameRef": "2026-11-07", "DatedVehicleJourneyRef": "30027349"},
   "VehicleLocation": {"Latitude": "32.0754", "Longitude": "34.7749"},
   "Bearing": "90", "Velocity": "23",
   "MonitoredCall": {"StopPointRef": "21345", "Order": "12",
                     "AimedArrivalTime": "2026-11-07T09:04:00+02:00", "ExpectedArrivalTime": "2026-11-07T09:05:30+02:00",
                     "ArrivalPlatformName": "2"}}}
```

| שדה | חובה? | בשביל מה Kav משתמשת בו |
|---|---|---|
| `LineRef` | כן | מזהה הקו בלוח הזמנים (`route_id` של GTFS). **מספר** (ראו מזהים בהמשך) |
| `OriginAimedDepartureTime` | כן | מתי הנסיעה יצאה מהתחנה הראשונה שלה. יחד עם `LineRef`, ככה יודעים איזו נסיעה זו |
| `RecordedAtTime` | כן | מתי כלי הרכב דיווח. מיקום ישן מבערך 2 דקות לא מוצג כזמן אמת |
| `VehicleRef` | בשביל המפה | איזה כלי רכב זה, כדי שיחליק מדיווח לדיווח במקום לקפוץ |
| `VehicleLocation` | בשביל המפה | איפה הוא. בלי זה הנסיעה עדיין מופיעה בלוחות, רק לא על המפה |
| `MonitoredCall.StopPointRef`, `MonitoringRef` | כן | התחנה: זו שכלי הרכב נמצא בה או עבר בה הרגע (כל הנסיעות), או זו ששאלו עליה (הגעות). זה ה**קוד** של התחנה (`stop_code` של GTFS) |
| `MonitoredCall.Order` | בשביל ההיסטוריה | המקום של התחנה בנסיעה, בשביל המעברים שנרשמים |
| `MonitoredCall.ExpectedArrivalTime` | בשביל הלוחות | מתי הוא צפוי להגיע לתחנה (ואם אין, `AimedArrivalTime`, הזמן לפי לוח הזמנים) |
| `PublishedLineName` | בשביל הלוחות | מספר הקו כמו שהנוסעים מכירים אותו |
| `FramedVehicleJourneyRef` | בשביל המתכנן | המזהה של הנסיעה עצמה ויום השירות שלה, בשביל העדכונים בזמן אמת של המתכנן |
| `Bearing`, `Velocity` | לא חובה | לאן הוא פונה (במעלות), ובאיזו מהירות הוא נוסע (קמ"ש) |
| `ArrivalPlatformName` / `DeparturePlatformName` | לא חובה | הרציף של רכבת |

הזמנים ב-ISO 8601, עם ההפרש מ-UTC. כל מה שמחוץ לאזור שב-`BOUNDS` (`south,west,north,east`) לא נלקח בחשבון.
כברירת מחדל זו כל ישראל, ושרת יכול לצמצם את זה לעיר אחת.

## מזהים {#ids}

Kav בנויה לישראל: מזהי הקווים (`LineRef`) וקודי התחנות הם של משרד התחבורה, כמו בלוח הזמנים שלו, והם מספרים. מקור
לזמן אמת חייב לקרוא לנסיעות ולתחנות באותם מזהים.

## מקורות מוכנים {#ready-made-sources}

- **המשרד** (`MOT_URL`, `MOT_KEY`): ברירת המחדל.
- **GTFS-Realtime**, הפורמט הפתוח השני לזמן אמת: `server/examples/gtfsrt_siri.py` ממיר את מיקומי כלי הרכב ואת
  עדכוני הנסיעות של פיד לתשובות האלה. הוא צריך את המזהים של המשרד, אז הוא מתאים לפיד שבנוי על לוח הזמנים של המשרד.
- **הקלטה**: `server/examples/live_replay.py` מגיש תשובה מוקלטת, אחרי שהזמנים בה הוזזו לעכשיו. ככה אפשר לנסות בלי
  מפתח בכלל, או להתחיל ממנו מקור משלכם.

Kav מגיעה עם מתאמים רק למקורות פתוחים ומותרים. מה שאתם מחברים לשרת שלכם זו החלטה שלכם, והאחריות עליכם.

התראות על קווים (עיקופים, עיכובים, תחנות שזזו) לא מגיעות מהמקור לזמן אמת: שרת לוקח אותן מכל פיד התראות של
GTFS-Realtime, וגם מזה של המשרד ([הרצה על השרת שלי](selfhost/README.md#line-alerts)).

## לשמור הקלטה {#saving-a-recording}

הקלטה בשביל `live_replay.py` היא תשובה אחת לשאלה של כל הנסיעות הפעילות. אם יש לכם מפתח משלכם מהמשרד, שאלו מהמחשב
שהמשרד אישר בשבילו, כמו שהשרת של Kav עושה:

```sh
curl -s --cacert server/selfhost/mot-ca.crt \
  "https://moran.mot.gov.il/Channels/HTTPChannel/SmQuery/2.8/json?Key=$MOT_KEY&MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal" \
  > recorded.json
```

`mot-ca.crt` היא שרשרת התעודות שהשרת של המשרד צריך, כמו ש-`live.py` משתמש בה. גם תשובה של כל מקור אחר לאותה שאלה
עובדת.

## לבדוק את המקור שלכם {#checking-yours}

```sh
python3 server/conformance.py live http://my-source:8100/siri
```

הסקריפט שואל את שתי השאלות ומפרט בדיוק מה חסר או לא תקין. אם המקור שלכם יפסיק לעבוד בהמשך, השרת של Kav ישמור לזמן מה את
התמונה האחרונה, ואז לא יראה כלום בזמן אמת. האפליקציה תגיד את זה, ושום דבר אחר לא יישבר.
