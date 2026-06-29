# قرارداد مورد انتظار سرور Ravix Chat

## ورود

اپ برای سازگاری، مسیرهای زیر را به‌ترتیب امتحان می‌کند:

1. `POST /api/operator/login`
2. `POST /api/operator/session`
3. `POST /operator/api/login`
4. `POST /operator/login`

بدنه JSON یا Form شامل `username` و `password` است. پاسخ می‌تواند یکی از کلیدهای زیر را برگرداند:

- `token`
- `access_token`
- `operator_token`
- `authToken`

کلیدهای اختیاری:

- `socket_url`
- `socket_path`

اگر سرور Cookie Session صادر کند، اپ همان Cookie را نیز برای اتصال Socket.IO نگه می‌دارد.

## Socket.IO

اتصال با `role=operator` و توکن در `auth` و `query` برقرار می‌شود تا با پیاده‌سازی فعلی و نسخه‌های قبلی سازگار باشد.
