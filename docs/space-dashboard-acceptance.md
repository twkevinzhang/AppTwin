# 空間儀錶板與隔離權限驗收

## 產品契約

- 空間建立包含獨立 Google 相容環境，必要套件、資料環境與 binding 成功後才提交空間。初始化不要求登入或即時連網。失敗回滾環境並顯示「建立空間失敗」，清理失敗保留 journal。
- 卡片右側管理按鈕直接開啟獨立詳細頁。Google 不提供啟用／停用入口。
- 帳號只管理該空間 AccountManager 帳號，新增／移除交由 authenticator；同步限實際支援的 adapter。App 私有 session 不屬於中央帳號列表。
- 權限按類別列出相關 App。宿主能力與空間決策均允許才可存取。拒絕不能只改 checkPermission 的回傳值。
- 照片拒絕禁止廣泛讀取，但允許使用者明確授予的文件／選取照片 URI 與 App 私有副本。已交給 App 的資料不會因撤銷而消失。
- 撤銷停止該空間內受影響 App，關閉 listeners 與已開啟 handles；不停止其他空間。
- 使用量計入 App 資料、快取、空間共用檔案及 Google 資料。共用 APK 與外部備份不重複計入。

## 備份與從備份建立

- schema v2，不相容舊版備份。產品領域操作為「從備份建立」，不是覆寫原空間。
- Global 管理唯一預設備份目錄，空間不得覆寫。Global 變更只影響新備份，舊備份保留原 URI。
- 清單列出大小、時間、可存取狀態；永久刪除檔案成功才移除清單紀錄。
- 一般空間可在來源仍存在時從備份建立，取得新的 space UUID 與 environment。
- LINE 的 Custodian 金鑰身分必須維持唯一 owner；來源仍持有身分時不能同時建立第二份。不得偷偷刪除來源或繞過 ownership 驗證。
- 檔案不加密，系統文件存取權失效時需如實顯示，不能把 registry 當成檔案存在證明。

## 實機驗收邊界

主代理是 ASUS 唯一操作 owner。使用指定 ADB `/Users/zhangzhenlong/Library/Android/sdk/platform-tools/adb`。
使用專用 A/B 空間、合成聯絡人、1px 測試圖片與 fixture；只刪本次資料，保存並恢復宿主權限。
ASUS 為 Android 12 / API 31；Android 13+ 的 READ_MEDIA_* 與選取照片 API 需要另以實體 Pixel 7 覆蓋，不能由 ASUS 結果推定。

Given：宿主具備必要能力、同一探針 App 分別位於 A/B。
When：A 拒絕、B 允許，嘗試相機、錄音、定位、聯絡人讀寫、照片 query/open/direct path，然後撤銷、重啟及重複操作。
Then：A 無法取得新的受保護資料；B 正常；撤銷後不再取得新資料，其他空間不受影響。
定位訂閱註冊成功不等於已取得座標，兩種證據必須分開記錄。

建立與備份另驗證：Google 初始化成功前無可用空間、失敗回滾、清理失敗 journal、從備份建立新 identity、來源保持存在、永久刪檔及位置變更。

## 執行紀錄

驗收裝置：ASUS_I002D，Android 12 / API 31；2026-10-08。測試使用合成資料，完成後恢復宿主原始權限。

已通過的實機流程：

- ASUS 空間建立與 Google 必要套件／資料初始化、一般備份建立新 identity、來源保持存在。建立故障注入、回滾與 journal 復原另由本機 coordinator／store 測試驗證，未在實機破壞 Google assets。
- Compose UI 流程，包含卡片詳細頁、權限與 Global 設定；22 項 UI 個案已於 ASUS 通過。
- A/B 權限隔離：相機實際開啟、AudioRecord 實際收音、聯絡人讀寫、照片 query/open/direct path、定位註冊；撤銷後重啟再次驗證。
- AccountManager authenticator 新增／批准移除、真實 SyncAdapter provider 同步與完成回呼；備份只攜帶來源空間帳號資料並映射新 user，A/B 帳號維持隔離。完整流程於 ASUS 重跑通過，42.985 秒。
- 真實系統圖片選擇器取得明確 URI 授權；廣泛照片權限拒絕時仍可讀取選定圖片，重新啟動後持久授權仍有效。
- 真實 SAF 目錄切換：新備份寫入新的 Global 目錄，舊備份保留原 URI 並可讀；永久刪除後確認實際檔案與 registry 均消失。

