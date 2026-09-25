# LINE 背景冷啟動推播：顯式 Application 初始化

日期：2026-09-26。實機：Pixel 7、Android 17/API 37、build incremental 15641320。

## 問題與根因

- Given：cloned LINE 的 guest Application 尚未初始化，AppTwin 與 microG 背景服務仍運作。
- When：由 host LINE 傳送訊息到分身帳號。
- Then（修正前）：microG 收到推播，wrapper/attestation/workload gate 均通過，但 static broadcast 停在 dispatch-queued，8 秒後 receiver-timeout。直到使用者打開分身才收訊。
- Then（預期）：不開啟分身 Activity，背景初始化 Application、派送 receiver，訊息實際入庫並顯示 cloned LINE 系統通知。

2026-09-26 02:12 與 02:14 的兩則原始流程測試均重現逾時。02:15 手動打開分身後，同一 PID 26486 才執行 bindApplication 並由 STARTING 轉 READY；02:16 背景測試約 90 ms 完成 receiver，資料庫與系統通知均有該測試標記。

`b9b77e3`（首次包含於 v0.1.3）引入的 dispatcher 在 STARTING 直接返回；guest 原本在 handleReceiver 才 bindApplication，形成互相等待。本次證據不支持套用既往「microG ingress count=0」的不同事件。舊 APK SHA-256：`ac03e1bfffc4f46e069c82052ad90c8f52398d0b63f38c2bc8e19f07390e5276`，對應 9 月 18 日電池設定驗收。

## 調整

```text
合法背景推播 → 短期 bind/thaw → oneway bootstrapApplication
  → guest 主執行緒驗證身分與期限 → bindApplication
  → 原有 appDoneExecuting 身分驗證 → READY
  → FIFO receiver → 真實完成 ACK → 釋放綁定
```

- 沿用既有 appDoneExecuting 作為唯一初始化完成依據，不捏造 READY。
- 初始化要求核對 package/process/vuid/generation、server token 存活與期限。
- 每個 lease 僅要求一次初始化；暖程序不重新初始化；READY 前不消耗 receiver FIFO。
- 保留 enqueue 起算 8 秒總期限及 3 秒 bind timeout；區分 bootstrap-timeout 與 receiver-timeout。
- stop/death/期限失效撤銷排程與 binding，取消以 capability/generation 定位；舊 ServiceConnection 回覆不可取消替代 lease。
- 限期的是派送與 binding，不代表能安全中斷已開始的 Application.onCreate。取消阻止尚未開始的初始化及失效 receiver。
- 不變更 microG、LINE、資料模型、登入或電池設定；不新增永久保活、私有 API 輪詢或通知複製。

## 驗收

測試訊息由使用者明確授權的 host LINE 對話送出；只保留測試標記命中數、時間與 metadata-only checkpoints，不提交聊天資料庫、token 或帳號內容。

候選版本：`0.1.3-bootstrap.20260926`（versionCode `1003`），以保留資料方式安裝至 Pixel 7。APK SHA-256：`acea6a6a9d56f6b2da32a31687935d2faf294d26b2ad764b1263dd8ac9176d40`。

- runtime 677 項、app 146 項 JVM 測試：823 項全部通過，無失敗或略過。
- `:virtual-runtime:lintDebug` 與 `:app:lintRuntimeProbeDebug`：BUILD SUCCESSFUL，沿用專案 lint baseline；不是零警告宣告。
- Pixel 7 instrumentation：6 項全部通過（8.086 秒）。使用 production dispatcher、真實 Android Handler 與替身 guest transport，覆蓋 cold/READY/FIFO、warm、stop、舊 lease callback、真實 8 秒 timeout 與 late READY、process death。這些是控制流程測試，另以下列真實 LINE 入庫／通知驗收補足整合路徑。

| 實機樣本（2026-09-26） | 結果 |
| --- | --- |
| 02:33 cold1 | 新 PID 6805；bootstrap 01.922 → READY 02.471 → receiver completed 03.195。精確標記入庫 1 筆、系統通知命中。 |
| 02:34 cold2 | 新 PID 7625；bootstrap 30.791 → READY 31.294 → completed 32.040。入庫 1 筆、通知命中。 |
| 02:37 series1 / series2 | 兩個精確標記各入庫 1 筆；最新通知命中 series2。暖程序 receiver 約 52 ms，無重新 bootstrap。未將已被更新的 series1 通知宣稱為獨立觀察成功。 |
| 02:39 reclaim_network | 行動網路 210 → none → 213 復原，再只終止分身 LINE PID 9008。新 PID 9850；bootstrap 55.199 → READY 55.678 → completed 56.429。入庫 1 筆、通知命中。 |

上述均未打開 cloned LINE Activity。另一次 UI 發送操作過快，將兩個測試標記合併成一則訊息；不計為兩則成功樣本，也不視為 AppTwin 丟訊息。

30 分鐘熄屏驗收已完成：02:40:12 至 03:10:21，系統 `mLastSleepTime` 顯示連續睡眠 1,808,697 ms，期間 `mLastWakeTime` 未變。解鎖後於 03:19:04.100 從同機 host LINE 發送 `A_bootstrap_20260926_idle30`，03:19:04.253 已要求再次熄屏；接收時為 Asleep。此間沒有開啟 cloned LINE。

- 發送前：guest LINE 程序不存在，精確測試標記 DB 筆數 0，microG count 96。
- 03:19:06.649 ingress；07.711 bootstrap；08.341 新 PID 18701 / generation 7 READY；09.144 receiver completed。從發送到完成約 5.0 秒，從 ingress 到完成約 2.5 秒。
- 發送後：microG count 97；精確標記入庫 1 筆；AppTwin LINE channel 通知命中，importance 4；鎖定畫面也目視顯示該測試通知。
- 測試邊界：已涵蓋連續熄屏 30 分鐘後的背景冷啟動，發送時曾喚醒同機 host LINE，不能稱為外部裝置在全程未喚醒的接收手機上發送。手機接電與 ADB 相連，亦不等同拔線深度 Doze。


## 判斷與邊界

- 觀察事實：修正前 cold 兩次逾時，手動開啟才 READY；修正後多次 cold/reclaim 都自行 READY，且真實訊息入庫並出現通知。
- 推論：已解除本次 STARTING 與 receiver 初始化互等的根因；此判斷不外推為修復所有歷史 LINE 通知原因。
- 結論：原始冷啟動故障路徑已在 Pixel 7 通過驗證。可靠度分數：98%（對本次冷啟動根因與修補有效性的判斷，非長期通知送達率）。若相同版本在 ingress 已抵達時仍停於 bootstrap/READY 前，此分數應下修並重新定位第一個失敗 checkpoint。
- 驗證限制：ASUS 未測；手機接電，不能代表拔線深度 Doze。仍依賴 LINE backend、microG ingress、網路、OS 與使用者未停止 space，不能保證永遠收到通知。


Sanitized 驗收資料：[evidence/2026-09-26-bootstrap](evidence/2026-09-26-bootstrap)。
