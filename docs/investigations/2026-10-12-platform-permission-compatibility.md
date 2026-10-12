# 平台未定義權限的相容行為

## 使用者確認的規則

目前 Android 版本沒有定義的權限，AppTwin 不額外拒絕 cloned App，顯示「自動允許（此版本免設定）」且不提供開關。Android 支援的權限仍遵守 host 系統授權與 Space 設定。Android 12 的圖片／影片實際讀取仍受 `READ_EXTERNAL_STORAGE` 控制，不得利用自動允許新版媒體權限繞過有效的拒絕。

使用者已明確同意此規則並要求開始實作。開工 repo 為 `main`，工作目錄乾淨。ASUS 為 Android 12 / API 31，host AppTwin 的 `READ_EXTERNAL_STORAGE` 已授權。

## 行為與結構

```text
installed + requested permission
  -> known introduction API + lower device API + platform NameNotFound
       -> virtual automatic allow, no persisted toggle
       -> UI 免設定
  -> other permissions
       -> existing host / Space authorization

media query / open / shared native path
  -> effective permissions for actual platform version
  -> host grant intersect Space grant
  -> undefined modern permission cannot bypass legacy denial
```

任意未知、自訂／signature 權限、未安裝 package、未宣告權限與查詢異常不能套用 auto allow。平台存在的權限被拒絕也不能視為「不支援」。

## 驗收計畫

- 本機：API 31 媒體 exemption、API 33 仍可拒絕、未知／未宣告／未安裝不允許、平台 lookup failure 不允許；圖片、影片、混合媒體與 native shared path 的有效權限矩陣。
- 原問題：ASUS openinfo 的 `READ_MEDIA_IMAGES`／`READ_MEDIA_VIDEO` 顯示免設定且無無效開關。只查詢原分身，不能為此改動 LINE 授權或重啟 LINE。
- 真實媒體：獨立 fixture package 與臨時 Space，只建立並讀取本次測試圖片／影片；拒絕儲存權限時不能讀，允許後可讀，撤銷後再不能讀，另一 Space 不受影響。
- 清理：刪除測試 Space、媒體與 fixture package，恢復原背景設定；不清除 AppTwin 或既有分身資料、不讀私人媒體或聯絡人、不執行雲端操作。

使用者已明確批准本輪實機驗收與清理。臨時 fixture package 為 `org.apptwin.fixture.mediacompat`，不更動既有 fixture；merged manifest 僅三項媒體權限與單一 Activity，無 account service／provider／聯絡人等功能。清理 ownership 記於裝置 `cache/media-compat-owned.json`，建立與成功清理後更新；剩餘資源不可忽略。

## 結果

- 本機 runtime 745、app 183 個單元測試通過，runtimeProbe app／instrumentation 與獨立 mediaCompat fixture APK 編譯通過。
- 共用相容判斷採已知權限引入版本與平台 lookup 雙重證據；未知名稱、平台存在或 lookup error 不自動允許。
- SDK 31 Provider／native whitelist／AppOps 改用有效 legacy gate。SDK 33+ 保留既有合法 legacy fallback，本次不宣稱所有 modern deny 都阻止已由其他有效權限允許的存取。
- ASUS 三項 targeted UI 測試與實際 openinfo production UI 接線測試通過：兩項媒體權限自動允許、無開關、沒有「權限設定失敗」。
- 媒體分階段已觀察 `allowed-A`、`denied-B`、`revoked-A`、`allowed-B`：每階段兩項 modern 權限皆允許，而圖片／影片 query、URI open、direct path 共六種實際讀取跟隨 legacy grant；拒絕時回報 SecurityException／FileNotFoundException。
- 完整五階段曾因測試 Activity 返回／重啟與前景視窗觀測失敗而中止，不能將上述分階段證據當成完整 E2E 通過。每輪臨時 Space／媒體皆依 ownership 清理至零。
- 裝置曾睡眠並停在 secure keyguard；由使用者本人解鎖。測試補 awake／unlocked 前置檢查、單一 host Activity 與測試窗口 `KEEP_SCREEN_ON`，不改產品 token／foreground fence 或裝置全域休眠設定。
- 最小 restart smoke（A 首次 → B 首次 → A 重啟）三次正常 controller 啟動皆 `Started` 且確切 fixture 在前景，返回成功。未證實程式碼審查提出的 asynchronous process cleanup／orphan reconciliation 競態，未修改產品 lifecycle。
- 前景 root 暫時為 null 時，測試最多等候 5 秒取得確切 fixture 標題後才送 Back；不對其他 app 發送返回，也不放寬 launch 與媒體斷言。
- `revoked-A` 的失敗樣本已寫出正確媒體拒絕結果，但 Activity 未保持前景且 controller 未回報 `Started`；不將此樣本降級判定成功。測試移除 probe 額外 kill（產品權限 setter 已停止該 fixture），並 bounded 等待 exact package/user 從 running registry 移除，保留所有五階段／啟動／六種讀取斷言。registry 移除不是完整 task cleanup barrier，因此未宣稱已修復 lifecycle 競態。
- 更新 task lifecycle 後，完整五階段嚴格 E2E 已通過；下列失敗紀錄保留作為診斷歷程。

