# 漸進式架構重構與驗收契約

## 範圍與基線

基線 commit：`5f14b47`。沿用 Kotlin／Compose、Java／AIDL／JNI 與現有模組、資料格式和依賴；AGP 8.13.2、Kotlin 2.1.0、Compose BOM 2025.03.00、Lifecycle 2.8.7。這份文件記錄驗收要求，不代表測試已通過。主代理逐階段核對實際產物和測試結果，前一階段通過後才能進入下一階段。

採用 Google Android 分層、單向資料流、畫面狀態持有者與建構子注入建議；不得藉重構升級依賴或替換 runtime。API／函式庫用法須核對對應版本的官方文件。參考：https://developer.android.com/topic/architecture/recommendations 與 https://developer.android.com/topic/architecture/ui-layer/state-production 。

## 不變契約

- 空間、package、virtual user、revision、process generation 必須保持 exact scope，不能以另一空間的 clone 代替失敗目標。
- File stores、交易 journal、復原 gate、資料完整性警告與原始損壞資料保留語意不變；不得將讀取失敗當作空資料。
- 拆分操作後仍共享同一協調與序列化邊界；不得由各畫面建立互不相干的 mutex、store 或 recovery 狀態。
- 晚到的讀取／觀察結果須受 generation、目前空間與目的地 fence 保護；取消必須保持取消語意。
- 重複新增／啟動、清除、重試必須維持現有抑制行為。Explicit Stop、死亡／resume fence、hard timeout、退避與 fail-closed 判斷不得弱化。
- Host UID 相同不代表 guest caller 可信；維持 caller、virtual scope、signature permission 與 attestation 邊界。

## 分階段 exit gates

| 階段 | 產物與完成條件 | 驗收證據要求 | 狀態 |
| --- | --- | --- | --- |
| 固定契約 | 盤點操作與狀態；補缺少的 characterization 測試；建立本契約 | 基線與新增測試、build、Lint；狀態 UI 在實體裝置執行 | 通過：本機與正式 Pixel 10a 驗收 |
| 拆操作與組裝 | 細分操作介面與實際 Android adapter 實作；手動 composition root；共享 coordinator | 操作語意、復原與重複操作回歸；build、Lint、相關 unit／整合測試 | 通過：本機與正式 Pixel 10a 驗收 |
| 拆畫面狀態 | Home／Picker／Details／Settings 責任與狀態持有者實際分離；保持 UI 與 navigation 契約 | SavedState、切換／晚到結果、操作進度及畫面狀態回歸；build、Lint、unit／UI | 正式驗收通過 |
| 整理 Lifecycle／權限／背景觀察 | 平台邊界提供觀察與權限能力，UI 停止時釋放 observer；操作前重新查權限 | Lifecycle 重建／停止／返回設定、權限撤銷、背景 retry／stop fences；build、Lint、unit／實機 | 未開始 |
| 小範圍整理 runtime | 明確限定一條現有 runtime 路徑，抽取可測 policy／helper | exact scope、非法 caller／Intent、失敗／取消／重複事件與版本差異；build、Lint、unit／實機整合 | 未開始 |

不得用介面重新命名或純資料分組取代實際責任拆分。每階段留下實際 command、結果與未驗證項目；禁止預先填入 PASS。

## 狀態與版本矩陣

- Loading：首次載入與重新整理分別驗證；已有空間時維持內容，不閃成 Empty；未完成初始復原不能解鎖來源操作。
- Success：正確空間、App、revision 與操作結果；完成後解除 busy。
- Empty：合法零空間／零帳號／零備份，與損壞／不存在空間分開；不存在的詳情返回 Home。
- Error：保留必要資料與資料完整性警告，重試可恢復，舊失敗不得覆蓋新成功。
- Offline：本地資料仍可使用；背景／MCS 失敗不能被當作本地清單失敗或訊息已送達。
- Lifecycle、Permission、Background Work、Bluetooth 變更同時核對 OS API、host targetSdk 與 guest targetSdk。現有 runtimeProbe target 28；不得宣稱其結果證明 target 36 特有行為。API 26～30、31～32、33、34、35～36、37 分別核對背景執行、FGS、通知／媒體／Nearby 權限、receiver／PendingIntent、quota 與相關反射差異。未觸及功能不捏造測試覆蓋。

