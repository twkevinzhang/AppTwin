# Space 存檔與還原驗收

## 支援範圍

第一版存檔使用 Android 系統文件選擇器，將一個 Space 的 CE／DE 私有資料、虛擬 external storage、App membership、active revision identity、GMS 狀態、virtual device identity及 runtime permission 決策寫入 `.apptwin-space` ZIP。

匯入永遠建立新的 Space UUID 與 virtual environment，不覆寫既有 Space。匯入在任何 mutation 前驗證 schema、entry path、大小、每檔 SHA-256，以及 Custodian 保存的整包 archive SHA-256。

## 安全限制

- 存檔目前不加密，可能包含帳號、聊天資料與 token，必須視為高度敏感資料。
- LINE 的 Android Keystore 私鑰不可匯出。登入延續只支援同一台裝置，且 `org.apptwin` 的 Android app data 未被系統清除或完整解除安裝。
- Space 刪除只在 exact retained keyspace marker 與 Custodian mapping 一致時保留 LINE aliases；一般刪除仍清除所有 guest-owned aliases。
- 同一 archive 的 keyspace 同時間只能由一個現有 Space 擁有。原 owner 仍存在時，匯入必須在建立新 Space 前拒絕。
- v1 要求 archive 內每個 App 的 exact active revision 仍存在於 AppTwin；revision 不符時不得建立 Space。

## Pixel 7 destructive E2E

Given：

- 實體 Pixel 7 已解鎖並連線。
- 測試 Space `custodian-line-test` 的 cloned LINE 已登入，可直接進入主畫面。
- `openinfo` Space 不在操作範圍。

When：

1. 從 Space 選單透過 SAF 匯出存檔至 Download。
2. 冷啟動來源 LINE，確認匯出沒有破壞現有登入。
3. 從 AppTwin UI 刪除來源 Space。
4. 確認 archive 仍存在、來源 virtual user 與資料目錄已刪除、exact Custodian aliases 仍保留。
5. 從設定頁選擇該 archive 匯入。
6. 確認建立不同 UUID 的新 Space。
7. 啟動還原後的 LINE，開啟聊天清單但不傳送訊息。
8. 強制停止 AppTwin，冷啟動後再次啟動 LINE。
9. 在還原 Space 尚存在時再次匯入同一 archive。

Then：

- 第 7、8 步均直接進入 LINE 主畫面，不出現登入、追加帳號或驗證碼畫面。
- 第 9 步在建立第二個 Space 前 fail closed。
- `openinfo` 的 metadata、virtual user 與 App 資料保持不變。
- 沒有 FATAL EXCEPTION、ANR、半成品可啟動 Space 或遺失的 archive reservation。
