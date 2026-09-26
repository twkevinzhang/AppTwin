# 背景服務開關與前景使用模式

## 使用者確認的行為

- AppTwin 設定頁卡片與 Android 快速設定方塊共用同一份持久開關。
- 預設保持既有開啟行為；關閉後重開 AppTwin、程序恢復與開機恢復均不得自行打開。
- 關閉仍可手動啟動分身並同步資料，但不新增分身系統通知；離開前景即停止分身背景運作。
- 重新開啟恢復既有 GMS 空間設定；可能同步關閉期間的積壓訊息，不清資料或重新登入。

## 實作

跨程序原子檔保存許可；設定 UI 與 Tile 依實際讀回值顯示，切換有 busy/error，不以點擊當成功。

```text
背景許可 ON → 既有 Daemon / microG / cold push 路徑
背景許可 OFF → 封鎖自動啟動、job、push recovery、分身新通知
  手動開分身 → exact package/user 的 15 秒 launch lease
  Activity started → 可見工作階段接管
  最後 stopped → 400 ms 內部換頁轉場窗 → 停止失去前景範圍的程序
  熄屏 → 立即撤銷前景工作階段並停止
```

前景 Activity 另外綁定 non-exported 的純 bound engine service，讓 Android 能看到 Activity → engine 的重要度依賴；未啟動服務、不建立 FGS 或通知。onStart 先綁定再執行 engine RPC，onStop／onDestroy 400 ms 解除；exact host component 在虛擬解析前走系統 bind，避免先碰觸 frozen engine。

launch lease 與 Activity 轉場窗分開，避免啟動 ACK 清理誤撤銷轉場。程序世代、Activity token、實際 sender PID 均核對；其他分身不能共用前景許可。背景關閉不會刪除各空間的持久 GMS desired state。

## 驗收

實體 Pixel 7；同一支手機的 host LINE 傳給已授權分身帳號。先驗證 UI 與 runtime，再比較指定測試標記 DB 筆數與系統通知，不保存聊天資料庫、token 或解鎖資訊。

第一輪實機結果（1004 候選版）：

| 路徑 | 直接觀察 |
|---|---|
| 設定卡關閉 → 快速設定方塊 | 兩端顯示關閉，Daemon/FGS 與分身程序停止 |
| OFF 背景傳入 off1 標記 | 約 53 秒觀察內 LINE DB 0 筆、無該通知、microG count 未增加 |
| OFF 重開 AppTwin | 未自動開啟服務 |
| OFF 手動開 LINE | 同一 off1 標記同步入庫 1 筆，但不顯示分身通知 |
| OFF 分身內換頁 → Home | 內部換頁保留同 PID；Home 後停止 |
| 快速設定開啟 → 傳入 on1 標記 | 入庫 1 筆，系統分身通知包含標記、importance 4 |
| 前景 LINE 中 ON → OFF | 關閉 FGS，保留同一 LINE PID；熄屏後停止 |
| OFF 重開手機 | 關閉狀態保留，無 AppTwin 背景程序 |

證據位於 [evidence/2026-09-26-background-switch](evidence/2026-09-26-background-switch/)。`off1-after60.json` 是採樣階段名稱；實際觀察約 53 秒，不代表已等待完整 60 秒。

重開手機後另發現：既有分身 APK 冷掃描使 BinderProvider 超過系統發布期限，engine 被以 `INITIALIZATION FAILURE / timeout publishing content providers` 終止；暖快取重試成功不能取代冷啟動驗收。修補讓 Provider 先發布，再於主迴圈最前端依原順序初始化；所有遠端 service fetcher 與 `ensure_created` 等待最多 30 秒的完整 ready，未完成不回傳半初始化服務，APK 簽章驗證保持原樣。第二輪冷開機掃描實測 28,255 ms 完成，沒有 provider timeout，關閉保存且手動開啟成功。

但下一段 service startup 在當時重開機高負載下觸發 ANR（`DaemonService` waited 20,158 ms）。直接 ANR stack 證明主執行緒在 `onStartCommand → reconcileTrustedGmsCloudMessagingForUsers → startCloudMessaging → acquireProvider` 等待分身冷啟動；因此不能以 FGS 已顯示當最終成功。修補保留主執行緒的 FGS promotion 與 gate handshake，將 reconciliation 改為單一 worker，最多一個 active 加一個最新 pending；完成時核對 destroy／OFF／更新的請求狀態，並以伺服器 gate 驗證 token。極窄的檢查與發布競態可能讓快速重開後的 fast-path token 過期而需要重新握手；不會繞過 OFF 通知限制。GMS timeout、retry、initial reconciliation 改專用 HandlerThread，避免下一輪重連又阻塞主執行緒。一次與另一任務共用實機的重測遭畫面切換干擾；OFF engine 被切入 cached 狀態，最後出現 `FREEZER / Sync transaction while frozen`。這輪不列作最終驗收。另一任務完成後獨占裝置重測，冷啟動與 ON 通知通過；但 OFF 前景停留仍再次發生 engine FREEZER，證明不能將該問題歸因於共用裝置，另行修補前景期間的引擎生命週期。