## 執行與裝置邊界

### Given／When／Then 與測試對照

| 情境 | Given | When | Then | 行為證據 |
| --- | --- | --- | --- | --- |
| Loading | 初始本地快照尚未完成 | 渲染首頁／等待詳情讀取 | 首頁不提前顯示建立第一個空間；詳情維持 loading | `HomeScreenStateContractTest.initialLoadingDoesNotOfferEmptySpaceCreation`；`MainViewModelLifecycleTest.details loading completes as successful empty accounts without an error` |
| Success | 已取得空間，詳細 enrichment 尚未完成 | 重新整理 | 現有卡片仍顯示，晚到結果受 generation fence 保護 | `HomeScreenStateContractTest.refreshingSnapshotKeepsExistingSpaceAndIntegrityWarningVisible`；既有 `MainViewModelLifecycleTest` enrichment／generation tests |
| Empty | 可讀的快照確實為零空間／零帳號 | 完成讀取 | 首頁可建立空間；零帳號不產生錯誤或離開詳情 | `HomeScreenStateContractTest.successfulEmptySnapshotOffersSpaceCreation`；新增 details loading test |
| Error | exact space 存在，但詳情資料來源失敗 | 讀取後重試 | 保留 selected space 與 Error，重試成功後更新；舊失敗不能覆蓋新資料 | `MainViewModelLifecycleTest.details read error can retry without treating a failure as a missing space`；既有 same-space stale failure test |
| Offline／背景失敗 | 本地詳情成功，背景資料讀取失敗／TCP 中斷 | 觀察背景健康或點重試 | 本地詳情保留；背景不顯示健康；忙碌時只接受一次重試 | `MainViewModelLifecycleTest.background connection failure preserves successful local space details`；`SpaceBackgroundCardUiTest.disconnectedTcpOffersExactRetryAndBusyPreventsDuplicates` |

以上是規格與案例對照，實際執行結果另記。基線首頁在零卡片且有完整性警告時仍提供建立空間；現有 `HomeScreenUninstallTest.metadataWarningRemainsVisibleWhenNoValidGroupCanBeLoaded` 只證明警告存在，不能證明 Error 與 Empty 已完整分離。此為第三階段狀態整理必須解決／驗收的缺口；第一階段僅固定此現況，不改產品行為。背景資料讀取失敗也不單獨證明實際網路斷線，網路與 MCS 的原始流程需分別驗收。

每批執行 `:app:assembleRuntimeProbeDebug`、`:app:assembleRuntimeProbeDebugAndroidTest`、`:app:lintRuntimeProbeDebug`、`:app:testRuntimeProbeDebugUnitTest` 與受影響純 JVM 模組測試。涉及 runtime／GMS 時補該模組 build、Lint、unit／JNI 與實機整合。Root 主代理負責所有 Gradle 與 device 操作，避免平行污染驗收。

正式 Android UI／runtime 驗收裝置為實體 ASUS、Pixel 7 或 Pixel 10。使用者於 2026-10-14 明確同意本次五階段重構使用實體 Pixel 10a；此為本次授權。本機 AGENTS.md 已更新，保留原 .git/info/exclude 的管理方式。不得在此文件記錄 PIN、serial 或 credentials。

UI fixture 測試不等於真實資料操作驗收。執行 connected tests 前核對 runner 清理是否會刪除資料、現有安裝與可復原備份；涉及刪除、通知或外部副作用時依已確認授權界線操作。逐項記錄實際 executed／skipped，不用 build 成功替代 E2E。

## LINE 已知限制

