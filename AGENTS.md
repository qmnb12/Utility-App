# Utility-App — project notes

## Build and verification
- Android/Gradle project. The working environment has no Android SDK, so a full Android build cannot be verified here.
- The project uses Android Gradle Plugin 9.3.1 and Gradle Wrapper 9.6.1. The wrapper version satisfies AGP 9.3's documented Gradle 9.5 minimum.
- Run `./gradlew :app:assembleDebug` in Code On The Go or an Android SDK environment.
- Run local unit tests with `./gradlew :app:testDebugUnitTest`.

## Automatic Redialer
- A redial attempt is not started while the previous call is still active or waiting for a phone-state transition.
- The Activity registers for `TelephonyManager.ACTION_PHONE_STATE_CHANGED`; the next attempt is gated on the previous call returning to IDLE.
- USSD mode validates one USSD code and creates a tel: URI using `Uri.fromParts`. Batch USSD is intentionally unsupported.
- Auto-disconnect uses the public `TelecomManager.endCall()` API and requires `ANSWER_PHONE_CALLS` on Android 9+. There is no hidden `ITelephony` reflection fallback.
- Android background-activity-launch restrictions mean an app must not try to launch the next `ACTION_CALL` from the background. The background checkbox preserves the session state, but a new call is launched only while the Activity is visible. OEM/carrier call-state behavior may still vary.

## Message Sender
- Repeated SMS sends are foreground-only. Leaving the Message Sender screen cancels the active loop; no background preference is read unless a real foreground-service implementation is added later.
- All SMS uses the shared `SmsDispatch` helper and multipart send API, including short messages. Per-part send results are recorded by `SmsStatusReceiver`.
- Clipboard-filled numbers are treated as valid input and normalized before use.
- Random placeholders are processed without API-34-only Java methods. Placeholder lengths are limited to 1–50.

## Scheduled and multi-recipient SMS
- Phone numbers are normalized by `PhoneNumberHelper`; recipient lists are capped at 50 and duplicate numbers are removed.
- Scheduled SMS jobs are persisted in `scheduled_sms_data` and rescheduled after boot or app replacement.
- Exact alarms require user-granted Alarms & reminders access on Android 12+. When access is unavailable, the UI explicitly offers approximate scheduling; it does not claim exact timing.
- SMS send results are asynchronous. A successful submission callback indicates acceptance by the SMS framework/carrier, not proof of delivery to the recipient.

## Preferences
- Redialer preferences: `ar_prefs`.
- Message Sender preferences: `ms_prefs`.
- Feature Hub/history preferences: `utility_feature_data`.
- Pending scheduled messages: `scheduled_sms_data`.
- Version 2 JSON backup includes all four stores; restore also accepts legacy version 1 backups.
