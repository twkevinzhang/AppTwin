# M0 Shopee Taiwan GroupApp acceptance

Acceptance was executed on 2026-08-03 against an unrooted, bootloader-locked ASUS_I002D running
Android 12/API 31. The source package was the main-system installation of `com.shopee.tw` 3.79.27
(`versionCode=37927`). No real account credentials were entered and no purchase was made.

> **Legacy naming note:** This acceptance predates the AppTwin rename. MaskAccounts,
> `org.maskaccounts`, UID `10959`, and the recorded private-data paths are preserved exactly as
> historical device evidence; the current product and package names are AppTwin and `org.apptwin`.

## Accepted user paths

1. Sideload the `runtimeProbeDebug` APK without clearing MaskAccounts data.
2. Select the imported Shopee package and launch its persisted GroupApp.
3. Confirm that Shopee's live home screen and remote content render.
4. Return to MaskAccounts, select Shopee, and choose **開啟登入**.
5. Confirm that the native screen shows phone/email/user name, password, SMS login, Facebook,
   LINE, and registration controls.
6. Keep the login screen in the foreground for more than 75 seconds and confirm that it remains
   responsive with no fatal exception, VM exit, Apache HTTP class failure, or cross-UID code-load
   rejection.
7. Press Home, wait eight seconds, restore the guest task, and confirm that the same login screen
   resumes.
8. Force-stop MaskAccounts, launch the Shopee GroupApp again, and confirm that the live home screen
   loads from a cold process.

Result: passed. The final 75-second foreground run used host component
`org.maskaccounts/com.lody.virtual.client.stub.StubActivity$C0` and virtual target
`com.shopee.tw/com.shopee.app.ui.auth2.login.origin.LoginActivity_`. A subsequent force-stop cold
run reached `com.shopee.app.ui.home.HomeActivity_` and rendered live Shopee content.

## GroupApp isolation proof

| Evidence | Main-system Shopee | MaskAccounts GroupApp |
| --- | --- | --- |
| Android UID | `10956` | `10959` (MaskAccounts UID) |
| Process | original package process | process label `com.shopee.tw`, PID owned by UID `10959` |
| Android component | installed `com.shopee.tw` activity | host `org.maskaccounts` `StubActivity$C0` with virtual Shopee target |
| Private data | `/data/user/0/com.shopee.tw` | `/data/user/0/org.maskaccounts/virtual/data/user/0/com.shopee.tw` |
| Code source | installed base and split APKs | host-private virtual package revision |

The virtual base and split APK checksums exactly matched the installed source:

| APK | SHA-256 |
| --- | --- |
| `base.apk` | `29185d730cf38434e0bf48fafe74c28421ca22c97344e2cfd7ab4a3e9c9d0ff2` |
| `config.arm64_v8a.apk` | `ef63ee055df93fbfdda9eee4bad3815fe0accc0864e3301514f7756a524709d9` |
| `config.xxhdpi.apk` | `5a0f199558287510adb2244ae579d0a364a0cf6804f27299d5502aa513725c08` |

The installed Shopee package retained UID `10956`; the accepted process reported name
`com.shopee.tw` but `/proc/<pid>/status` reported UID/GID `10959`. Together with the virtual stub
component, separate host-private data tree, and matching APK hashes, this proves that the observed
screens came from imported Shopee code running as a MaskAccounts GroupApp rather than from the
main-system Shopee process.

## Android 12 compatibility fixes

Shopee's Volley path uses `org.apache.http.ProtocolVersion`. Although the installed app declares
the optional Apache HTTP legacy library, the Android 12 virtual delegate class loader previously
contained only `android.test.base.jar`. MaskAccounts now adds the available platform Apache HTTP
legacy JAR to that delegate path.

Shopee's Hermes loader also calls `createPackageContext(INCLUDE_CODE)` for its own package. The
runtime previously created and cached the first guest `LoadedApk` while Android's `ActivityThread`
still identified the process as MaskAccounts, causing Android 12 to reject the later code load as a
cross-UID security violation. The runtime now binds the virtual identity before creating the first
guest package context.

## External verification boundary

Shopee's server may redirect normal navigation to a traffic-verification page depending on device
or network signals. MaskAccounts does not spoof Play Integrity, bypass a challenge, automate real
credentials, or perform purchases. This acceptance covers GroupApp startup, live home rendering, the
declared native login UI, ordinary background return, and process/data/code isolation only.
