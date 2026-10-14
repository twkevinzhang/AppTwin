# Space Google 登入修正與驗收

## 目前狀態

**已實作，尚待實際流程與正式裝置驗證完成。** Google 登入與後續 framework 帳號選擇器已有 Space-only 的原 LINE 輔助證據；最新候選的 native chooser 自動化、完整 LINE／Drive 與 ASUS／Pixel 7 正式實機驗收尚未完成。 本文件記錄進行中的證據，不能作為已修復或本次最終 APK 已驗收的宣告。最後更新：2026-10-14。

原始問題：Space 已有 Google 帳號，但 cloned LINE「登入 → 主要裝置 → 以其他方法登入 → 以 Google 帳號登入 → 以 Google 帳號登入」經實體 Credential Manager 顯示 host 帳號。

預期：原登入入口只使用該 Space Google 帳號；Google 授權產生的 genuine credential 回到原 cloned LINE，完成指定帳號登入。空 Space、失敗、取消與不支援的協定不得使用 host 帳號替代。

## 實作邊界

```text
cloned LINE / framework credential request
  → CredentialManagerStub（不轉送 host credential Binder）
  → SpaceGoogleCredentialBridge
      ├─ 驗證 Google option / caller / Space / generations / provider restrictions
      ├─ pinned microG archive 的獨立 ClassLoader
      ├─ 原 caller + server client ID + nonce + hosted domain
      └─ 同 Space virtual AuthSignInActivity
          → Space Google 帳號選擇 / Google 授權
          → GoogleSignInAccount result
  → 驗證目前 Space 帳號 membership / session / callback
  → framework GetCredentialResponse → 原 LINE callback
```

使用既有 pinned microG `v0.3.15.250932`（GmsCore version code `250932030`），沒有升級依賴或切換隔離模型。實作直接使用 microG AuthSignInActivity 的 GoogleSignInOptions / SignInConfiguration 協定，不是啟動實體 provider，也不是自行製造 token。

一次性 session 有 120 秒期限、取消 Binder、callback death 與 Space／package／process generation 檢查。只完成原 request，避免跨 Space 或 stale callback。結果僅在記憶體流轉；此文件不保存帳號、token、nonce、client ID 或裝置識別碼。

目前只支援單一 Google ID credential 的直接 get。prepared get、candidate、create、clear、其他 credential type、特權 origin、linked service、verified phone 與未支援的 authorized-account filter 等採明確 fail-closed 策略；prepared 回空結果，不代表已實作完整 prepared session。這些限制不應被描述為所有 Credential Manager 功能相容。

## 歷史第十五版 Google credential 路徑證據（非最新整體驗收）

| 項目 | 結果與證據 |
| --- | --- |
| Build／Lint | 通過，40 秒，`/tmp/apptwin-google-space-fifteenth-build.log`；未過濾 Error／Fatal 為 0 |
| Unit tests | App 232、runtime debug 836，0 failure／error／skip |
| Lint warnings／baseline | App 30、runtime 142、fixture 17 warnings；runtime 既有 baseline 13 errors／358 warnings 未變 |
| 輔助實機 regression | Pixel 10a 41／41 通過，0 failure／skip，123.286 秒；`/tmp/apptwin-google-space-final41-regression.log` |
| 安裝與建置一致性 | 安裝 App 串流 SHA-256 與建置 App 相同 |
| 原 LINE chooser | 唯一 Space Google 帳號、caller label LINE；只記憶體比對，不保存帳號值 |
| UI ownership | 14:04:14.035 root UI 註冊成功；callerKnown／callerMatchesVirtual 均 true |
| 真實 timeout | **通過**：caller frozen 時 Google UI 自行到期關閉，原 LINE 登入畫面恢復；見下方時序 |
| 取消與重試 | **通過**：重試仍為該 Space 唯一帳號；按 Back 關閉 owned UI、原 caller 收到一次 terminal error並回原 LINE |
| 真實 credential 回傳 | 第十一版觀察 genuine credential callback success 一次；第十五版選擇相同 Space 帳號後 LINE 亦辨識預期身分，繼續到聊天記錄還原頁；最終首頁仍 pending |
| 正式實體裝置驗收 | **待 ASUS 或 Pixel 7**；Pixel 10a 僅輔助證據 |

歷史第十五版 APK SHA-256（已由後續 production 候選取代）：

- App：`eed8228473618b2c4d7c7112c0913ba0083350020ad9794fdac05710f8b8fdb4`
- Test：`2f6fe0710464dcd57b813658b8f71fb954a8b5b5dba0d239a053e8b75a9bbb51`

### 原 LINE timeout／取消的直接觀察

下列時間皆為 2026-10-14 台北時間；root 執行原始 LINE 操作，未以 fixture 代替。

