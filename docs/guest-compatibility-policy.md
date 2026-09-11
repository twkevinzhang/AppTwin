# Guest 共用相容政策與驗收

## 狀態與證據

本文件定義視窗與音訊身分的共同契約；本輪修正與驗收範圍見 [實機記錄](investigations/2026-09-12-revanced-runtime.md)，不表示全部問題已修復。

- 既有歷史：Facebook Lite 曾出現重複 inset；共用 edge-to-edge 政策之後又需補 eWeLink theme opt-out。
- ReVanced YouTube：原播放樣本的播放器父層 `y=136`、內層 `y=272`。本輪重開同一 Zorig 影片後為 `y=136`，尚未修改幾何行為；同一影片的 miniplayer→展開、全螢幕→直立均回到正常位置，但未重現原始失敗觸發，不能宣告黑邊修復。
- 音訊根因：已追到 provider 呼叫的 `args[0]` 與 Application/global host attribution 共用同一物件。舊遞迴原地轉換將它改成 guest，後續 currentOp／global 觀測轉為 guest，MediaPlayer 因 host UID／guest package 不匹配遭 `OpPlayAudio` 靜音。先前的正確 Application 快照發生在污染之前，不能反駁後續污染。
- 本輪實作：provider 入口改用 runtime state 的私有 root copy，僅轉換自有來源的 root；保留 `next`、token 與未知欄位，不修改原物件或 delegated chain。這是修正共同物件生命週期，沒有新增 ReVanced 音訊特例。
- 版本證據：官方比對顯示 Android 12 沒有所需 Builder copy，Android 13／14 的該複製路徑漏掉 `next`。因此不能假定 Builder 能跨版本完整保留資料；目前只有 Pixel 7 API 37 的三項真實 framework 測試通過，其他 Android 版本仍待實測。
- 自動化：最終候選的 666 項 unit tests（0 failure／error／skip）、lint、APK build 通過；Pixel 7 上重新執行三项真實 framework 測試通過。
- 輸出證據：系統 speaker 音量 4/25 時 output capture 非零，mean 約 −60.8 dB、max 約 −43 dB。Playback capture 為全零，但 app 可 opt out，不能據此判定無聲；手機 microphone 訊號偏低，尚未完成與影片播放／暫停的相關驗證。以上不取代完整原始流程與實際喇叭驗收。
- 新發現：`keyevent 127` 暴露 MediaSession targeted dispatch 把 guest caller 與實體 UID 11291 配對造成崩潰。已做精確 stub 修正並安裝；Pixel 7 同程序／音軌的暫停→續播通過，數位輸出亦呈現有聲→全零→有聲；它是額外系統入口問題，不是上述音訊污染根因。

結論：已定位並實作 provider 共用 attribution 污染修正，支持「共用邊界與驗收覆蓋不足」的判斷；完整產品驗收未完成。可靠度分數：97%（provider 污染鏈），不等同所有裝置／流程的修復成功率。驗證方式：在相同 provider→播放器順序確認原 global source 不變、player attribution 正確、AppOps 不靜音，再完成下列實機矩陣。

## 契約與責任

```text
guest SDK / theme / Activity lifecycle
                  ↓
GuestEdgeToEdgeCompat：政策與決策原因
                  ↓
framework decor → guest view hierarchy → Surface
                  ↓
實際內容位置、觸控、IME 與全螢幕結果

guest package / virtual UID（guest 內部）
                  ↓
實體系統入口：host package + 真實程序 UID
                  ↓
framework / native player → AppOps → 音訊輸出
```

### 視窗

- 政策依實際 Android SDK、guest target SDK、floating 與 guest theme opt-out 決定；不得依 AppTwin target 或 stub theme 猜測 guest 行為。
- 現有政策對 SDK 與 target 均達 35 的非 floating Activity 評估 enforcement；SDK 或 target 未達 36 時保留 opt-out 語意。讀取失敗保留現狀並留下原因，不能靜默宣稱已套用。
- AppInstrumentation 負責正確的 theme／生命週期順序；GuestEdgeToEdgeCompat 負責政策選擇。guest 仍可在 onCreate 後改變視窗，因此前置決策不是最終幾何證據。
- decor、guest layout 與 Surface 必須共同還原預期可見範圍。不可全域吞掉 insets、強迫全部 edge-to-edge，或依黑帶像素數新增固定補償；先定位重複套用、消費或位移的責任層。
- 影片長寬比產生的正常 letterbox 必須與額外 inset 分辨；不能以「沒有黑色」作為唯一判準。