觀察事實：ASUS 上的真實 ContactsProvider 使用分號分隔 authority，已修正別名判斷並重跑實測；native 相機／收音 Binder gate 安裝成功，允許與拒絕均以實際操作驗證。

推論與限制：上述結果支持 API 31 上已測試路徑的隔離，不能推定所有第三方 App 或 Android 版本皆涵蓋。Android 13+ READ_MEDIA_* 與選取照片 API 尚未以 Pixel 7 驗證；定位註冊成功不等於 GPS 座標到達。Google fused location 的 coarse-only 路徑採 fail-closed，目前需要 fine 權限；UI 已揭露。LINE 的 v2 Custodian 登入／背景通知不屬於本輪通過證據。

驗證方式：以相同合成 fixture 在實體 Pixel 7 重跑照片與定位路徑，再用目標第三方 App 驗證 authenticator、sync adapter 與資料存取。


## 重跑命令

本機採 JDK 17、Gradle offline，驗證目前 runtimeProbe variant，避免合計不同 variant 的舊報告：

```sh
./gradlew :application-core:test :gms-runtime-adapter:testDebugUnitTest :gms-compat-core:test :app:testRuntimeProbeDebugUnitTest :virtual-runtime:testDebugUnitTest :app:assembleRuntimeProbeDebug :app:assembleRuntimeProbeDebugAndroidTest --offline -PAPP_VERSION_CODE=1004 -PAPP_VERSION_NAME=0.1.4-space-dashboard
```

ASUS instrumentation 使用 `org.apptwin.test/androidx.test.runner.AndroidJUnitRunner`。權限與選取 URI 測試以明確 class 選擇執行；帳號與 Global 備份測試另有 opt-in 參數。以上均使用受控 fixture；需要安裝 `runtime-update-fixture` 的 revisionOne debug APK。選取 URI 測試必須使用真實系統 picker，不可用偽造 grant 代替。Global 測試必須預先由系統 picker 授權兩個專用 SAF 目錄。

```text
明確 class → SpacePermissionEnforcementE2eTest
spaceAccountE2e=1 → SpaceAccountManagementE2eTest
明確 class → SelectedUriPermissionE2eTest
spaceGlobalBackupE2e=1 → SpaceGlobalBackupLocationE2eTest
```

驗收前確認裝置 Awake、keyguard showing=false；長流程暫時依實際插電類型維持亮屏，終止後恢復原 stay_on_while_plugged_in 設定，不保存 PIN。未解鎖導致 foreground readiness 失敗的執行不列為通過。

測試終止後，依測試保存的 prior-host-grants 紀錄由外部 ADB 恢復宿主權限；不可在 instrumentation 活著時撤銷同 UID 權限，否則系統會終止測試程序。測試失敗時仍嘗試清除每筆 owned fixture／空間／備份，保留原始錯誤與清理錯誤。


## 同步介面相容性依據

同步使用 exact-user 虛擬服務 binding，不呼叫宿主 ContentResolver 排程。AOSP 的 ISyncAdapter 在 Android 9 起於 startSync 前加入 onUnsyncableAccount，因此 API 28+ 使用 start=2／cancel=3，API 26／27 使用 start=1／cancel=2；每次只送一次啟動，不以另一個 transaction 重試。成功必須收到真實 ISyncContext.onFinished，fixture 另確認實際資料寫入。

來源：[Android 8.1 AIDL](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/core/java/android/content/ISyncAdapter.aidl)、[Android 9 AIDL](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/content/ISyncAdapter.aidl)、[Android 12 AIDL](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/content/ISyncAdapter.aidl)。


