# Space Google 帳號隔離修正與驗收

## 目前結論

**source24 已修正兩條帳號路徑，原 LINE 已接受 Space Google 帳號並找到雲端備份。** 完整備份還原／LINE 首頁仍停在使用者專屬備份 PIN；未讀取、保存、猜測 PIN，也未要求在對話提供。ASUS／Pixel 7 正式驗收尚待完成；目前 Pixel 10a API37／runtimeProbe host target28 結果僅為輔助。

最新 source24 完整 42 項 regression **全部通過**（175.361 秒），沒有 negative per-test status／skip。最後更新：2026-10-14。

## 問題與最終路徑

原 Google 登入經 physical Credential Manager，顯示 host 帳號。改為同 Space pinned microG 真正 Google 登入，保留原 caller、server client ID、nonce 與 hosted domain，回傳 genuine credential；沒有換依賴、OS profile 或自行製造 token。

登入後 LINE 聊天還原另用 framework AccountManager chooser，也曾 physical fallback 顯示 host 帳號。原 chooser 攔截後還發現 LINE 合法使用 `FLAG_ACTIVITY_FORWARD_RESULT`：transient Activity 將結果交給 parent 後結束。舊持續驗證 source ActivityRecord 會把這條合法鏈判成 canceled，故需 server bounded request capability。

```text
Google credential request
  → SpaceGoogleCredentialBridge：caller／Space／generation／120s session
  → 同 Space pinned microG AuthSignInActivity → genuine Google credential
  → 原 LINE callback（一次）

LINE 聊天還原 framework account chooser
  → exact component／selector 攔截，保留 FORWARD_RESULT／result route
  → server capability：source process／generation／client Binder
      + Space／package epochs／TTL／host UI claim／once consume
  → local non-exported virtual chooser → explicit-user VAccountManager
  → filters／membership／scope 重查 → parent LINE result
```

microG pinned `v0.3.15.250932`、GmsCore code `250932030` 不變。virtual chooser 保存 scope、使用 explicit-user authenticator、不 fallback host，結果返回前重新驗證。目前 prepared／candidate／create／clear、非支援 Google options／privileged origin 等仍 fail closed，不宣稱完整 Credential Manager parity。

## source24 最新證據

| 項目 | 結果與限制 |
| --- | --- |
| Build | 通過，66 秒；API24 removeIf 改為現有最低版本可用 Iterator，無 suppress／依賴變更 |
| Unit | App 232、runtime debug 843；0 failure／error／skip |
| Lint | 未過濾 Error／Fatal 0；warnings App／runtime／fixture 30／142／17；既有 baseline 不變 |
| Native focused | **通過**，4 probe、32.736 秒；`/tmp/apptwin-google-nativepicker24-forward.log` |
| Forwarded fixture | parent for-result → transient forward+finish，確認來源銷毀後選帳號，正確 Space result exactly once；selection／cancel／empty 同步保留 |
| 原 LINE 帳號選擇 | 唯一 Space Google 帳號，無 host chooser；只記憶體比對，不保存帳號值 |
| 原 LINE 結果 | 15:42:08 scope-current → existing-account → scope-complete → result-ok-account 均 true；`/tmp/apptwin-native24-result.log` |
| LINE business state | 保留預期 Space 帳號，實際找到雲端備份、還原按鈕 enabled；`/tmp/apptwin-line24-business-state.json`（布林 metadata） |
| 真實還原 | 點還原到備份 PIN prompt；尚未完成 PIN／還原／LINE 首頁 |
| 完整 regression | **42／42 通過**，175.361 秒，0 failure／skip／negative per-test status；`/tmp/apptwin-google-space-final42-source24.log` |
| 最終 APK hashes | source24 build metadata 已保存，見下列 hashes；安裝匹配依 root 驗證記錄 |
| 正式裝置 | 待 ASUS／Pixel 7；Pixel 10a 僅輔助證據 |

最終 source24 APK SHA-256（`/tmp/apptwin-google-final24-build-metadata.json`）：

- App：`0a68397fa9999dcd2cc27a28f7eea6725e12b0a88289577c3d699a24c0f6629f`
- Test：`6b3cb81946ff837a76a5ad8786f6e89b8843c7498baec740ef614f4535da09e1`
- Fixture：`6f865ec7ec46f8e67739854a4b17879a0c736f753a5e9fda80f05394721eaf86`