背景訊息的原始 Then 是訊息實際進 cloned LINE 對話／DB 且出現 cloned notification。MCS CONNECTED、TCP socket、PID non-frozen 或 receiver 排程不是投遞成功。保留現有 metadata-only、exact package/user/generation、attestation、bounded lease 與 stop fences；不得新增 blind keepalive、私有 API polling、偽造訊息或 host 通知複製。架構重構不宣稱修復既有 microG／LINE upstream ingress 限制；若測試未跑該流程，明確列未驗證。

## 實際驗收紀錄

### 第一階段：本機與正式 Pixel 10a 驗收通過

- 主代理 baseline build／Lint 成功；新增測試後重新執行 app unit tests：187 tests，0 failure/error/skipped。application-core 58、group-store 7 的既有結果由 Gradle 確認 UP-TO-DATE。所有結果的 task／count 與限制見 [stage1.json](refactor-evidence/stage1.json)。
- 新增 3 個 JVM、3 個 Compose UI 契約測試；修正既有重命名還原測試的過時入口。先跑失敗案例單獨通過，再完整重跑六組 UI suites：Pixel 10a/API 37 上 34 tests 全通過，0 skipped，runner `OK (34 tests)`。此為補充證據。
- app Lint 無新增 error/fatal；runtime baseline 有既有警告與停用 detector，不擴大 suppression。microG 真實 pinned artifact smoke 已提供兩個本機 artifact 路徑執行，0 skipped。
- 安裝使用資料保留方式，未清除／解除安裝 target app。初次 aggregate metadata 摘要不一致，不能宣稱初次整個 tree 未變；檔案時間盤點顯示 GMS profile 的寫入發生於安裝後、UI suite 開始前，其餘空間／membership 結構檔案修改時間早於本次安裝。此證據與既有啟動復原寫入相符，未取得原 profile 副本，因此不宣稱已完成初次逐欄位比對。後續兩次完整 UI suite 期間摘要均未變。
- 非作者審查完成；production、依賴與 AGENTS 未變更。Compose saved-state restoration 的證據不等於完整 Activity/process-death E2E。
- 使用者已明確允許本次以 Pixel 10a 正式驗收。授權後重新執行正式六組 UI suites：34/34 通過，0 failure/skipped；build、Lint、app unit gate 再次成功。第一階段 gate 通過，後續四階段仍須逐一實作與驗收。


### 第二階段：操作與組裝拆分，正式驗收通過

