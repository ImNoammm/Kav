package uk.noammm.kav.data

// The rider's discount profile as Rav-Pass's Profile tab names it (its view profile_type: the approved profiles, joined by
// "/"), matched only against Rav-Pass's own fixed list of profile names, profile_0 to profile_63 in every language
// Rav-Pass 10.2.30 ships (read from its decompiled copy by kav5-pay2-work/tools/profiles.py), never guessed. The match
// gives Rav-Pass's profile number, and the number Kav's fare profile.
object RavPassProfiles {
    // Rav-Pass's number for each of its profile names.
    private val NAMES: Map<Int, List<String>> = mapOf(
        0 to listOf("Undefined profile", "Категория - общий", "Неопределённый профиль", "פרופיל לא מוגדר", "ملف تعريف غير مُعرّف", "โปรไฟล์ไม่ระบุ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        1 to listOf("Adult profile", "Профиль взрослого", "פרופיל בוגר", "ملف تعريف البالغين", "โปรไฟล์ผู้ใหญ่"),  // values values-ar values-en values-iw values-ru values-th values-uk
        2 to listOf("Child profile", "Льготная категория - ребёнок", "פרופיל ילד", "ملف تعريف الطفل", "โปรไฟล์เด็ก"),  // values values-ar values-en values-iw values-ru values-th values-uk
        3 to listOf("Extended student profile", "Льготная категория - студент расширенный", "פרופיל סטודנט מורחב", "الملف الشخصي الممتد للطالب", "โปรไฟล์นักศึกษาแบบขยาย"),  // values values-ar values-en values-iw values-ru values-th values-uk
        4 to listOf("Senior Women profile", "Senior citizen profile", "Льготная категория - пенсионер", "Пенсионерка профиль", "פרופיל אזרחית ותיקה", "الملف الشخصي النساء المسنات", "โปรไฟล์สตรีสูงอายุ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        5 to listOf("Handicapped profile", "Льготная категория - инвалид", "פרופיל נכה", "ملف تعريف معطل", "โปรไฟล์ผู้พิการ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        6 to listOf("Blind profile", "Льготная категория - слепой", "פרופיל כבד ראיה/עיוור", "ضعاف البصر / الأعمى", "โปรไฟล์ผู้พิการทางสายตา"),  // values values-ar values-en values-iw values-ru values-th values-uk
        7 to listOf("Deaf-Mute profile", "Льготная категория - глухой/немой", "פרופיל חרש/אילם", "الصم / البكم الشخصي", "โปรไฟล์ผู้พิการทางการได้ยินและการพูด"),  // values values-ar values-en values-iw values-ru values-th values-uk
        8 to listOf("Unemployed profile", "Льготная категория - безработный", "פרופיל מובטל", "الملف الشخصي عاطل عن العمل", "โปรไฟล์ผู้ว่างงาน"),  // values values-ar values-en values-iw values-ru values-th values-uk
        9 to listOf("Staff profile", "Льготная категория - персонал", "פרופיל פרסונל", "الملف الشخصي للموظفين", "โปรไฟล์พนักงาน"),  // values values-ar values-en values-iw values-ru values-th values-uk
        10 to listOf("Military profile", "Льготная категория - силы безопасности", "פרופיל כוחות הבטחון", "لمحة عن قوى الأمن", "โปรไฟล์ทหาร"),  // values values-ar values-en values-iw values-ru values-th values-uk
        11 to listOf("Resident profile", "Льготная категория - резидент", "פרופיל תושב", "الملف الشخصي المقيم", "โปรไฟล์ผู้อยู่อาศัย"),  // values values-ar values-en values-iw values-ru values-th values-uk
        12 to listOf("Industrial luggage profile", "פרופיל תובלה", "الملف الشخصي النقل", "โปรไฟล์สัมภาระอุตสาหกรรม"),  // values values-ar values-en values-iw values-ru values-th values-uk
        13 to listOf("Transit staff profile", "Профиль транзитного персонала", "פרופיל עובד תחבורה ציבורית", "ملف تعريف موظف النقل العام", "โปรไฟล์พนักงานขนส่ง"),  // values values-ar values-en values-iw values-ru values-th values-uk
        14 to listOf("Far distance transport profile", "Долгосрочный транспортный профиль", "פרופיל הובלה ארוכת טווח", "ملف تعريف النقل طويل الأجل", "โปรไฟล์ขนส่งระยะไกล"),  // values values-ar values-en values-iw values-ru values-th values-uk
        15 to listOf("Local transport profile", "Профиль местного транспорта", "פרופיל הובלה מקומית", "ملف تعريف النقل المحلي", "โปรไฟล์ขนส่งท้องถิ่น"),  // values values-ar values-en values-iw values-ru values-th values-uk
        16 to listOf("Commuter profile", "פרופיל יומם", "الملف الشخصي Yom", "โปรไฟล์ผู้เดินทางประจำ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        17 to listOf("Animal profile", "Профиль животного", "פרופיל בעל חיים", "الملف الشخصي للحيوان", "โปรไฟล์สัตว์"),  // values values-ar values-en values-iw values-ru values-th values-uk
        18 to listOf("Object profile", "Профиль пакета", "פרופיל חבילה", "ملف تعريف الحزمة", "โปรไฟล์สิ่งของ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        19 to listOf("Regular student profile", "Льготная категория - студент стандартный", "פרופיל סטודנט רגיל", "ملف شخصي عادي للطالب", "โปรไฟล์นักศึกษาปกติ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        32 to listOf("Child age 5-10 profile", "Льготная категория - ребёнок 5-10 лет", "פרופיל ילד גיל 5-10", "نبذة عن طفل يبلغ من العمر 5-10 سنوات", "โปรไฟล์เด็กอายุ 5-10 ปี"),  // values values-ar values-en values-iw values-ru values-th values-uk
        33 to listOf("Youth profile", "Youth under 18 profile", "Льготная категория - молодежь до 18 лет", "Молодежный профиль", "פרופיל נוער", "الملف الشخصي للشباب", "โปรไฟล์เยาวชน"),  // values values-ar values-en values-iw values-ru values-th values-uk
        34 to listOf("National Service profile", "Льготная категория - национальная службы", "פרופיל שירות לאומי", "ملف الخدمة الوطنية", "โปรไฟล์บริการแห่งชาติ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        35 to listOf("Military serving without pay profile", "Шалат / Кадац профиль", "פרופיל של\"ת/קד\"צ", "ملف تعريف SLA / KDZ", "โปรไฟล์ทหารที่รับราชการโดยไม่ได้รับเงินเดือน"),  // values values-ar values-en values-iw values-ru values-th values-uk
        36 to listOf("Police profile", "Профиль полицейского", "פרופיל משטרת ישראל", "ملف شرطة إسرائيل", "โปรไฟล์ตำรวจ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        37 to listOf("Prison guard profile", "Профиль тюремной службы", "פרופיל שירות בתי הסוהר", "ملف تعريف خدمة السجون", "โปรไฟล์ผู้คุมเรือนจำ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        38 to listOf("Knesset member profile", "Профиль депутат кнессета", "פרופיל חבר כנסת", "عضو الكنيست", "โปรไฟล์สมาชิก Knesset"),  // values values-ar values-en values-iw values-ru values-th values-uk
        39 to listOf("Knesset guard profile", "פרופיל משמר הכנסת", "لمحة عن حرس الكنيست", "โปรไฟล์เจ้าหน้าที่รักษาความปลอดภัย Knesset"),  // values values-ar values-en values-iw values-ru values-th values-uk
        40 to listOf("National insurance profile", "Льготная категория - битуах леуми", "פרופיל זכאי ביטוח לאומי", "ملف التعريف المؤهل للتأمين الوطني", "โปรไฟล์ประกันแห่งชาติ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        41 to listOf("IDF/hostility disabled profile", "Профиль жертвы боевых действий", "פרופיל נפגע פעולות איבה", "الملف الشخصي المتضرر من الأعمال العدائية", "โปรไฟล์ผู้พิการจาก IDF/เหตุการณ์ความไม่สงบ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        42 to listOf("Rural immigrant profile", "Профиль нового иммигранта в сельской местности", "פרופיל עולה חדש ביישוב כפרי", "لمحة عن مهاجر جديد في مستوطنة ريفية", "โปรไฟล์ผู้อพยพในชนบท"),  // values values-ar values-en values-iw values-ru values-th values-uk
        43 to listOf("Accompanied by the blind profile", "Бумажная карточка для сопровождения слепых", "פרופיל כרטיס נייר למלווה אדם עיוור", "بروفايل ورقية لمرافقة شخص كفيف", "โปรไฟล์ผู้ติดตามผู้พิการทางสายตา"),  // values values-ar values-en values-iw values-ru values-th values-uk
        44 to listOf("Year of service profile", "Год службы профиля", "פרופיל שנת שירות", "ملف تعريف سنة الخدمة", "โปรไฟล์ปีบริการ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        45 to listOf("Senior citizen 67+", "Zahav Kav", "Захав Кав", "Пенсионер 67+", "זהב 67+", "الذهب 67+", "ผู้สูงอายุ 67+"),  // values values-ar values-en values-iw values-ru values-th values-uk
        47 to listOf("Youngster (age 18-26)", "Молодые люди (18-26 лет)", "צעיר (גיל 18-26)", "الشباب (من 18 إلى 26 سنة)", "เยาวชน (อายุ 18-26)"),  // values values-ar values-en values-iw values-ru values-th values-uk
        48 to listOf("Geographic", "Географический", "גאוגרפי", "يف جغرافي"),  // values values-ar values-en values-iw values-ru values-th values-uk
        49 to listOf("Ex-serviceman", "Освобожденный солдат", "חייל משוחרר", "جندي مسرّح"),  // values values-ar values-en values-iw values-ru values-th values-uk
        51 to listOf("Rail handicapped profile", "Rail неверный профиль", "פרופיל נכה ברכבת ישראל", "لمحة عن شخص معاق في قطار اسرائيل", "โปรไฟล์ผู้พิการสำหรับรถไฟ"),  // values values-ar values-en values-iw values-ru values-th values-uk
        52 to listOf("Golan regional council profile", "Профиль Голанского регионального совета", "פרופיל מועצה אזורית גולן", "لمحة عن مجلس الجولان الإقليمي", "โปรไฟล์สภาภูมิภาคโกลัน"),  // values values-ar values-en values-iw values-ru values-th values-uk
        53 to listOf("South settlement resident rail profile", "Профиль железной дороге для жителей южных населенных", "פרופיל נסיעה ברכבת לתושב ישובי הדרום", "ملف سفر القطار لسكان المستوطنات الجنوبية", "โปรไฟล์รถไฟสำหรับผู้อยู่อาศัยในนิคมภาคใต้"),  // values values-ar values-en values-iw values-ru values-th values-uk
        54 to listOf("Valley resident rail profile", "Профиль железной дороге для жителей долины", "פרופיל נסיעה ברכבת לתושב העמק", "ملف تعريف السفر بالسكك الحديدية لسكان الوادي", "โปรไฟล์รถไฟสำหรับผู้อยู่อาศัยในหุบเขา"),  // values values-ar values-en values-iw values-ru values-th values-uk
        55 to listOf("Ahead rail profile", "Профиль железной дороге для жителей Ахихуд", "פרופיל נסיעה ברכבת לתושב אחיהוד", "قطار ملف السفر لمقيم في أتشهود", "โปรไฟล์รถไฟ Ahead"),  // values values-ar values-en values-iw values-ru values-th values-uk
        56 to listOf("Eilat citizen profile", "Профиль гражданина Эйлата", "פרופיל תושב אילת", "ملف مقيم في إيلات", "โปรไฟล์พลเมืองไอแลต"),  // values values-ar values-en values-iw values-ru values-th values-uk
        57 to listOf("Citypass profile", "Citypass профиль", "פרופיל סיטיפס", "الملف الشخصي Citypass", "โปรไฟล์ Citypass"),  // values values-ar values-en values-iw values-ru values-th values-uk
        58 to listOf("Egged Taavura profile", "Эгед Таавура профиль", "פרופיל אגד תעבורה", "الذين تتراوح أعمارهم بين الملف الشخصي Tafura", "โปรไฟล์ Egged Taavura"),  // values values-ar values-en values-iw values-ru values-th values-uk
        59 to listOf("Inter-city pupil profile", "Межгородский профиль ученика", "פרופיל תלמיד בנסיעה בין עירונית", "ملف تعريف لطالب يسافر بين المدن", "โปรไฟล์นักเรียนระหว่างเมือง"),  // values values-ar values-en values-iw values-ru values-th values-uk
        60 to listOf("Ramallah profile", "Рамаллах профиль", "פרופיל רמאללה", "ملف رام الله", "โปรไฟล์รามัลลาห์"),  // values values-ar values-en values-iw values-ru values-th values-uk
        61 to listOf("South transporters profile", "юг", "פרופיל מסיעי הדרום", "لمحة عن الناقلات الجنوبية", "โปรไฟล์ผู้ขนส่งภาคใต้"),  // values values-ar values-en values-iw values-ru values-th values-uk
        62 to listOf("Afikim profile", "Афиким профиль", "פרופיל אפיקים", "الملف الشخصي الأفنيوز", "โปรไฟล์ Afikim"),  // values values-ar values-en values-iw values-ru values-th values-uk
        63 to listOf("Eilat senior citizen profile", "Профиль пенсионера Эйлата", "פרופיל ותיק תושב אילת", "نبذة عن أحد السكان المخضرمين في إيلات", "โปรไฟล์ผู้สูงอายุไอแลต"),  // values values-ar values-en values-iw values-ru values-th values-uk
    )

    // Kav's fare profile for Rav-Pass's numbers that have one (Kav's own fare rules, FareProfile). 4 is Rav-Pass's senior
    // women (אזרחית ותיקה; "Senior citizen profile" in its English file only) and 45 its 67 and over (זהב 67+). 34 and 44
    // serve now rather than discharged, but ride free as the recently discharged do. The rest (a child, staff, rail-only,
    // local and company profiles, the undefined one) have no fare profile in Kav.
    private val FARE: Map<Int, FareProfile> = mapOf(
        1 to FareProfile.REGULAR,
        19 to FareProfile.STUDENT, 3 to FareProfile.STUDENT,
        4 to FareProfile.SENIOR_WOMAN, 45 to FareProfile.SENIOR,
        33 to FareProfile.YOUTH,
        47 to FareProfile.YOUNG,
        40 to FareProfile.NATIONAL_INSURANCE,
        5 to FareProfile.DISABILITY, 41 to FareProfile.DISABILITY,
        6 to FareProfile.BLIND,
        48 to FareProfile.GEOGRAPHIC,
        49 to FareProfile.DISCHARGED, 44 to FareProfile.DISCHARGED, 34 to FareProfile.DISCHARGED,
    )

    private fun key(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
    private val BY_NAME: Map<String, Int> = NAMES.flatMap { (n, names) -> names.map { key(it) to n } }.toMap()

    sealed class Read {
        // One fare profile for everything shown.
        data class Fare(val profile: FareProfile, val shown: String) : Read()
        // A name of Rav-Pass's own with no fare profile in Kav.
        data class NoFare(val shown: String) : Read()
        // A name that isn't one of Rav-Pass's own.
        data class Unknown(val shown: String) : Read()
        // Several profiles that are different fare profiles in Kav.
        data class Several(val shown: String) : Read()
        // Nothing shown.
        object Nothing : Read()
    }

    // Rav-Pass's numbers for the text, or null when any part isn't a name of its own. The whole text first, then its parts
    // between "/", each the longest run of parts that is one name ("IDF/hostility disabled profile" holds a "/" itself).
    fun numbers(text: String): List<Int>? {
        BY_NAME[key(text)]?.let { return listOf(it) }
        val parts = text.split('/')
        val out = mutableListOf<Int>()
        var i = 0
        while (i < parts.size) {
            val j = (parts.size downTo i + 1).firstOrNull { BY_NAME[key(parts.subList(i, it).joinToString("/"))] != null } ?: return null
            out += BY_NAME.getValue(key(parts.subList(i, j).joinToString("/")))
            i = j
        }
        return out
    }

    fun read(text: String?): Read {
        val shown = text?.trim().orEmpty()
        if (shown.isEmpty()) return Read.Nothing
        val numbers = numbers(shown) ?: return Read.Unknown(shown)
        val fares = numbers.mapNotNull { FARE[it] }.distinct()
        return when {
            fares.isEmpty() -> Read.NoFare(shown)
            fares.size > 1 -> Read.Several(shown)
            // A profile with a fare and another without one (a resident's, say): the one with the fare.
            else -> Read.Fare(fares.single(), shown)
        }
    }
}