## 驗收期間發現的 task 生命週期阻點

觀察事實：`taskmap-failure-diagnostic.json` 的 revoked-A 已產生正確拒絕結果，fixture PID=1767、taskId=1565。22:20:39.652 舊 process PID=32689/generation=9 死亡；22:20:39.703 full orphan reconciliation 移除 task1565。新 fixture PID1767/generation11 直到 22:20:41.323 finally 的 user-stop 才清理。22:20:41.285 controller 回報 Activity 未恢復。此證據證明舊 process 清理誤刪新 fixture 的實體 task，不是媒體權限斷言失敗。可靠度分數：98%。

推論：ActivityStack 的 recent snapshot 缺項可能讓新 task 的 virtual history 過早消失，導致 full orphan scan 判為無 owner；這個內部步驟仍未直接觀測。可靠度分數：75%。驗證方式：在 exact physical AppTask 與 current-generation owner 仍存在時保留 history，重跑嚴格 Started 與全部媒體操作；若仍失敗，再定位 attach/resume/optimize metadata，不降低驗收。

使用者以「好」確認將 runtime task lifecycle 納入本輪；v1007 已 install-r 保留資料更新 ASUS。

## 候選與清理狀態

- runtime 候選：recent 缺項才追加一次 exact physical AppTask snapshot；需 exact host stub 與同 task/user 的 unmarked current-generation owner 同時成立。查詢失敗不視為存在。未改 launch acknowledgement、timeout、workload gate 或 orphan 清理規則。
- 候選本機：runtime 749、app 183，共 932 tests，零 failure/error；v1007 `0.1.7-task-lifecycle-candidate` app／instrumentation／mediaCompat fixture build 成功。ASUS 已更新 v1007，並完成下列實機驗收。
- 已完成原授權清理：fixture host package 已卸載，test-owned Space／media／cache 均清零；原兩個 Space IDs、LINE legacy grant 與 base/split APK hashes 保留，global background=1。沒有執行中 test／logger／雲端資源。
- 實際 openinfo 媒體列截圖：`evidence/2026-10-12-permission-compatibility/openinfo-read-media-images.png`、`openinfo-read-media-video.png`。兩列皆「自動允許（此版本免設定）」，無開關。

## 最終 ASUS 驗收

- `absentModernPermissionsNeverBypassLegacyMediaGate` 通過（43.296 秒）：allowed-A、denied-B、revoked-A、allowed-B、still-denied-A 五階段皆 controller `Started`，取得確切 fixture 前景並正常返回。每階段 IMAGE／VIDEO virtual grant 永遠 true，READ_EXTERNAL_STORAGE 跟隨該 Space 的設定；圖片／影片 query、URI open、direct path 共六種讀取全部符合允許／拒絕。A 的拒絕不被 B 的允許改變。
- `guestRestartSmoke` 通過（38.576 秒）：A 首次、B 首次、A 重啟皆 `Started` 且確切 fixture 在前景；沒有降低 resume 或 media 斷言。原 v1006 task1565 誤删樣本留存供比對。
- 非作者審查未發現阻擋；本機 runtime749+app183=932 tests，零 failure/error；APK 編譯成功。只驗收實際 ASUS/API31，不宣稱已完成 API33+ 實機矩陣。
- fixture host package、full/smoke owned Space／media／cache 均已清理；原 groups 1/2、LINE CAMERA／legacy grant=true、base/split hashes、global background=1 皆保留。所有測試／logger 已停止，沒有本輪建立的持續計費雲端資源。

結論：平台未定義的媒體權限自動允許及 Android12 有效儲存拒絕已通過原 Space UI 與真實媒體 E2E；新 task 的誤刪問題在此 ASUS 流程已不再重現。可靠度分數：97%。驗證方式：同裝置再跑上述五階段與 A→B→A，或在 Pixel7/其他 Android 版本擴充相同嚴格流程；若再見新 task 誤删，重新定位實際 history 遺失點，不以結果檔取代 resume。
