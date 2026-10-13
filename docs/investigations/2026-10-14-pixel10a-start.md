# Pixel 10a 分身啟動失敗

## 授權與原始驗收

使用者回報 openinfo 空間點選 cloned LINE 時顯示 `ACTIVITY_START_FAILED`，空間詳情顯示背景服務異常、microG 啟動失敗及 TCP 已中斷，重新啟動無效；已明確授權開始修正。

驗收：保留既有資料更新 APK，從相同 LINE 圖示啟動、退出及重試；確認 microG 啟動與實際 TCP 狀態。不得傳訊、登入／登出、清資料或卸載分身。主代理是唯一裝置操作 owner，無雲端操作或付費資源。裝置為 Pixel 10a、Android 17、kernel 6.1.162-android14、4 KiB page size。Pixel 10a 是本次原問題裝置；專案要求的 ASUS／Pixel 7 正式 runtime 回歸仍須另外完成。

## v1020 重現與第一個阻斷點

主代理點選 openinfo 的 LINE 圖示，親自觀察 snackbar `ACTIVITY_START_FAILED` 與 AppTwin crash dialog。guest 的 `A64NearHook` 回報 `maps-byte-limit`，`attempts=0/pages=0`；`Sensitive Binder gate installed=0`，最終 `Unable to establish guest native permission boundary`。microG 主與 persistent process 同樣退出。

唯讀 host maps 為 353757 bytes／3692 rows，server maps 為 314740 bytes／3198 rows；這些是 host/server 的量測，不是假稱已量到失敗 guest 的 snapshot。舊 reader 的 256 KiB cap 在完整 EOF 前拒絕，且 exact-cap EOF 也存在錯誤拒絕。

修正將有限容量調至 1 MiB；exact-cap 用一個 byte 的 EOF probe，仍計入 128 次 read 與 50 ms 配置 deadline。保留 4096 rows、64 次配置／64 pages、完整 newline、NOREPLACE 與 CAS publication，Binder gate 失敗仍拒絕 guest 啟動。

## v1021 實機發現的第二個阻斷點

容量修正後，guest 讀到完整約 308–311 KB 的 snapshot，79 次左右 read；parser 在約第 1175–1181 row 拒絕零長度區間，並非反向區間。該 row 的 permissions 為 `r-xp`。這時仍未通過啟動驗收。

Android 官方 [android14-6.1 task_mmu.c](https://android.googlesource.com/kernel/common/+/refs/heads/android14-6.1/fs/proc/task_mmu.c) 使用 `VMA_PAD_START` 顯示 data end，以 `vma_pages` 決定是否輸出原 VMA，再輸出 padding VMA。[pgsize_migration.c](https://android.googlesource.com/kernel/common/+/refs/heads/android14-6.1/mm/pgsize_migration.c) 讓 padding 的起點等於 `VMA_PAD_START`，並清除 read/write/execute flags。因此完全 padding 的 data row 可顯示零長度，後接同起點、非空的 `---p` padding row。這是與裝置核心族群相符的來源證據，不表示已對照裝置全部核心 patches。

實機四個失敗 snapshot 均確認 `nextSameStart=1 / nextNonempty=1 / nextPadding=1`。最終 parser 僅略過這個完整合法 pair 的空 data row，並把下一 row 正常加入 occupied ranges；因此 padding 不會被誤判為配置空隙。空 data row 不能位於前一佔用區內，反向／重疊／未配對／錯誤 permissions／partial snapshot 均仍拒絕。4096 quota 改按實際 row 計算，empty rows 不能繞過上限。

診斷僅保存 byte／row／read 數、parse reason、row number、offset、permissions 及下一列關係；不保存 maps 地址、路徑、token、Intent extras 或 LINE 資料。實機不成功時停止 host，避免失敗重試持續耗電，更新後再啟動；未清除資料。查閱 MCS 日誌時曾在工具輸出誤包含敏感登入欄位，未將該原文保存到 repo；最終證據只保存登入回應成功／失敗分類。

## 驗證紀錄

原始 metadata-only 證據見 `evidence/2026-10-14-pixel10a-start/baseline.txt`；容量修正後尚失敗的證據見 `v1021-first-attempt.txt`。

APK build 成功。四個 ARM64 native helper suites（maps reader、near branch、island pool、far jump patch）在 host ASan／UBSan 下通過。測試涵蓋大於舊 cap、exact-cap EOF／over-cap、EINTR／I/O／read quota／deadline、4096／4097 rows、padding 不成為配置空隙及非法 pair；另保留 near branch／CAS publication／island pool 回歸。Gradle 最初沿用 cached unit 結果，最終另以 `--rerun-tasks` 重新執行；最終 775 tests／0 failures／0 errors／0 skipped，全數通過。這些不取代原實機流程驗收。

## Pixel 10a 原流程結果

- 最終安裝 `1021 / 0.1.21-pixel10a-native-maps`，主 APK 與裝置 SHA-256 一致：`dbcc49481be4794dce833279c2004a634d409e61d141aafcf043b208b82a3313`。AppTwin Custodian 沿用已安裝同 signer 配套，沒有卸載／清資料。
- 從 openinfo 原 LINE 圖示進入 LINE 歡迎／登入畫面；Back 回 AppTwin 後再點同圖示成功。另 force-stop host、冷啟動 AppTwin、再次點相同 LINE 圖示仍成功，共三輪。
- microG 主／persistent 與 LINE guest 皆出現 `Sensitive Binder gate installed=1`。最後驗收時間窗未見 AppTwin crash events 或 near hook failure。
- 空間詳情直接觀察「背景服務正常／microG 已啟動／TCP 已連線」。MCS 日誌經分類確認 `mtalk.google.com:5228` 連線及成功 LoginResponse；冷啟動後重新連線與登入成功，沒有以排程成功替代登入證據。
- 沒有 LINE 登入、傳訊、登出、清資料或分身移除。eWeLink、其他 cloned app、登入後功能、LINE 背景訊息通知及 ASUS／Pixel 7 回歸未驗收；不得由上述啟動成功推定它們已通過。

結論：本次 Pixel 10a 原啟動流程已通過；修正已實作，仍待專案指定 ASUS／Pixel 7 實機回歸，不能描述為產品完整 runtime 驗證。可靠度分數：99%（限三輪啟動與上述 microG／MCS 狀態）。驗證方式：重跑相同入口及 cold restart，對照 Binder gate、登入回應、UI 與 crash window；在 ASUS／Pixel 7 重跑相關隔離／啟動回歸才能擴大結論範圍。

完整 metadata-only postflight 見 `evidence/2026-10-14-pixel10a-start/final-postflight.json`；paired-empty 證據見 `v1021-padding-pair.txt`。本次沒有建立任何持續計費資源。
