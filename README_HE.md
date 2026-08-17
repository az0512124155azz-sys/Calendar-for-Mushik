# תמונת פרופיל ותפריט חשבון Google

החלף בפרויקט:

- `MainActivity.kt`
- `res/layout/activity_main.xml`

הוסף את הקבצים החדשים מהחבילה:

- `res/layout/dialog_account_menu.xml`
- `res/drawable/bg_profile_circle.xml`
- `res/drawable/bg_account_action.xml`
- `res/drawable/bg_sign_out_button.xml`
- כל קובצי `account_strings.xml` בתיקיות השפה המתאימות

לאחר התחברות מוצגת תמונת Google בצד הנגדי לבורר השפה. אם אין תמונה, מוצגת האות הראשונה בשם. לחיצה פותחת פרטי חשבון, ניהול חשבון Google והתנתקות.

ההתנתקות אינה מוחקת אירועים. היא מנקה את החשבון והרשימה מהאפליקציה ומחזירה למסך ההתחברות.

## Git

- שם התיקון: `תמונת פרופיל ותפריט חשבון Google`
- ענף: `feature/google-account-menu`
- commit: `feat: add Google profile menu and sign out`
