# ساخت APK امضاشده و قابل آپدیت

برای اینکه نسخه‌های بعدی بدون حذف برنامه روی نسخه فعلی نصب شوند و تاریخچه محلی گفتگوها پاک نشود، همه نسخه‌های اصلی باید با یک Keystore ثابت امضا شوند. APKهای Debug که در Runnerهای جداگانه GitHub ساخته می‌شوند ممکن است امضای یکسانی نداشته باشند.

## Secrets لازم در GitHub

از مسیر زیر وارد شوید:

`Settings → Secrets and variables → Actions → New repository secret`

چهار Secret زیر را بسازید:

- `RAVIX_KEYSTORE_BASE64`: محتوای Base64 کامل فایل Keystore
- `RAVIX_KEYSTORE_PASSWORD`: رمز Keystore
- `RAVIX_KEY_ALIAS`: نام Alias کلید
- `RAVIX_KEY_PASSWORD`: رمز کلید

برای تبدیل Keystore به Base64 در PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ravix-release.jks")) | Set-Content -NoNewline keystore-base64.txt
```

سپس در تب Actions، Workflow زیر را اجرا کنید:

`Build Signed Ravix Chat APK`

فایل خروجی `ravix-chat-operator-v0.3.0-release.apk` است. Keystore و رمزهای آن را در چند محل امن نگه‌داری کنید؛ از دست‌دادن این کلید یعنی نسخه‌های بعدی روی برنامه نصب‌شده قابل آپدیت نخواهند بود.
