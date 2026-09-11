# ReVanced 共用執行層修正與實機記錄

日期：2026-09-12，Asia/Taipei。基底 commit `73871ae`；於既有 `main` 工作。

## 結果範圍

已修正 provider 對共享 attribution 的污染，以及媒體鍵 targeted dispatch 的 caller package 漏轉。Pixel 7 實測已排除 `OpPlayAudio` 靜音，數位音訊通過播放／暫停／續播；不能因此宣稱喇叭聲學驗收或所有生命週期通過。

黑邊仍未定位精確觸發條件。本輪沒有修改布局行為，只有新增預設關閉、有總量限制的幾何診斷。重開後正常不構成黑邊修復。

## 裝置與版本

- 實體 Pixel 7，Android 17／API 37，build `CP2A.260705.006`。
- Clone：`app.revanced.android.youtube`，20.40.45，target 35；AppTwin `runtimeProbeDebug`，target 28，實體 UID 11291。
- 最終已安裝 APK SHA-256：`58ff1bdd031a3932c791a1ba3283017eec8702bf5040721f7f514df85bea0bfc`。先核對手機與本機 SHA，再 `pm install -r`，未清除 AppTwin／guest 資料。
- APK 大檔 USB 傳輸實測約 1.1 MB/s、120 秒；先前 90 秒超時與安裝失敗不能當成程式回歸。最後以獨立 push（300 秒上限）及 install（60 秒上限）完成。

## 觀察事實

### 音訊污染

1. 原始播放：speaker 路由、未全域靜音，但 `OpPlayAudio` 報 guest package 不屬於實體 UID 11291；player `mutedState:opPlayAudio`。
2. Application 初始化後 attribution 正確為 host；較晚 `trackPlayer` 的 current operation package／global source 卻變成 guest。
3. `ProviderHook` 舊程式直接遞迴修改傳入 source；該物件可與 Application/global source 共用。這會把 Context 的 operation identity 一起污染。
4. 新程式僅對自有 root 建立 runtime state 副本，保存 token、next、pid、device、permissions 與未知欄位，然後替換本次 `args[0]`。其他 principal、delegated chain、原 Context 不改。
5. 不採 Builder copy：Android 12／12L 無此建構式；Android 13／14 的 copy 路徑未保存 next。這些版本已核對官方程式，但沒有實機執行。

### 媒體鍵

原 `keyevent 127` 使 guest 崩潰：`dispatchMediaKeyEventToSessionAsSystemService` 收到 guest caller package／UID 11291。修正只核對 `boolean (String, KeyEvent, MediaSession.Token)` 的該入口，將目前 guest 的首參數改為 host，保留事件及目標 session。

最終候選在診斷關閉下：PID 15799、player 1983 由 `started → paused → started`，均 `mutedState:none`，未再次出現該崩潰。

### 數位輸出與喇叭界線

- 最終 output capture 的逐秒 RMS 約為 −49／−52／−50／−54 dBFS，暫停連續四秒為全零，續播後約 −49～−48 dBFS。分析程式以 −200 表示零樣本，不是實測噪聲底。
- 跨 app 返回後再次 capture：mean −53.1 dBFS、max −38.1 dBFS。
- `scrcpy --audio-source=output --no-playback` 會短暫將音訊擷取而停止手機輸出；每次有 5～16 秒上限，程序結束後恢復路由。因此此證據只證明數位路徑，不能冒充喇叭聲學量測。
- `playback --audio-dup` 曾全零；此模式允許 app 拒絕擷取，host probe target 28，因此不能以該模式的零值判定 app 無聲。
- 手機 mic-unprocessed 的播放／暫停樣本未形成清楚對照；Mac 麥克風樣本全零，均不算有效喇叭驗收。曾短暫將音量 4/25 調至 14/25，以 finally 恢復 4/25。

### 畫面

