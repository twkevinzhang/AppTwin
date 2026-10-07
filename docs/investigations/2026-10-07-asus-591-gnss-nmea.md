# ASUS 591 GNSS NMEA 啟動相容性修復

日期：2026-10-07（Asia/Taipei）。環境：實體 ASUS_I002D、Android 12、openinfo virtual user 1，既有 591 分身 `com.addcn.android.house591`。

## 原始問題與原因

Given：在 openinfo 開啟既有 591。When：啟動定位並註冊 NMEA listener。Then：22:17:43、22:17:49、22:17:53 三次 main-thread `SecurityException: invalid package "com.addcn.android.house591" for uid 10296` 導致閃退；預期啟動與定位可完成。

UID 10296 屬於 `org.apptwin`。例外路徑為 `LocationManager.addNmeaListener` → `ILocationManager.registerGnssNmeaCallback` → 系統 CallerIdentity。原 runtime 只有舊 NMEA API hook，缺少 Android 12 新 API 的 package 身分轉換。

Android 12 AIDL 的參數為 listener、packageName、attributionTag、listenerId，register/unregister 均回傳 void。來源：[AOSP Android 12 ILocationManager.aidl](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/location/java/android/location/ILocationManager.aidl)。

## 修正與自動化

- 補 register/unregister GNSS NMEA hooks，透過既有 Inject 掃描註冊。
- 只轉換 packageName 參數，保留 listener、attributionTag、listenerId，包含與 guest package 同值的字串。
- 註冊要求 host fine location 權限；fake location 模式不呼叫真實服務。解除監聽在真實模式不受權限撤銷阻擋。
- 回傳依實際 return type 使用安全預設，覆蓋 void 與 boolean 契約。
- `:virtual-runtime:testDebugUnitTest`：714 tests，0 failures/errors；其中新增 7 個 NMEA 測試涵蓋實際 hook.call、身分、拒絕、fake、解除與 injector 可發現性。
- `:app:assembleRuntimeProbeRelease` 成功；`git diff --check` 通過。

## 保留資料更新與實機驗收

使用者核准更新安裝、定位與返回重開，三輪各至少 60 秒，不登入新帳號、不刊登、不聯絡、不刪除資料。使用既有 Keychain 簽章，候選與安裝版憑證 SHA-256 一致後才更新。未卸載、未擴大定位權限；背景定位提示選擇保留使用期間權限。

安裝時間 22:31:52；版本標籤保留 0.1.3 / code 1003。候選 APK SHA-256：`85e72ce85f2b210ca8d55e5b01fb2f316bde548d82520951b871aca665f9ab22`。

| 輪次 | 觀察區間 | 實際結果 |
| --- | --- | --- |
| 1 | 22:32:53–22:33:59（66 秒） | 完成定位切換提示、首頁與中古屋列表操作；PID 23136 持續存在，guest Activity 為 resumed。之後進入地圖，曾開啟系統權限設定，未改權限，返回地圖頁成功。 |
| 2 | 22:35:05–22:36:23（78 秒） | 返回 AppTwin openinfo 後重開，首頁可操作；每 20 秒取樣 PID 與 resumed Activity 均持續存在，進入縣市定位頁。 |
| 3 | 22:36:50–22:37:58（68 秒） | 再次從 openinfo 點開；定位頁顯示實際縣市／行政區結果，點選後返回首頁成功；每 20 秒取樣 PID 與 resumed Activity 持續存在。 |

22:31:52–22:38 的 logcat 未見原 invalid package / registerGnssNmeaCallback 例外，也未見 591 的 Java fatal、native fatal 或 process-death。crash buffer 有兩筆 crash_dump helper 訊息，其 PID 706 / 638 對應 keystore2 / vold，不是 591；不將裝置所有 crash buffer 訊息視為本次 app 崩潰。

## 結論與邊界

觀察事實：原始三次同一例外，修後同一 ASUS 的啟動、定位完成與兩次返回重開均通過至少 60 秒觀察。

推論：補上新版 NMEA API 身分轉換消除了本次可重現的啟動閃退。結論：本次 591 啟動／定位閃退已修復並通過約定實機流程。可靠度分數：98%。驗證方式：在相同 space 重跑原始啟動與定位流程；若出現新堆疊或程序死亡，重新定位首次失敗點。

第二、三輪是既有 guest PID 的返回重開，不是三次獨立 cold-process 啟動。未驗證 591 所有功能、長時間穩定性、其他裝置，或實機權限撤銷／fake location 組合；後兩者有 hook 層自動化涵蓋。本次無雲端操作，未建立持續計費資源。