- 實際 48 個操作/helper 分入八個 focused Android adapter；Application 持有單一 composition graph、stores、journals、runtime 與 GMS 狀態。MainViewModel 改為注入依賴的 plain ViewModel，production factory 以 pinned Lifecycle 2.8.7 的 CreationExtras 建立 SavedStateHandle。保留既有 dispatcher、mutex、generation fence 與依賴版本。
- 非作者審查確認操作語意、共享 graph／factory 身分及嚴格驗收斷言。完整 Gradle build、app 190 個與 runtime 791 個 unit tests 通過，零 failure/error/skip；Lint 保留既有 baseline，app 30 warnings、runtime 133 warnings/2 hints、fixture 17 warnings，無未過濾 error/fatal。
- 正式 Pixel 10a/API 37/host target 28 共 46 個測試執行全部通過：五輪 add/first-launch＋兩次 warm reuse（同 instance/PID/task/count=1）；雙空間跨 host force-stop 的兩段隔離；八組實際 camera/audio grant/restart/revoke probes（1 個 test）；factory 2、備份/帳號 2、UI 契約 34。沒有以 body 通過取代 teardown，也沒有 skip 或人工 resumed 設定。已核對安裝 APK SHA-256，owned state/空間已清理，既有 public group/membership/environment 結構由測試確認保留。
- 驗收揭露並修正三類 prerequisite：現代 `checkPermissionForDevice` 的 exact guest policy 路由；API 29+ canonical taskId 與 running/nonempty retention，orphan 清理保留雙 owner 查詢；reused ACK 必須確認實際 exact guest task。沒有放寬 permission、owner、generation、stub、workload gate、timeout 或新增依賴。
- warm reuse 歷史失敗的直接原因證據為 engine 主動對原 fixture PID 發送 SIG9，kill callsite 明確為 `attach-running-stub-mismatch`。server 已裝 AM hook，guest-facing GetRunningAppProcesses 會投影已註冊 PID 的 name/UID；VAMS 卻用此投影驗證 host stub。三個權威 consumer 已改用 pre-hook raw AM，回傳 detached identity 並保留原 PID/hostUID/exactslot/generation 條件。修正後上述五輪及完整正式 gate 通過；暫時 runtime task-drop/kill 診斷已移除。所有歷史失敗與診斷樣本保留於 [stage2.json](refactor-evidence/stage2.json)，不計為正式通過。
- failure-only 測試 metadata 僅保存 synthetic fixture lifecycle、allowlisted task/window 與固定身分欄位；不記訊息、帳號、token、Intent extras、UI text、聲音或影像。camera/audio probe 為 opt-in，麥克風讀取有界且即時丟棄，不更動 host sensor grants。
- 僅 sideload-only fixture 停用 ExpiredTargetSdkVersion，以保留既有 target 28 相容性契約；其他 Lint detector 與 app/runtime baseline 不變，這不代表 Play 上架合規。SavedState fresh-owner 測試不等於真實 UI process-death；舊 Android 分支有 unit/官方文件核對，實機證據限 API 37。沒有宣稱 LINE ingress/通知問題已修復。
- 官方核對：[PermissionManager](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/permission/PermissionManager.java)、[IActivityManager](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/IActivityManager.aidl)、[RecentTaskInfo](https://developer.android.com/reference/android/app/ActivityManager.RecentTaskInfo)、[TaskInfo](https://developer.android.com/reference/android/app/TaskInfo)、[AppTask.moveToFront](https://developer.android.com/reference/android/app/ActivityManager.AppTask#moveToFront()).

可靠度分數：96%。驗證方式：比對正式 APK hashes、JUnit terminal status/零 skip、實機 lifecycle/有效能力及 exact public baseline assertions，並在後續階段重新執行原始路徑；新失敗需回到對應 gate，不能用歷史成功替代。

### 第三階段：畫面狀態拆分，正式驗收通過

- MainViewModel 保留 navigation、SavedState、onboarding、訊息及 deep-link shell；四個實際 state owners 接管各畫面 workflow。唯一 session coordinator 接管 catalog、busy、launch mutex、普通／deep-link／add-first-launch 及 recovery sequencing。各畫面使用 readonly StateFlow，legacy MainUiState 只有同步唯讀投影。
- Production AppTwinApp 消費六個 typed flows；Home 零有效資料的讀取／完整性錯誤顯示 Error/重試，Loading 與 Error 不呈現 Empty 建立 CTA，refresh/error 保留既有卡片。保留原 SavedState keys、dialogs、navigation 與既有 34 個 UI characterization cases，新增 3 個狀態案例。
- 非作者審查要求的取消傳遞、owner scope 發布帳號結果、單一 Settings 備份位置 ownership 與 navigation/session atomic updates 均已修正。Gradle build、Lint 與 app 212 個 unit tests 通過，零 failure/error/skipped；app Lint 30 warnings、無新增 error/fatal，依賴及 baseline 不變。
- 正式 Pixel 10a 共 50 個測試執行通過：37 UI 狀態契約、factory 2、帳號/備份 2、雙空間跨 force-stop 2、sensor 1（8 個有效能力 probes）、五輪 first/warm reuse、production ViewModel workflow 1。最後一例透過真實 factory/state owners 建立空間、選 fixture、first/warm launch、詳情與刪除，核對真正渲染的 own label、同 instance/PID/task/count=1、journal terminal、private directory cleanup 與既有 public baseline。初次 framework accessibility query 失敗另保留紀錄，修正明確 Home 前置條件與真實 Compose semantics 後完整通過，未移除顯示斷言。
- 安裝 APK hashes 已核對；前 49 個執行與新增 workflow 的 test APK hash 分開記錄於 [stage3.json](refactor-evidence/stage3.json)。runtime 未修改，791 個結果沿用第二階段，不宣稱第三階段重新執行。沒有清除或解除安裝 target app。
- 官方依據：[Architecture recommendations](https://developer.android.com/topic/architecture/recommendations)、[State holders and UI state](https://developer.android.com/topic/architecture/ui-layer/stateholders)。沒有更改依賴、SDK、runtime/workload gate 或 LINE channel 策略；lifecycle-aware collection 與平台觀察邊界留給第四階段。

可靠度分數：96%。驗證方式：核對 stage3.json 的正式 runner terminal、零 skip、JUnit 結果、實機 fixture lifecycle／能力與 APK hashes；後續任何回歸須重新驗收對應流程。

### 第四階段：Lifecycle／權限／背景觀察，正式驗收通過

- UI 六個畫面 flows 與背景 policy flow 使用既有 Lifecycle API 控制 STARTED 訂閱；Details health observer 只在 RESUMED 執行，Stop 只取消 UI 觀察。MainActivity resume 統一重新讀取權限、畫面與備份設定；通知權限回程重新核對系統狀態。
- Application 持有唯一可注入背景 repository、scope 與 mutation mutex；Tile 與 UI 共用。保留 durable readback／原意圖 retry，explicit Stop 使排隊 enable 失效；觀察者取消不取消 application 操作。
- 權限請求保留原 group/package/permission/launchKey/binding/member timestamp 與 ticket；取消 generation 拒絕晚到 lookup，舊 callback 不消耗新 request。Adapter 在操作入口與準備後重新核對 host grant、原 binding 及穩定 membership。正常 ADDED→ENABLED 的 state 不是 membership 更換；刪除重加仍拒絕。
- Gradle build、Lint 與 app 232 個 unit tests 通過，零 failure/error/skipped；Lint 30 warnings、零 error/fatal、未增加 baseline 或依賴。正式 Pixel 10a 共 56 個測試執行通過：完整回歸 47、跨 host force-stop 隔離 2、五輪 first/warm reuse、host revoke 1（兩個實際 camera denial probes）、實際 background policy 1；sensor 套件含八個 grant/restart/revoke 能力 probes。
- 真實 Settings Back 返回與同 Activity 畫面讀回通過；production permission-result 入口的原 scope／cancel／舊 ticket 通過，但不是 OS permission dialog 全自動操作。Lifecycle helper 使用真實 Activity 與 Compose test clock 證明訂閱 1→0→1、Stop 後無 polling，不能冒稱 MCS wall-time 健康證據。
- 實際 Off／retry rejection／queued Enable→Stop／prior policy restore 通過。Cold preflight 使用 pre-hook raw AM、實體 UID/PID 與全部 virtual users；不能把非原子 snapshot 說成原子保證。測試的 legacy getSystemPid 實際為 UID，已改用既有 actual PID API 並交叉核對，未改 production API。
- 安裝 hashes、owned cleanup、host CAMERA grant/flags 與原背景設定復原均已核對；沒有清除或解除安裝 target app。所有歷史失敗與限制見 [stage4.json](refactor-evidence/stage4.json)，不計為正式通過。
- 官方核對：[Lifecycle coroutines](https://developer.android.com/topic/libraries/architecture/coroutines)、[Runtime permissions](https://developer.android.com/training/permissions/requesting)、[Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[Compose synchronization](https://developer.android.com/develop/ui/compose/testing/synchronization)。SDK 與既有平台豁免／background location 分支保留；未新增 Bluetooth 功能、未宣稱跨版本實機或 LINE 通知修復。

可靠度分數：96%。驗證方式：比對 stage4.json 正式 runner terminal、APK hashes、JUnit/Lint 結果、實際能力與設定復原；後續 runtime 變更須重新驗收相關路徑。
