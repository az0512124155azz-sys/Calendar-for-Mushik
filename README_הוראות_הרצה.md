# GCal Search & Add - מדריך הרצה ב-Android Studio

## שלב 1: פתיחת הפרויקט
1. פתח את Android Studio.
2. `File → Open` ובחר את התיקייה `GCalSearchAdd` (זו שמכילה את הקובץ `settings.gradle`).
3. חכה ל-Gradle Sync (עלול לקחת כמה דקות בפעם הראשונה).

## שלב 2: קבלת SHA-1 (מפתח החתימה)
זה הדבר החשוב ביותר ליצירת ה-Client ID הנכון בגוגל:
1. בפינה הימנית העליונה ב-Android Studio: `Gradle` (פאנל צד) → `app → Tasks → android → signingReport`.
2. לחיצה כפולה על `signingReport`, יריץ אותו ותקבל בלוג פלט שכולל שורה `SHA1: XX:XX:XX:...`.
3. **העתק את מחרוזת ה-SHA1** - תצטרך אותה בשלב הבא.
   (זה ה-debug SHA-1 שנוצר אוטומטית - מספיק לבדיקות. לפרסום בפועל ל-Play Store תצטרך גם SHA-1 של ה-release keystore.)

## שלב 3: יצירת פרויקט ב-Google Cloud Console
1. גש ל-https://console.cloud.google.com/
2. צור פרויקט חדש (או בחר קיים).
3. בתפריט: `APIs & Services → Library` → חפש **Google Calendar API** → לחץ **Enable**.
4. בתפריט: `APIs & Services → OAuth consent screen`:
   - סוג: External.
   - מלא שם אפליקציה, מייל תמיכה.
   - הוסף את עצמך כ-Test User (חשוב! אחרת ה-Sign-In ייכשל).
5. בתפריט: `APIs & Services → Credentials → Create Credentials → OAuth client ID`:
   - Application type: **Android**.
   - Package name: `com.magic3d.gcalsearchadd` (מופיע ב-`app/build.gradle` תחת `applicationId`).
   - SHA-1: הדבק את מה שהעתקת בשלב 2.
   - שמור.

**חשוב:** באפליקציות Android, ה-Client ID הזה לא נכנס בקוד בכלל - Google Sign-In מזהה אותו אוטומטית לפי ה-package name + SHA-1 שרשמת. לכן אין שום מפתח API שצריך להדביק בקבצי הקוד.

## שלב 4: הרצה
1. חבר מכשיר Android אמיתי או הפעל אמולטור עם Google Play Services (Play Store חייב להיות מותקן באימאג' של האמולטור).
2. לחץ Run ▶.
3. באפליקציה: לחץ "התחבר עם Google", בחר את החשבון שהוספת כ-Test User.
4. אשר את ההרשאה ליומן כשהיא מבוקשת.

## מבנה הפרויקט
- `MainActivity.kt` - מסך ראשי: שורת חיפוש (תאריך/מילת מפתח), רשימת אירועים, כפתור פלוס.
- `AddEventActivity.kt` - טופס הוספת אירוע, כולל ברירת המחדל 14:00-23:59 אם לא נבחרו שעות.
- `CalendarRepository.kt` - כל התקשורת מול Google Calendar API (שליפה, חיפוש, הוספה).
- `EventAdapter.kt` - RecyclerView לתצוגת האירועים.

## בעיות נפוצות
- **"Sign in failed" / קוד שגיאה 10**: כמעט תמיד SHA-1 לא תואם, או שכחת Enable ל-Calendar API.
- **"App not verified"**: תקין בשלב פיתוח - כל עוד אתה רשום כ-Test User זה יעבוד, פשוט תלחץ "Advanced → Go to app (unsafe)".
- **הרשאה נדחית שוב ושוב**: ודא שה-scope בקוד (`CalendarScopes.CALENDAR`) תואם למה שאישרת ב-OAuth consent screen.
