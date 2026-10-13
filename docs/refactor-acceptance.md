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
| 固定契約 | 盤點操作與狀態；補缺少的 characterization 測試；建立本契約 | 基線與新增測試、build、Lint；狀態 UI 在實體裝置執行 | 本機／補充實機通過；正式裝置 gate 待確認 |
| 拆操作與組裝 | 細分操作介面與實際 Android adapter 實作；手動 composition root；共享 coordinator | 操作語意、復原與重複操作回歸；build、Lint、相關 unit／整合測試 | 未開始 |
| 拆畫面狀態 | Home／Picker／Details／Settings 責任與狀態持有者實際分離；保持 UI 與 navigation 契約 | SavedState、切換／晚到結果、操作進度及畫面狀態回歸；build、Lint、unit／UI | 未開始 |
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

正式 Android UI／runtime 驗收依專案要求在實體 ASUS 或 Pixel 7 完成。目前唯讀盤點只見 Pixel 10a API 37；使用其他裝置需要使用者明確決定，尚未取得時不能標示正式實機驗收通過。不得在此文件記錄 PIN、serial 或 credentials。

UI fixture 測試不等於真實資料操作驗收。執行 connected tests 前核對 runner 清理是否會刪除資料、現有安裝與可復原備份；涉及刪除、通知或外部副作用時依已確認授權界線操作。逐項記錄實際 executed／skipped，不用 build 成功替代 E2E。

## LINE 已知限制

背景訊息的原始 Then 是訊息實際進 cloned LINE 對話／DB 且出現 cloned notification。MCS CONNECTED、TCP socket、PID non-frozen 或 receiver 排程不是投遞成功。保留現有 metadata-only、exact package/user/generation、attestation、bounded lease 與 stop fences；不得新增 blind keepalive、私有 API polling、偽造訊息或 host 通知複製。架構重構不宣稱修復既有 microG／LINE upstream ingress 限制；若測試未跑該流程，明確列未驗證。

## 實際驗收紀錄

### 第一階段：本機與補充實機已驗證，正式 gate 待裝置確認

- 主代理 baseline build／Lint 成功；新增測試後重新執行 app unit tests：187 tests，0 failure/error/skipped。application-core 58、group-store 7 的既有結果由 Gradle 確認 UP-TO-DATE。所有結果的 task／count 與限制見 [stage1.json](refactor-evidence/stage1.json)。
- 新增 3 個 JVM、3 個 Compose UI 契約測試；修正既有重命名還原測試的過時入口。先跑失敗案例單獨通過，再完整重跑六組 UI suites：Pixel 10a/API 37 上 34 tests 全通過，0 skipped，runner `OK (34 tests)`。此為補充證據。
- app Lint 無新增 error/fatal；runtime baseline 有既有警告與停用 detector，不擴大 suppression。microG 真實 pinned artifact smoke 已提供兩個本機 artifact 路徑執行，0 skipped。
- 安裝使用資料保留方式，未清除／解除安裝 target app。初次 aggregate metadata 摘要不一致，不能宣稱初次整個 tree 未變；檔案時間盤點顯示 GMS profile 的寫入發生於安裝後、UI suite 開始前，其餘空間／membership 結構檔案修改時間早於本次安裝。此證據與既有啟動復原寫入相符，未取得原 profile 副本，因此不宣稱已完成初次逐欄位比對。後續兩次完整 UI suite 期間摘要均未變。
- 非作者審查完成；production、依賴與 AGENTS 未變更。Compose saved-state restoration 的證據不等於完整 Activity/process-death E2E。
- adb 目前只連接 Pixel 10a，尚無 ASUS／Pixel 7。正式 UI/runtime gate 仍等待核准實體裝置，或使用者本次明確允許 Pixel 10a；其餘四階段未開始，不能越過此 gate。

