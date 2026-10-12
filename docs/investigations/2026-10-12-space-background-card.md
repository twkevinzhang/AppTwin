# SpaceDetailsScreen 背景服務卡

## 目的與授權

使用者確認將背景服務卡加入每個 Space 的 `SpaceDetailsScreen`，呈現 microG 啟動與 TCP 連線狀態，提供相應重試；總背景設定關閉時卡片停用。使用者已授權實作及 ASUS 實機驗收。

實機驗收授權：保留資料更新 APK、Space 2 重試、總背景開關開／關、host LINE 向「張震龍 Kevin」傳少量測試訊息。總開關短暫影響所有 Space；結束恢復原設定。禁止清資料、登出、雲端操作。既有帳號與分身資料保留。

## 結構與行為

```text
SpaceDetailsScreen overview
  背景服務卡
    microG 啟動狀態
    TCP 連線狀態（exact virtual user 的可信 fresh observation）
    重試 / 最後檢查
  儲存 / 權限 / 帳號 / 備份 / 空間管理
```

未知、觀測失敗或過期證據不能顯示成功。host UID 包含其他分身，不可用共用 UID socket 數推定指定 Space 健康。重試限定 durable desired user，尊重總關閉、explicit stop、刪除與 generation fence；重複操作合併，有期限與退避。不得默默替換 pinned microG。

## 驗收條件

Given：ASUS 實機、現有 Space 2、背景功能啟用、指定 Space 推播連線異常。
When：查看卡片與點擊對應重試。
Then：顯示實際 microG／TCP 狀態、重試期間禁重複點擊、依真實結果更新，其他 Space 不被停止。

Given：總背景設定關閉。
When：進入 SpaceDetailsScreen 或呼叫重試。
Then：卡片停用，runtime 拒絕喚醒；遲到結果不恢復成功。

本機驗證需涵蓋 unknown/stale health、exact scope、重試與 off/stop race；實機驗證需涵蓋 production screen/backend 接線、global-off/on、exact-space retry。最後以原背景 LINE 發訊核對 ingress、入庫與通知；任何缺口分別列明，不能以 build/unit 取代。

## Readiness

- repo `/Users/zhangzhenlong/Projects/AppTwin`，branch `main`；開工時工作目錄乾淨。
- ASUS `L8AIB7611694M38` 可 adb 存取，`run-as org.apptwin` 可用。
- Java 17、Android SDK、Gradle wrapper 可用；不需要新增登入或 credential。
- 不建立雲端資源，不執行付費操作。

## 結果

- runtime 738、app 182 個單元測試通過；arm64 JNI 與 runtimeProbe APK／instrumentation APK 編譯通過。
- TCP evidence 由 exact guest 的 pinned microG MCS socket，透過 `getsockopt(TCP_INFO)` 取得；登入驗證要求成功登入事件捕捉的 socket identity／session start 與 fresh probe 相符，並確認 stream 與 heartbeat 狀態。不能觀測時回 unknown。
- 手動重試以 exact virtual user 停止／重啟 McsService，尊重總背景設定、既有 desired membership 與 stop fence；其他 Space 不被停止。UI 只在詳情頁 resumed 時低頻更新。
- ASUS 上實際 Space 2 卡片呈現「正常／microG 已啟動／TCP 已連線」。總設定 off/on 的 production wiring 測試通過，卡片停用與 runtime 拒絕重試皆經驗證，結束恢復原值開啟。
- 第一版 native 方法註冊簽名不符造成啟動崩潰，已修正 typed Java wrapper／Object JNI 對應，重新安裝後實機卡片測試通過。未清除資料。
- 原背景 LINE 實测：20:14:04 發送，20:14:05.133 ingress、20:14:07.080 bootstrap、20:14:09.183 READY、20:14:10.253 completed。microG count 17→18，唯一測試標記 DB 0→1，AppTwin 通知命中。全程未開 cloned LINE。
- 最新 ASUS 卡片 UI 6 tests、production global policy wire 1 test 通過。
- 使用者另行明確授權短暫停止 Space 2 MCS 服務，故障／恢復共用 120 秒上限。測試第一次在健康 baseline 尚未成立時退出，未注入；補上獨立 30 秒 baseline 等待後通過。
- 額外 opt-in 斷線→點卡片重試→恢復實機測試通過：20:24:54.353 baseline phase=CONNECTED / TCP=CONNECTED / session=true；20:24:54.363 exact user 2 stopResult=1；卡片實際顯示「已中斷」，點「重新連線」；20:24:57.267 terminal connected / verified=true。注入至恢復約 2.9 秒，其他 Space generation 未改變。整組測試 15.636 秒，沒有清資料或停止其他 Space。
- 重試後再跑原背景 LINE 流程：20:26:28 發送，20:26:29.280 ingress、20:26:30.704 bootstrap、20:26:32.895 READY、20:26:34.273 completed。microG count 18→19、精確測試標記 DB 0→1、AppTwin 通知命中。全程未開 cloned LINE，總背景設定保持開啟。

證據：[evidence/2026-10-12-space-background](evidence/2026-10-12-space-background)。背景成功證明本輪投遞恢復，不代表長期送達率、完整斷線自動恢復或其他 microG 版本相容性。可靠度分數：98%（已列明的實測結果）；驗證方式：可重跑 opt-in 實機測試及原背景發訊。
