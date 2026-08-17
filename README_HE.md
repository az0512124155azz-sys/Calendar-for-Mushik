# מחיקת אירוע בהחלקה ימינה

החלף בפרויקט את הקבצים הבאים בקבצים המצורפים:

- `MainActivity.kt`
- `EventAdapter.kt`
- `item_event.xml`
- `dialog_delete_event.xml`

הוסף לפרויקט את הקבצים החדשים הבאים, בדיוק בתיקיות המופיעות בחבילה:

- `CalendarEventDeleter.kt`
- `values/delete_strings.xml`
- `values-he/delete_strings.xml`
- `values-iw/delete_strings.xml`
- `values-ar/delete_strings.xml`
- `values-fr/delete_strings.xml`
- `values-ru/delete_strings.xml`
- `drawable/bg_event_clip.xml`
- `drawable/bg_cancel_button.xml`

אין צורך לשנות את `CalendarRepository.kt`; פעולת המחיקה הופרדה לקובץ קטן ועצמאי כדי לא לסכן את פעולות החיפוש, ההוספה והעריכה הקיימות.

## Git

- שם התיקון: `מחיקת אירוע בהחלקה ימינה`
- ענף: `feature/swipe-to-delete-calendar-event`
- commit: `feat: add swipe-to-delete for calendar events`

## בדיקה ידנית

1. התחבר ל-Google ופתח יום שיש בו אירוע.
2. החלק את כרטיס האירוע ימינה.
3. ודא שכפתור מחיקה אדום נחשף בצד שמאל.
4. לחץ על הכפתור ובטל בחלון האישור; האירוע צריך להישאר.
5. חזור על הפעולה ואשר; האירוע צריך להימחק מהרשימה ומ-Google Calendar.
6. ודא שבמצב סגור לא רואים אדום דרך הכרטיס ושחלון האישור משתמש בכרטיס הבהיר של האפליקציה.
7. בחר תאריך שאינו היום, החלף שפה וודא שאותו תאריך ואותם אירועים נשארים מוצגים.
8. ודא שכפתור הביטול מוצג ללא פס לבן פנימי.