ASUS 的安全修補會拒絕非 system caller 直接呼叫 framework SyncAdapter；對照測試未進入 onPerformSync，events buffer 同期出現 SNET `203229608`。因此手動同步經受限的虛擬 SyncManager 轉交：僅宿主可要求指定 user／已註冊 component／authority／既存 Account；伺服器持有 120 秒 lease，綁定目前 service／process generation、前景授權及完成回呼。guest-local bridge 僅接受建立它的虛擬伺服器 PID，使用可驗證 PID 的內部 Binder 呼叫。局部 framework dispatch 的 identity 在 finally 恢復，對 Android 系統的外部 Binder 呼叫仍使用 AppTwin 實際 UID。

來源：[framework SyncAdapter caller guard](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/AbstractThreadedSyncAdapter.java)、[Binder calling PID 與單向呼叫限制](https://developer.android.com/reference/android/os/Binder#getCallingPid())。橋接能力的通過條件仍為真實同步完成與 fixture provider 資料寫入，不能以 Binder transact 成功代替。


## 本輪本機驗證

目前 variant 合計 1,003 個 unit tests，失敗／錯誤／略過均為 0：application-core 58、gms-compat-core 26、gms-runtime-adapter 26、app runtimeProbeDebug 158、virtual-runtime Debug 735。APK 與 instrumentation APK 建置成功。新增同步橋接、一次性移除批准、terminal service callback 與刪除／晚到 receiver 邊界均包含回歸測試。

同步橋接另使用專用 `getScopedSyncManagerPid`，避免改變舊 `getSystemPid` 的既有 native identity 行為。ASUS 觀察到 exact component 宣告及 manager PID 比對成立、`onPerformSync` 實際 provider 寫入、真實 `onFinished` 成功；移除則先經 fixture authenticator 批准。


觀察事實：以上 unit tests、APK 建置與已列實機流程具備直接結果。推論：實作符合本輪明定的 API 31 路徑與自有 runtime 隔離契約。結論：ASUS 已測路徑可交付；不能延伸成所有 Android／第三方 App 相容性保證。可靠度分數：95%。驗證方式：實體 Pixel 7 覆蓋 Android 13+ 媒體權限及目標 App 相容性；若出現繞過或隔離反例，下修此分數並補回歸。


## 最終整合結果

ASUS 解鎖且亮屏前置條件成立後，完整 27 項 instrumentation 於 101.226 秒通過，0 失敗／0 略過。其中 UI 22 項，另包含服務連線 wire、同步 bridge identity 例外恢復、帳號完整流程、Google／備份與真實權限隔離。先前兩項系統 picker／Global SAF 流程另於 204.176 秒通過；為分批驗收，並非單次 29 項 suite。

測試後僅保留既有空白 review 空間 A；本輪測試空間、合成 provider 資料、備份檔與 registry 清理完成，兩個專用 SAF 目錄為空。宿主權限及 stay-awake 設定已恢復。ASUS 安裝目前 debug APK 供檢視；instrumentation APK 與宿主測試 fixture 已解除安裝。未建立雲端資源或持續計費項目。

證據：[最終 27 項](evidence/space-dashboard-asus/final-instrumentation.txt)、[帳號同步 checkpoint](evidence/space-dashboard-asus/account-sync-checkpoints.txt)、[系統 picker／Global SAF](evidence/space-dashboard-asus/selected-uri-and-global-backups.txt)、[儀錶板](evidence/space-dashboard-asus/dashboard.png)。


## 帳號類型中文名稱修正

2026-10-08：新增帳號選單與既有帳號列將 `com.google` 顯示為「Google 帳號」，`com.google.work` 顯示為「企業管理帳號」。內部 type、操作 callback 與 test tag 保持原值。APK 建置及 158 項 App unit tests 通過；ASUS 實際開啟選單確認兩個中文名稱、原始代碼不再顯示，既有 Google 帳號列亦確認顯示中文。本次僅檢查顯示，未新增、移除或同步使用者帳號。