- 14:04:12.640：建立 request；14:04:14.035：owned root UI 註冊。
- deadline 前確認 caller frozen=true、Google UI frozen=false；僅保存布林 metadata（`/tmp/apptwin-google-final-freezer.json`）。
- 14:06:12.742：UI-local deadline；14:06:12.752：exact owned activity finish accepted=true、一次 terminal error。實際畫面返回原 LINE Google 登入頁，chooser 已消失。
- 14:06:33.927：新 request 重試，重新確認 Space 唯一帳號與 LINE label。
- 14:06:44.301：Back 返回結果；owned UI finish accepted=true，14:06:44.307 一次 terminal error，實際返回原 LINE。

這證明本次 caller frozen 情境中，active Google UI 可自行關閉。120 秒為 active uptime deadline，不宣稱 wall-clock、裝置深度睡眠或所有 frozen process 的即時喚醒保證。沒有增加 thaw、keepalive 或放寬隔離邊界。

### 已修正的候選失敗歷史

先前候選曾因 nested framework ResultReceiver／ClipData capability 的 ClassNotFound／BadParcelable 阻擋 owned UI 註冊；個別 Parcel 測試通過並不足以證明真實路徑修復。第十五版採 framework ResultReceiver CREATOR normalization、隔離 ClipData capability、UI-local deadline 與 current／pre-delivery deadline fence，後續實際 root UI 註冊、timeout與取消均取得上述通過證據。第十版 38 項 regression 與第十一版 genuine credential 成功為歷史證據，不能取代第十五版完整登入。

實機證據限 Pixel 10a、API 37、runtimeProbe host target 28，角色僅為輔助。最新使用者 AGENTS.md 僅接受 ASUS 或 Pixel 7 正式實機驗收，不沿用舊 Pixel 10a 例外。其他 API／target／登入 SDK 路徑未取得同等證據。

## 最新候選：聊天還原的 framework 帳號選擇器修正

第十七版 production APK 已修正第二條帳號路徑。原先 LINE 聊天還原「選擇帳號」會啟動 `android/android.accounts.ChooseTypeAndAccountActivity` 並列出 host 4 個帳號；發現時 root 已取消，未選 host 帳號。

```text
LINE 聊天還原「選擇帳號」
  → exact framework chooser component／selector
  → SpaceAccountPickerPolicy：caller Activity token + Space serial／generations
  → local non-exported virtual chooser
  → VAccountManager explicit Space user + allowable accounts／types
  → 回傳前再次驗證 scope／membership → 原 LINE
```

修正保留原 result routing、篩選 options；覆寫 guest user extra，以 authoritative activity token 與世代驗證 caller 範圍。saved state 保留 user；authenticator 使用 explicit user；新增帳號無法虛擬解析時 fail closed；結果重新檢查目前 Space membership，移除帳號值／extras 日誌。

第十六版完整 build 成功（111 秒），第十七版 incremental build 成功（3 秒）。App 232、runtime debug 839 unit tests 全通過，0 failure／error／skip；Lint warnings App／runtime／fixture 為 30／142／17，未過濾 Error／Fatal 為 0。建置 metadata：`/tmp/apptwin-google-final17-build-metadata.json`。

最新 production App 為第十七版，SHA-256：`75d66d6cefa301e7f52ee27ee470a3b013864845688eb4f9e8bede106bb69747`；安裝 App 串流 hash 已確認與建置一致。第十八、十九版只有 androidTest 觀察／安全 phase 診斷調整，production APK 未變。第十九版測試建置成功（9 秒，`/tmp/apptwin-google-space-nineteenth-build.log`），test APK SHA-256：`1e379833cf2f6c39821e053c57297bd029c15917c9778c7eaf361d4454b2d649`。

原 LINE 第十七版實際 chooser 已只呈現唯一 Space Google 帳號，記憶體比對通過且沒有 host android chooser。選擇該帳號並確認後回到 LINE 還原頁，但 LINE 尚未保留選定帳號，**不宣稱 Drive 授權、聊天還原或最終 LINE 首頁已完成**。主要 Google 登入已接受且 cold restore 仍保留進度；略過備份的確認對話已取消，未完成可能造成資料損失的略過操作。

第十七版 focused test 的 Espresso observer 無法看到 engine-process UI 而失敗；第十八版改為跨 process UiAutomation 觀察，隔離斷言未放寬，build 成功（11 秒），focused warm／cold 之後在 exact foreground guard 失敗；當時最上層為真實通話 UI，詳見下一節。最終 regression 仍待重跑結果。先前第十五版 Google timeout／取消實測保留為該路徑證據，不能自動替代本次新增 framework chooser 驗收。

## 目前驗證中斷的外部狀態