| 樣本 | navigation divider 起點 | watch/player 起點 | Surface 可見範圍 |
|---|---:|---:|---|
| 原始失敗播放 | y=136 | y=136 | `[0,272][1080,743]` |
| 同一 Zorig 影片重播 | y=0 | y=136 | `[0,136][1080,744]` |
| 同影片迷你播放器展開後 | y=0 | y=136 | `[0,136][1080,744]` |
| 同影片全螢幕返回直立 | y=0 | y=136 | `[0,136][1080,744]` |
| 最終候選跨 app 返回後，另一影片 | y=0 | y=136 | `[0,136][1080,744]` |

同一影片為 ZorigNomadTW 的「台灣有一件蒙古人都想親眼看到的國寶」。正常 XML 的 player overlay 也稱「展開迷你播放器」；此描述不是失敗狀態的獨有證據。全螢幕截圖呈現符合影片比例的左右留白，不是原本額外向下位移。

## 已完成與未完成驗收

| 項目 | 結果與限制 |
|---|---|
| JVM 回歸 | 666 tests，0 failures／errors／skips |
| lint／build | `:virtual-runtime:lintDebug`、`:app:assembleRuntimeProbeDebug` 通過 |
| 真實 framework | 最終 APK 的三項 instrumentation tests 通過：metadata／chain、實際 Context 併發不污染、virtual root 轉換 |
| 媒體鍵／數位輸出 | 暫停、續播及跨 app 返回後有數位輸出；診斷關閉仍成立 |
| eWeLink | 既有登入／裝置列表可見，底部「家庭→我的→家庭」可操作；未控制任何裝置，未驗證鍵盤場景 |
| Facebook | 本機 clone 為完整 `com.facebook.katana`；首頁與搜尋鍵盤可用，未發文／發訊。不是 Facebook Lite 回歸的替代品 |
| ReVanced 搜尋／GMS | 搜尋、頻道與影片可載入；未執行完整 GMS／LINE 回歸 |
| 影片供應 | 一次音／視訊分段遭 HTTP 403、播放器 buffering；該樣本不算有效播放驗收，另部影片可播放 |
| 啟動／背景返回 | 曾一次主畫面顯示需要修復，重新點選 clone 後成功，原 PID 音軌仍存活；不宣稱整體啟動狀態已修復 |
| 黑邊 | 保留失敗與正常證據；無精確重現條件、無布局修正，仍未解決 |
| 喇叭聲學／完整三輪原始 E2E | 未完整通過，不以 unit／數位 capture 取代 |
| ASUS／Android 12～14／Lite／floating fixture | 未實測 |

## 推論、結論與可靠度

- **共用層確有缺陷，可靠度分數：97%。** 不同入口對 caller identity 的處理不一致，且一次呼叫污染另一入口使用的共享物件；這不是 ReVanced 單一設定問題。驗證方式：反覆 provider→player 順序及媒體鍵 E2E，若原 source 仍變異或 mismatch 再現即下修。
- **不能推論整個隔離架構需要重寫，可靠度分數：90%。** 本次可透過保留 guest 身分、僅在精確系統邊界轉換並採私有副本改善。尚未驗證的入口及舊版相容性保留於共用政策文件；不能宣稱全面修復。
- **黑邊成因目前無法判定。** 直接證據是多了一層 136px 位移，並非整個 Window 高度縮小；將其歸因於特定 inset 寫入點或迷你播放器尚缺因果證據。驗證方式：下一次原始失敗時取得同程序的 policy 決策與 decor→Surface 祖先幾何，定位第一個多加 inset 的層，再做有失敗對照的修正。

## 證據位置與清理

原始證據：`/tmp/apptwin-revanced-investigation/playing/`。本轮證據：`/tmp/apptwin-policy-fix/`，含 `framework-tests-final.txt`、`media-key-sequence.json`、`final-output-rms.json`、`original-normal.xml`、`original-mini-return.xml`、`original-full-return.xml`、`final-portrait.xml` 與分場景截圖。原始音訊／UI 只保存在本機，不加入 repository；暫存檔可能隨系統清理消失。

測試不改帳號、通知權限、電池白名單或雲端資源。診斷開關恢復 0、旋轉恢復直立、音量恢復 4/25。沒有新建持續計費資源。
