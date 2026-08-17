תיקון אופליין מלא ל-EventSpot

לפני ההעתקה:
1. סגור את Android Studio או עצור את ההרצה.
2. פתח את קובץ ה-ZIP.
3. העתק את התיקייה app מתוך ה-ZIP אל שורש הפרויקט שלך.
4. בחר Replace / החלף קבצים קיימים כאשר Windows או Android Studio מבקשים.

מבנה נכון לאחר ההעתקה:
<תיקיית הפרויקט>/app/build.gradle.kts
<תיקיית הפרויקט>/app/src/main/java/com/magic3d/gcalsearchadd/CalendarRepository.kt

לא נכון ליצור תיקייה נוספת בשם EventSpot-Offline-Complete-Patch או app בתוך app.

אחרי ההעתקה:
1. פתח את Android Studio.
2. לחץ Sync Now.
3. בחר Build > Clean Project.
4. בחר Build > Rebuild Project.
5. התקן את האפליקציה.

התנהגות:
- בלי אינטרנט, אירועים שכבר נשמרו במטמון נשארים מוצגים.
- אירוע חדש באופליין נשמר במכשיר ומוצגת הודעה שהוא יתווסף ליומן כאשר יהיה אינטרנט.
- עם חזרת האינטרנט, הפעולות הממתינות נשלחות ליומן.
- בהתחברות ובהמשך כל שלוש שעות מתבצעת הורדה מלאה של היומן.
