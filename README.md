# GCal Search & Add - הוראות התקנה

## מה כבר בנוי
כל הקוד מוכן: מסך ראשי, חיפוש אירועים לפי תאריך/מילת מפתח, הוספת אירוע עם ברירת המחדל של
14:00-23:59, עיצוב בסגנון Google Calendar.

**הנקודה החשובה: איך "מכניסים" את ה-Client ID לאפליקציה**

בשונה מ-Firebase, ל-GoogleSignIn באנדרואיד **אין קובץ config שמכניסים** (כמו google-services.json).
במקום זה, גוגל מזהה את האפליקציה שלך לפי שילוב של **applicationId + SHA-1 fingerprint**, שנרשמים
ב-Google Cloud Console. הקוד לא צריך לדעת שום Client ID בעצמו!

## שלב 1: יצירת פרויקט ב-Google Cloud Console
1. כנס ל-https://console.cloud.google.com/
2. צור פרויקט חדש (או השתמש בקיים)
3. בתפריט: APIs & Services -> Library -> חפש "Google Calendar API" -> Enable

## שלב 2: מסך הסכמה (OAuth consent screen)
1. APIs & Services -> OAuth consent screen
2. סוג: External (אלא אם יש לך Google Workspace)
3. מלא שם אפליקציה, מייל תמיכה
4. ב-Scopes הוסף: `https://www.googleapis.com/auth/calendar`
5. הוסף את עצמך כ-Test User (חשוב! אחרת ההתחברות תיכשל)

## שלב 3: יצירת Client ID מסוג Android
1. APIs & Services -> Credentials -> Create Credentials -> OAuth client ID
2. Application type: **Android**
3. Package name: `com.magic3d.gcalsearchadd` (זהה בדיוק למה שמוגדר ב-build.gradle.kts)
4. SHA-1 fingerprint: צריך להריץ בטרמינל של Android Studio:
   ```
   ./gradlew signingReport
   ```
   העתק את ה-SHA1 שמופיע תחת "debug" ותדביק אותו בשדה המתאים.
5. שמור.

זהו - **אין צורך להעתיק שום מחרוזת client_id לקוד**. גוגל מזהה את האפליקציה
אוטומטית לפי ה-package name + SHA-1 שרשמת, בכל פעם שהיא קוראת ל-Google Sign-In.

## שלב 4: פתיחה ב-Android Studio
1. פתח את התיקייה `GCalSearchAdd` כפרויקט קיים (Open -> בחר את התיקייה)
2. תן ל-Gradle לסנכרן (יופיע אוטומטית, או Sync Now למעלה)
3. הרץ על מכשיר/אמולטור עם Google Play Services מותקן (אמולטור צריך להיות עם "Google APIs" או "Google Play")

## חשוב לזכור
- אם תרצה להוציא את האפליקציה ל-Production (מעבר ל-Test Users), Google תדרוש אימות (Verification)
  כי scope של calendar נחשב "sensitive". לפיתוח ובדיקות אישיות זה לא נדרש.
- אם תחליט בעתיד לבנות APK חתום (Release), תצטרך ליצור SHA-1 נוסף של מפתח ה-release ולהוסיף
  אותו כ-Client ID נוסף באותו פרויקט.
- כל הלוגיקה של חיפוש/הוספה נמצאת ב-`CalendarHelper.kt`.

בהצלחה! 🎉