Google credential 路徑先前已在原 LINE 驗證真實 120 active-uptime deadline、caller frozen 時 UI-local timeout 清理、Back cancel、重試及 terminal callback 一次。這些是該路徑的直接觀察；active uptime 不等於深度睡眠 wall-clock 保證，沒有增加 thaw／keepalive。source24 當前 native 結果另有上述直接證據。

## 失敗歷史與證據邊界

先前 nested ResultReceiver／ClipData BadParcelable 已以 framework CREATOR normalization／隔離 capability 處理並驗證 UI 註冊與清理。source22 真實觀察 scope 在 onCreate 為 true、確認時 false 而取消，後由 FORWARD_RESULT 解釋並由 source24 capability 修正。**該舊原始檔路徑被後續 observer 覆寫**；歷史來源為當時工具輸出轉錄，不將覆寫後檔案或其 hash 當成原始 source22 證據。source24 使用獨立 `/tmp/apptwin-native24-result.log`。

source19 full regression 曾有 6 個失敗，首個 AccountRefresh body 通過、cleanup runtime ownership 失敗後有 cascades。root 保存 exact owned metadata，僅一次保留資料 install／cold-start 使用既有 recovery，process/service=0、pending=false、GroupStore 自動 reconcile 只剩原 group；未手動刪 private files。source21 後續 full42 通過，但 cleanup ownership 邏輯未改，**不宣稱 race 已修復**。舊版 41／42 pass 均不取代 source24 最新完整 run。

先前真實通話曾阻擋前景驗證；未控制或結束通話。通話 UI 離開後恢復測試；observer／foreground 失敗不被誤歸因為已證實 rotation bug，未放寬隔離斷言。rotation 操作亦不被當成 Activity recreate 的證明。

## 剩餘 gate

- [x] 兩條 Space 帳號來源與合法 forwarded result 實作、source24 build／Lint／unit／focused 通過。
- [x] 原 LINE 只選 Space 帳號，保留選定身分並找到真實雲端備份。
- [x] source24 完整 42 項通過及最終 App／test／fixture hashes 保存；安裝匹配以 root 驗證記錄為準。
- [ ] 備份 PIN／還原／最終 LINE 首頁；使用者專屬操作尚未完成，不猜測或收集 PIN。
- [ ] 實體 ASUS 或 Pixel 7 正式驗收，依最新 replacement AGENTS.md，不沿用舊 Pixel 10a 例外。
- [x] source24 清場完成：只剩原 Space、Google 帳號數 1；原 group/apps metadata 未變，安裝 APK hash 匹配最終產物，獨立審查無阻擋問題。
- [x] 驗收文件與此修正一併提交；實際 commit 以 git log 為準。

主要 Google 登入可能已移轉 LINE primary-device session；未選 host 帳號，未確認略過備份，不宣稱資料完整遷移或 private data byte-for-byte 不變。LINE 背景推播問題不在本次驗收證據內。未建立持續付費雲端資源或本次 dev server。

觀察事實：source24 原 LINE 已接受 Space 帳號並發現實際備份。結論：帳號隔離兩條路徑具備直接輔助實機成功證據，完整還原／正式裝置 gate 仍待完成。可靠度分數：94%（已觀察帳號隔離範圍）。驗證方式：核對已通過的完整 regression 與安裝 hashes，完成已授權備份 PIN 與正式裝置原操作；不以 fixture 或進入 PIN 頁冒稱還原完成。

## 官方核對來源

- [Android Credential Manager Google 登入](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)：Google options 與結果協定。
- [AOSP Android 14 ICredentialManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/credentials/ICredentialManager.aidl)：get／prepare callback。
- [AOSP Android 16 ICredentialManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/credentials/ICredentialManager.aidl)：candidate 與 cancellation 協定。
- [pinned microG GoogleIdService](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/credentials/provider/GoogleIdService.kt)：Google option／Space AccountManager 來源。
- [pinned microG SignInProxyActivity](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/credentials/provider/SignInProxyActivity.kt)：Google token 回傳 bundle。
- [pinned microG IdentitySignInService](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/credentials/identity/IdentitySignInService.kt)：caller、server client ID、nonce 的既有 signin 路徑。
- [pinned microG AssistedSignInActivity](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AssistedSignInActivity.kt)：轉入 AuthSignInActivity 的 options／nonce 協定。

注意：pinned microG 部分 signin debug logger 可能輸出帳號或 token 片段。驗收應只取受控 metadata，不保存原始敏感 signin logs。
