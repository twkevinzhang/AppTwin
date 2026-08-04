# M0 LINE GroupApp acceptance

Acceptance was executed on 2026-08-03 against an unrooted, bootloader-locked ASUS_I002D running
Android 12/API 31. The source package was the main-system installation of
`jp.naver.line.android` 15.5.4 (`versionCode=150540375`).

## Accepted user path

1. Sideload the clean `runtimeProbeDebug` APK without clearing MaskAccounts data.
2. Launch the persisted LINE GroupApp through MaskAccounts.
3. Wait 75 seconds, exceeding the former 60-second initialization failure threshold.
4. Confirm that the screen still contains `歡迎使用LINE`, `登入`, and `註冊新帳號`.
5. Confirm that logcat contains neither `MainProcessInitializationException` nor LINE's
   `System.exit called` failure path.

Result: passed. ActivityManager reported the host component
`org.maskaccounts/com.lody.virtual.client.stub.StubActivity$C0`, while the active guest window was
`jp.naver.line.android/com.linecorp.registration.ui.RegistrationActivity`.

## GroupApp isolation proof

| Evidence | Main-system LINE | MaskAccounts GroupApp |
| --- | --- | --- |
| Android UID | `10744` | `10959` (MaskAccounts UID) |
| Process | normal installed LINE package | process label `jp.naver.line.android`, PID owned by UID `10959` |
| Android package/component | `jp.naver.line.android` | host `org.maskaccounts` `StubActivity$C0` |
| Private data | `/data/user/0/jp.naver.line.android` | `/data/user/0/org.maskaccounts/virtual/data/user/0/jp.naver.line.android` |
| Code source | main-system base and split APKs | host-private virtual package revision |

The virtual base and split APK checksums exactly matched the installed source:

| APK | SHA-256 |
| --- | --- |
| `base.apk` | `7922e56e0dec0e9619d745c9e4eae26101396f091f5da3947d75195cce34144e` |
| `config.arm64_v8a.apk` | `a5435f856b0babd91d242c3186b369f3174a018c60d7592e7abe9cb827077122` |
| `config.xxhdpi.apk` | `d92de43fa842fdc383afd19a79771e5e97e463c6eace2c694fda8b889d1fd6b0` |

Together, these observations prove that the accepted screen came from unchanged LINE code loaded
inside MaskAccounts, using a MaskAccounts-owned process and separate host-private data, rather than
from the already-installed original LINE activity.

## Regression fixed during acceptance

On API 31, AndroidX emulates a non-exported dynamic receiver with a generated signature permission
named `<guest package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. A virtual context returned LINE's
package name, but the OS permission check used MaskAccounts' real UID. LINE's `TimelineTask` then
failed component creation and timed out after 60 seconds.

The runtime now reports the guest-generated permission as granted inside the container and rewrites
the OS `registerReceiver` requirement to
`org.maskaccounts.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which is owned by the host with
`signature` protection. This both unblocks initialization and keeps the receiver inaccessible to
unrelated apps.
