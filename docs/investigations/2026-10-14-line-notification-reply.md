# cloned LINE 通知直接回覆

## 驗收契約與環境

2026-10-14 使用者明確授權本次使用實體 Pixel 10a、保留資料更新 APK，以及在 host LINE 與指定測試對話傳送訊息。沿用 `main`，未建立或切換 branch。裝置只有一個既有 Space；不清除資料、不重建 Space、不修改 LINE 登入。

Given：cloned LINE 已登入、通知帶原生回覆 action，AppTwin Activity 不在前景。When：在通知輸入唯一測試文字並按送出。Then：host LINE 指定對話收到一次，cloned LINE 對話顯示同一則，通知直接回覆介面正常結束。

原安裝版本 `0.1.21-pixel10a-native-maps`（1021）；驗收 APK `0.1.22-notification-reply`（1022），runtimeProbe debug / target 28。host LINE 26.15.1。更新使用 `adb install -r`，未 uninstall AppTwin。

Android CLI layout instrumentation 被 Play Protect 因舊版相容性封鎖；未繞過封鎖或修改安全設定，改用既有 ADB uiautomator dump／截圖。未使用裝置解鎖碼，亦不在紀錄保存任何 credential。

## 定位與修正

LINE `SEND_MESSAGE_ACTION` 的原生 PendingIntent 是 mutable startService，經 `StubPendingService` 轉送。原版本系統紀錄為 sent=true，但通知回覆仍等待、接收端與 cloned 對話皆未出現回覆測試標記。

原 stub 只解包 `_VA_|_intent_`，未傳遞系統填入外層 Intent 的 RemoteInput。修正於既有 generation 驗證後，複製 original Intent，使用正式 RemoteInput API 移轉文字、data results 與 results source。保留原 action、component、package、data、categories 與 routing extras；不合併任意外層 extras／ClipData、不改 mutable flags、不改 workload gate 或 keepalive。

Receiver 與 Service 共用 helper。非 RemoteInput ClipData 保持既有 dispatch；真正 malformed reply 不轉送部分資料。既有 framework text/data Bundle 各自複製，避免 framework 原地覆寫 original。LINE-only debug checkpoint `PendingReplyTrace` 僅記錄 route 與結果存在的布林值，不記錄訊息、keys、URI、對話識別值或 token。

## 已執行驗收

| 項目 | 結果 |
|---|---|
| 原版本重現 | 收到 ingress 測試通知；通知回覆送出後仍等待，未見 outgoing 標記 |
| 初版實機正常回覆 | host 收到一則、clone outgoing 一則、回覆介面結束 |
| 最終版本正常回覆 | host 與 clone 各一則正確 outgoing 標記，回覆控制項消失 |
| 取消輸入 | 未按送出；host 與 clone 未見取消標記 |
| 最終版本斷網／恢復 | 暫停 Wi-Fi 與行動網路，按一次送出；8 秒後恢復，host 與 clone 各出現一則；AppTwin 未加入重送 |
| 網路復原 | wifi_on=1、mobile_data=1，與原始設定一致 |

修正版本的 metadata checkpoint 為 `route=service outerText=true outerData=false innerBefore=false innerAfter=true`，支持「外層回覆資料未被帶入內層」的定位。正常回覆與離線恢復的唯一文字只用於指定測試對話；保留測試訊息，未刪除或收回真實資料。

## 自動化與審查

- `:virtual-runtime:testDebugUnitTest`：843 tests，0 failures/errors/skipped；包含既有 PendingIntent generation／ownership 回歸。
- Pixel 10a `PendingIntentRemoteInputInstrumentedTest`：9 tests 通過，使用真正 framework RemoteInput API。涵蓋文字、data/source、routing 不被外層覆寫、原始結果不被污染、多 key／空字串、普通／null-label ClipData、不同目標互不污染。
- `:app:assembleRuntimeProbeDebug`、`:app:assembleRuntimeProbeDebugAndroidTest`、`:virtual-runtime:lintDebug`、`:app:lintRuntimeProbeDebug` 通過；既有 lint baseline warnings 不代表新增錯誤。
- 非作者審查發現並修正 nested Bundle 淺複製及 null-label 普通 ClipData 回歸；最終審查未見新增 routing／generation bypass。

建置使用 Java 17、offline Gradle，版本參數 `-PAPP_VERSION_CODE=1022 -PAPP_VERSION_NAME=0.1.22-notification-reply`。裝置測試僅指定此 class，未執行會變更帳號或 Space 的其他 instrumentation suite。

## 結論與限制

觀察事實：本次 Pixel 10a 的指定分身／對話原始回覆流程已通過；正常回覆及斷網恢復均有實際接收端與 cloned 聊天畫面證據。

推論：原直接回覆資料在 PendingIntent 解包邊界遺失，是本次失敗的主要原因。結論：本次通知文字回覆路徑已修復。可靠度分數：96%。驗證方式：在其他核准裝置、另一個已登入 Space／帳號與其他 LINE 版本重跑相同流程；若接收端未收到或送錯對話，需下修結論。

未驗證：多 Space／多帳號的實際 LINE E2E、附件回覆、長時間冷程序、鎖屏回覆，以及 Space Stop／重建後舊通知的真實 UI 路徑。隔離與資料格式目前有 fixture／既有 generation 測試支持，不能替代上述實機驗收。未因本次修正宣稱已解決 LINE 背景 ingress 相容性問題。

本次沒有雲端操作或新增持續計費資源；沒有啟動 dev server。驗收版本保留在裝置。