並發讀取持久開關採純讀檔，避免 `AtomicFile.openRead()` 清理另一程序正在寫入的 `.new`；失敗重試保存原要求目標，避免把讀回舊狀態誤當重試目標。

## 參考

Android 官方 [Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles)：使用受 BIND_QUICK_SETTINGS_TILE 保護的 TileService，active tile 更新與 App 入口同步，鎖定時使用 unlockAndRun。

## 最終候選實機紀錄

- 10:19 冷開機仍為 OFF，沒有背景服務或分身程序。Provider 先發布再掃描。
- 10:20 開啟後重建 GMS；worker 耗時 19,729 ms，主執行緒沒有 ANR。
- 10:22 從 host LINE 發送 final_on，發送前分身 LINE 並未啟動。FCM count 101 → 102，分身冷啟動後入庫 1 筆，系統通知包含同一標記（importance 4）。
- 10:23 關閉後服務與分身停止；final_off 在約 46 秒觀察內 DB 0 筆，無新通知，FCM count 不變。
- 10:25 OFF 手動開啟分身後 final_off 入庫 1 筆，不產生新通知。後續前景停留觸發 FREEZER，促使加入前景 bound service；此修補的重新驗收如下。

## 判斷與限制

觀察事實：開關保存、跨入口同步、OFF 通知阻擋及 ON 真實推播均已有 Pixel 7 直接觀察；前景凍結是另外發現且必須處理的 runtime 生命週期問題，不能以短時間同步成功代替持續前景使用驗收。

推論：Daemon 的主執行緒阻塞與 OFF 引擎失去程序重要度是兩個不同問題，分別需要 worker 和前景系統綁定；單純延長背景保活不符合 OFF 語意。

既有通知保留；OFF 阻擋的是新通知。此開關作用於全部分身，重新 ON 可能補收積壓訊息。

本次沒有建立或操作持續計費的雲端資源。

## 最終建置

版本 `0.1.3-background.20260926`（1004），APK SHA-256 `1fa2a476a3f00f1b95b9d6499c96b3658780a1d0d48e16c3a73be4875a0a0745`。

- Runtime 700 + app 151 = 851 項 JVM 測試全部通過。
- 8 項實機 instrumentation 通過（12.086 秒），涵蓋設定卡及靜態推播冷啟動。
- APK / androidTest APK 建置、完整 app/runtime lint 通過；既有 lint baseline 保留。

## 前景綁定修補後的實機驗收

- 10:35:16 至 10:37:00，OFF 分身前景停留與聊天頁往返共約 104 秒：LINE PID 17109、engine PID 16754 保持不變，engine `oom_score_adj=0`，無 ANR、無 FGS。
- 10:37:02 Home 後 LINE/GMS 全部停止，ForegroundEngineService 綁定與 DaemonService 均不存在；OFF 值保持。
- `binding-foreground-90s` 採樣實際距起始約 88 秒；最終 `binding-foreground-navigation` 才是超過 100 秒的驗收依據。

- 最終版 10:37:37 發送 release 標記，10:38:29 仍為 OFF：DB 0 筆、無通知、無 LINE 分身程序，microG count 保持 103。

- 10:38:30 經快速設定重新 ON，未開啟分身；microG count 103 → 104、LINE 自動冷啟動。10:38:58 同一 release 標記入庫 1 筆且通知包含標記（importance 4），推播 receiver 完成。
- 最後設定卡與 Android Tile 均顯示「已開啟」，Daemon FGS 正常，保留此狀態交付。

結論：本次要求的兩處同步開關、持久 OFF、OFF 前景使用與離開停止、ON 恢復 LINE 通知，均完成實作及實體 Pixel 7 驗收。可靠度分數：97%；分數限於已測裝置與流程，前景停留約 104 秒，未將有限樣本解讀為所有 Android 裝置或無限時間保證。驗證方式：以上述標記逐一比對 LINE DB、通知、microG 計數、程序世代與系統重要度；更長前景使用或其他 Android 裝置出現失敗可下修此判斷。
