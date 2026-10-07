# ASUS eWeLink 捷徑代理修復驗證

日期：2026-10-07（Asia/Taipei）。修正 commit：`09d31af`。

## 環境與原始問題

- 實體 Zenfone 7，型號 `ASUS_I002D`，Android 12。
- AppTwin：`org.apptwin`，0.1.3 release；修後安裝時間為 21:59:03。
- Space：`openinfo`，environment ID 1。
- eWeLink：5.29.1，version code `2026091016`。
- Given：在 openinfo 開啟既有 eWeLink 分身。When：App 顯示 loading。Then：原本約兩秒後閃退；預期持續顯示既有登入畫面與設備列表。

## 觀察事實

修前 21:45:54 啟動 eWeLink，21:45:56.919 發生 `SIGSEGV`。同機 LINE 在 21:46:17 的 `getShortcuts()` 呼叫直接記錄 `AndroidFuture`／`ParceledListSlice` 回傳型別不符的 `ClassCastException`。eWeLink 最初的 JNI 例外未直接捕捉，不能將其 C++ ABI 混用列為已證實根因。

修正保留捷徑查詢的非同步回傳契約，並完成 21/21 項相關測試。

修後 22:02:42.501 出現 `group-app-start`，eWeLink guest PID 為 9373、UID 為 10296。22:02 畫面顯示既有登入狀態與設備列表；本紀錄不保存設備名稱或敏感截圖。22:04:30 再次自行從 openinfo 點開 eWeLink，約 22:05 檢查時同一 PID 仍存活，host `StubActivity` C3 為 resumed，未觀察到 `ClassCastException`、uncaught exception 或 fatal crash。兩次啟動均已有 `getMaxShortcutCount` 呼叫紀錄。

## 驗收範圍與結論

原本約兩秒的閃退點已通過，第二次啟動至少觀察前 30 秒成功；先前程序存活超過兩分鐘，但不能據此聲稱全程在前景。原定 60 秒觀察的末端因 USB 斷線未完成。使用者隨後明確要求停止等待、直接提交、建立 `v0.1.5` 並推送，因此不將本次結果描述為完整 60 秒末端驗收。

推論：保留 Android 12 捷徑 API 的非同步回傳契約，已消除本次實機可重現的 eWeLink 啟動閃退。可靠度分數：95%。本次並未證明或修復獨立的 native ABI 問題，也未驗證 LINE 背景通知。

驗證方式：重新連線同一 ASUS 裝置，在 openinfo 重複啟動 eWeLink，連續觀察至少 60 秒並核對 guest 程序與崩潰日誌；若閃退再次發生，取得首次 JNI 例外後重新評估根因。發布的 CI 編譯結果須另以實際 workflow 終態確認。