### 身分

- `Context.getPackageName()` 等 guest 可見識別與 virtual IPC 保留 guest package／virtual UID；不可全域改成 host，以免破壞 GMS、provider 與其他隔離語意。
- 實體系統 AppOps／媒體服務需要相互匹配的 host package 與真實程序 UID。ContextFixer 與系統入口 adapter 負責此轉換，不得繞過 AppOps、偽造授權或把其他來源的 attribution chain 一律視為自己的身分。
- 必須分別驗證 Context attribution、Application/global attribution、operation package、自建 Context 及 native 建立路徑。某一入口正確不能代替其他入口。
- 系統／virtual provider 的 attribution 轉換是單次呼叫的資料，不得原地修改 Context、Application 或其他呼叫者共享的 source。只可複製並改寫屬於目前 host／guest 的 root；其他 principal 原樣保留，不遞迴重寫 `next`。
- 複製必須保留 runtime state 的全部非 static 欄位，包括 pid、token、tag、permissions、device、`next` 與未知欄位；root 和 root state 必須與來源分離。此處是 root 淺複製，共享的 `next` 只能讀，不能再改寫。
- 複製失敗或 framework schema 不相容必須明確失敗，不得 fallback 成原地 mutation。Android 12／13／14 的 Builder 差異要求真實 framework 測試，JVM fixture 不足以宣稱跨版相容。
- AOSP Android 16 的無 Context AudioTrack／MediaPlayer 優先透過 `myAttributionSource()` 取 Application attribution；AudioTrack 另使用 operation package。這是調查線索，實機版本與實際呼叫堆疊仍須核對。
- API 31 以上不可重新啟用既有、已知可能破壞 ART 結構的 legacy offset patches 來補音訊入口。新 adapter 必須以確定的版本／簽名及身分證據為前提。

## 有界診斷

診斷預設關閉，只記 metadata。不得記錄畫面文字、content description、Intent extras、媒體 URL、訊息、音訊內容、token 或 attribution tag。

| 開關／tag | 目前預算與用途 |
|---|---|
| `debug.apptwin.window_policy=1`／`GuestWindowPolicy` | 每程序最多 48 筆決策；每 Activity 最多 3 批、每程序最多 12 批幾何快照；每批在 0／500／2000／8000 ms 採樣 |
| 視窗快照 | 每次 traversal budget 2048 nodes、最多輸出 96 nodes、Surface 祖先最多 32 層；記 local／screen／window bounds、padding、margin、translation、scroll、insets、flags 與截斷狀態 |
| `debug.apptwin.audio_identity=1`／`GuestAudioIdentity` | 每程序最多 48 次身分快照；最多 12 次 trackPlayer 邊界樣本，每次 caller stack 最多 24 frames，並關聯回傳 player ID；另限 16 次 provider copy metadata 樣本 |

預算耗盡後不得靠無限重啟規避。每次採證應指定重現步驟與結束點；結束將開關設為 `0`。診斷不建立播放器、不修改媒體參數、不持續輪詢；停止／銷毀 Activity 後不得因採樣延長其存活。只有例外類型可進錯誤日誌，避免例外文字洩漏內容。

## 驗收矩陣

下列矩陣尚未全部完成；上節列出的部分測試通過，不會自動使其餘項目通過。每筆結果需附 APK／guest 版本、Android build、實體機型、初始狀態、操作、證據路徑及結果。

