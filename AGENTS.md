# Utility-App — project notes

## Build
- Android/Gradle project; no JDK or Android SDK in the sandbox, so builds can only be run by the user
  (`./gradlew :app:assembleDebug`). Do not try to "verify" by building here.
- minSdk 26, so `Context.checkSelfPermission` and `TelecomManager.endCall()` paths are available.

## Behaviour decisions
- Auto Redialer: the dialer screen covering the Activity fires `onStop()` — the redial loop must NOT be
  stopped there. It is stopped only on `onDestroy`/finish, or after a grace delay when background mode is off.
- Background calling is opt-in via the `ar_run_in_background` checkbox (prefs `ar_prefs`).
- Auto disconnect needs `ANSWER_PHONE_CALLS` (Android 9+); `TelecomManager.endCall()` first, ITelephony
  reflection as fallback. Some OEM ROMs still block it.
- SharedPreferences keys must match between save and load: redialer `no_of_calls`/`call_gap`/`disconnect_sec`/
  `disconnect_mode`/`country_code`; sender `no_of_messages`/`message_gap`/`message_text`/`country_code`.
- Timer gaps are clamped to >= 1 s (Timer.schedule throws on 0).
- SMS longer than 160 chars is sent with `sendMultipartTextMessage`.