root 唯讀系統觀察顯示實體電話的 `com.google.android.dialer/...InCallActivity` 正在最上層。為保留使用者通話，未控制、結束或遮蓋通話；裝置 UI 驗證暫停，繼續不影響裝置的文件／原碼審查。

第十八版 native focused warm／cold 執行因 exact foreground guard 未成立而失敗。這不能證明帳號篩選成功，也不能在現有證據下歸因為 rotation bug。測試作者僅增加不含資料的 phase metadata 與分行診斷，不降低 foreground／lifecycle／隔離斷言。通話結束後須重跑原驗證；第十七版原 LINE Space-only chooser 觀察仍有效，但沒有任何本節的新完整通過宣告。

## 目前交付與驗收清單

- [x] 最新 production build／Lint 通過；App 232、runtime debug 839 unit tests，0 failure／error／skip。
- [x] 最新 production／test APK hashes 已保存；安裝 production 與建置相同。
- [x] 第十七版原 LINE Google 帳號及聊天還原 chooser 只顯示 Space 帳號；未選 host 帳號。
- [x] 歷史第十五版 Google path 的 41 項 regression、真實 timeout／取消／重試通過；**僅證明當時該版本及路徑，不是最新整體驗收**。
- [x] owned fixture groups／accounts 清理完成，只剩原 group；group／apps 公開 metadata 未變。runtime diagnostics 可能隨執行改變，未宣稱整個 private data byte-for-byte 不變。清場 metadata：`/tmp/apptwin-google-final19-cleanup.json`。
- [x] 原碼／文件審查完成；commit 待 root 執行。
- [ ] 最新 native chooser 自動化：第十七版 observer 失敗；第十八版 warm／cold exact foreground guard 失敗；第十九版僅診斷測試建置，真實通話持續中未執行。
- [ ] 最新完整 42 項 regression：尚未執行，不能沿用舊 41 pass。
- [ ] LINE 還原選定帳號保留、Drive 授權與最終 LINE 首頁。
- [ ] 實體 ASUS 或 Pixel 7 正式驗收；最新使用者 replacement AGENTS.md 為準，不沿用 ignored local AGENTS.md 的舊 Pixel 10a 例外。

主要 Google 登入已獲使用者授權並完成過真正帳號操作，LINE primary-device session 可能已移轉；cold clone resume 可觀察仍停在還原頁。沒有確認略過備份，沒有宣稱備份已還原或資料完整遷移。真實通話仍在前景，沒有操控或中斷通話。沒有建立雲端持續付費資源或保留本次 dev server。

## 證據解讀

觀察事實：主要 Google credential 已回到 LINE 並識別預期身分；第十七版還原 chooser 亦只顯示 Space 帳號。最新 production unit／Lint 通過，歷史第十五版 41 項實機 regression 通過；最新 native 測試與完整 42 項驗收尚未通過／執行。

推論：host credential 路由缺口已在候選版本被替換為 Space microG 登入路徑；第十五版 UI ownership 註冊、timeout／取消已取得原 LINE 通過證據；最終外部授權／登入與指定正式裝置 gate 仍未完成。

結論：目前只能稱「兩條帳號隔離路徑已實作並有原 LINE 輔助實機證據，最終自動化、完整登入與 ASUS／Pixel 7 正式實機驗收待完成」。可靠度分數：90%（僅對已實作與直接觀察的隔離邊界，不表示整體已正式驗收）。驗證方式：以最後 source/APK 重跑完整原操作、timeout/cancel 及 callback 身分驗證；通過後再更新最終結論。

LINE 背景推播／microG 上游 ingress 問題不在本次登入修正的完成證據內，不宣稱已修復。

## 官方核對來源

- [Android Credential Manager Google 登入](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)：Google options 與結果協定。
- [AOSP Android 14 ICredentialManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/credentials/ICredentialManager.aidl)：get／prepare callback。
- [AOSP Android 16 ICredentialManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/credentials/ICredentialManager.aidl)：candidate 與 cancellation 協定。
- [pinned microG GoogleIdService](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/credentials/provider/GoogleIdService.kt)：Google option／Space AccountManager 來源。
- [pinned microG SignInProxyActivity](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/credentials/provider/SignInProxyActivity.kt)：Google token 回傳 bundle。
- [pinned microG IdentitySignInService](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/credentials/identity/IdentitySignInService.kt)：caller、server client ID、nonce 的既有 signin 路徑。
- [pinned microG AssistedSignInActivity](https://github.com/microg/GmsCore/blob/v0.3.15.250932/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AssistedSignInActivity.kt)：轉入 AuthSignInActivity 的 options／nonce 協定。

注意：pinned microG 部分 signin debug logger 可能輸出帳號或 token 片段。驗收應只取受控 metadata，不保存原始敏感 signin logs。