| 層級／場景 | Given／When | Then 與證據 |
|---|---|---|
| 政策 unit | SDK／target 邊界、floating、opt-out、讀取失敗 | 分支選擇符合契約；只驗政策，不代表 framework 結果 |
| 實體裝置 | Pixel 7 或 ASUS；記錄可用機型 | 至少一台核准實體裝置完成原始流程；另一台未跑須標示。Emulator 僅補充 |
| ReVanced 直立播放 | 同一失敗影片／入口，冷啟動並播放 | 畫面與 Surface 幾何符合播放器內容；重現原錯誤狀態後不再額外下移，不能只比首頁 |
| 影片切換 | 直立→全螢幕→旋轉→返回直立 | Surface 與觸控對齊，沒有新遮擋／裁切／重複 inset；保留切換前後快照 |
| IME 與導航 | Facebook Lite／eWeLink／ReVanced，開關輸入框與鍵盤 | 輸入欄、底部導航可見可點；不得送出文字或操作真實裝置開關 |
| theme opt-out | eWeLink 與可控 floating／opt-out fixture | 保留合法 opt-out、對話框與 navigation bar 行為；不以一般 Activity 成功替代 |
| Provider 複製與 framework | 同一 global source 先經 provider 再建立 player；帶 next／token／未知欄位 fixture | 原 source 不變、root 分離、chain／欄位保留、非自有來源不改；Pixel 7 API 37 三項 framework 測試已有通過，Android 12／13／14 與 ASUS 仍待測 |
| MediaSession targeted dispatch | 安裝最新候選，`keyevent 127` 暫停並續播 | caller package／UID 匹配且不崩潰；Pixel 7 最終候選已通過媒體鍵暫停／續播，未驗證其他 Android 版本 |
| 真實音訊路徑 | guest 的 Context／無 Context framework player 與實際 ReVanced player | caller stack／player ID 對應 AppOps；host package／UID 一致，started 且無 opPlayAudio mute，並取得實際出聲證據 |
| 生命週期 | 播放→暫停→續播→背景返回→關閉再開 | 每次播放恢復有聲；暫停停止輸出，沒有重複音軌、殘留播放或重啟後才恢復的假成功 |
| 跨 app 隔離 | Facebook Lite、eWeLink、ReVanced；適用時既有 GMS／LINE fixture | guest package 維持原身分；既有登入／GMS 路由及 UI 無回歸。不得發消息、刪資料或改遠端設定 |
| 關閉診斷 | 開關改回 0，重跑最小播放操作 | 正常功能不依賴診斷；不再產生新採樣，沒有持續背景工作 |

原始失敗流程至少連續三輪，含冷啟動、切換後及重開樣本；任何一輪重現即不通過。無法播放相同影片時先記錄缺口，不能把另一影片的成功當成原問題已消失。實際出聲需要可核對的實機證據；`state:started`、正確 attribution 或無 mute log 仍各自不足。

## 剩餘邊界

- `ContextFixer.fixAttributionSource` 及 Telecom 使用的遞迴 mutation 尚未在本輪全面改寫；可能存在類似 shared state／delegation 風險。未證明它們造成此次無聲，不得把候選風險描述成已驗證漏洞。
- Provider `getType` 等不同參數形狀可能帶 attribution；目前已修入口不代表所有 method／Android 版本都被覆蓋。需按真實簽名另做盤點與測試，不能泛化改寫任意 `args[0]`。
- API 37 的 runtime state 測試不能保證其他版本／OEM 的 hidden 欄位可存取；反射失敗應報缺口，不降低複製契約。
- 此輪持續驗收須補 MediaSession 新候選、miniplayer／全螢幕、輸出與播放事件相關性及跨 app 項目。黑邊目前仍屬未穩定重現／未證明修復。

## 失敗與完工判準

- 黑帶來源未定位、只在重啟後正常、未完成原始流程，均只能回報「已定位部分路徑／已實作，尚待實機驗證」。
- UID／package mismatch、OpPlayAudio mute、Surface 裁切／遮擋、opt-out／IME 回歸、guest identity 被 host 取代，任一項即停止擴大修補並保留失敗證據。
- 反射失敗、未知 framework 簽名或診斷截斷須列為缺口，不可補猜或採取全域放寬。
- 不可為驗收刪除 app 資料、重建帳號、送出消息、切換 eWeLink 真實設備或建立雲端工作。需要擴大此邊界時另行取得授權。
- 完工記錄逐項區分已驗證、失敗、未驗證；只對實際通過的裝置／版本／流程宣稱結果。現階段本文件沒有宣告上述矩陣通過。

官方呼叫鏈參考：[AudioTrack](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/AudioTrack.java)、[MediaPlayer](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/MediaPlayer.java)、[AttributionSource](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/content/AttributionSource.java)、[ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/ActivityThread.java)。
