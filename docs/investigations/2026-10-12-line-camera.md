# cloned LINE 聊天相機的存取權限 toast

## 已確認的範圍與授權

Given：實體 ASUS/API31，openinfo/user2 cloned LINE 的 CAMERA 與 host CAMERA 已允許。When：在使用者指定聊天點左下相機。Then：目前只顯示約一秒的存取權限 toast，沒有相機預覽；預期可開啟預覽。

使用者批准：原 LINE UI 重現、只預覽不拍攝／傳送、短暫切換並恢復 exact LINE CAMERA（停止其 process）、必要 install-r；結束恢復授權與背景設定。不讀取聊天或私人媒體、不操作雲端。使用者指定聊天僅供導航，不保存內容。裝置執行者僅 device_investigation；主代理統一 build。

## 觀察事實

- host CAMERA、RECORD_AUDIO、READ_EXTERNAL_STORAGE 已授權；LINE virtual explicit 同三項皆 true。host WRITE_EXTERNAL_STORAGE=false，AppTwin manifest maxSdkVersion=29；LINE manifest 宣告 WRITE_EXTERNAL_STORAGE，targetSdk36。Space explicit 值不等於 VPM effective decision，尚須檢查實際回傳。
- Camera sensor privacy 關閉（沒有阻擋相機）。原入口按鈕 enabled/clickable。
- 重新啟動 LINE 後，CAMERA=true 原入口仍出現精確權限 toast（TYPE_NOTIFICATION_STATE_CHANGED）。因此舊 permission cache 不是足以解釋本次失敗的原因。
- LINE 靜態程序資源有對應 generic access-error string；它不是 CAMERA 專用訊息。僅檢查 APK，沒有拷貝聊天 data 或影像。臨時 APK／解碼資料位於 /tmp，結束由主代理刪除，不進 repo。

## 取證候選

v1008 只增加 opt-in 的 debug permission metadata，沒有改決策。條件為 DEBUG + exact LINE/user2 + 手動 property=1；每 process 最多96events，firstevent後120秒harddeadline，toggle不重置。固定 permission／stage，僅 decision/PID/user，不記錄 arguments、tokens、Intent、聊天或影像。預設關閉，實測結束恢復 property 原值。

## 實際權限判定

v1008 的相機點擊窗口取得 AM decision：READ_EXTERNAL_STORAGE=0、WRITE_EXTERNAL_STORAGE=-1、CAMERA=0，同時捕獲使用者描述的精確 toast。原入口的拒絕點是 legacy WRITE 檢查，沒有 CAMERA 拒絕證據；native camera 尚未開啟。新 process 重現排除僅舊 permission cache 的解釋。

結論：host manifest 的 WRITE maxSdk29，使 API31 上的 host effective WRITE 被拒絕，LINE 在相機入口也檢查該項，所以 CAMERA 已授權仍停在入口。可靠度分數：95%。驗證方式：支援與隔離有效 legacy write 後，permission trace 必須允許所需項目，原相機入口顯示 preview；再依 CAMERA deny／restore 驗證拒絕與重試。

不能只新增 host 寫入授權：現有 native whitelist 依 READ grant 不區分讀寫；Provider 媒體 mutation 也共用 READ gate；AppOps op60 缺 write gate。新增能力前需補齊 shared write 的 Space 邊界，private virtual storage 仍可讀寫。

已提出新增 scope：host/LINE legacy WRITE grant、方向性 native/Provider/AppOps write 控制、兩個臨時 Space 只寫本次 fixture 檔案後清理、原 LINE only-preview 验收。使用者已明確回覆「允許擴充本輪修正」。當前 WRITE 未改，CAMERA=true/global1；property 恢復原值，所有 collectors 結束。暫存 APK／靜態解碼資料已由主代理清理。

## 寫入修正計畫與 readiness

已具備：ASUS awake/unlocked、指定 LINE 帳號與入口、ADB、Gradle/NDK/host clang；原 CAMERA/READ=true、WRITE=false、global1、diagnostic property 原空值均已記錄。Codex 可自行準備：新版 maxSdk32 manifest、真正 host WRITE grant、LINE exact Space WRITE grant、獨立 fixture/兩個 temporary Spaces/owned media。需要使用者提供項目：目前無；已授權安裝確認需要時使用裝置 PIN，不記錄該值。

修正接線：WRITE 加入 Space controlled 與 AppOps60；Provider 区分读写，selected-read URI 不能授予 write；native 每个 shared path 的读与写独立，private/virtual roots 保留自有读写。SDK≥30 平台 scoped storage 仍自行约束，不能把 WRITE grant 說成任意寫入能力。host WRITE 與 exact LINE WRITE 只在本機候選通過後由唯一 device owner 變更。

Fixture 驗收：A READ=true/WRITE=false → A WRITE=true → A WRITE 撤銷 → B READ=true/WRITE=false 隔離 → A READ=false/WRITE=true → A 讀寫恢復；只對 owned shared media/file 做 Provider/direct mutation，宿主核對實際 bytes/hash/existence，不能把 redirected private write 成功誤判為 shared write。每 phase strict Started、exact fixture foreground、返回；原媒體 five-phase 再驗證沒有破壞 READ gate。

原 LINE 驗收：WRITE 必要授權後指定聊天 camera entry 可 preview，CAMERA deny 阻擋，恢復 CAMERA 後可 retry；不拍攝、不傳送、不保存影像。結束刪除本次 fixture、Space、檔案、debug artifacts，保留獲批准的新 WRITE，恢復 CAMERA=true/global1/property 原值。主代理唯一 build owner，device_investigation 唯一裝置執行者。

## 本機整合

- Host manifest WRITE maxSdkVersion 改為32；SDK≤32 的有效WRITE新增 Space控制與AppOps60，既有媒體setter會重啟exact package以更新native capabilities。
- Legacy Provider 區分讀／寫，selected-read URI 不能授予mutation；未知provider方法要求讀寫，已知readonly接口保留讀取。
- Legacy native shared path read/write獨立；相對dirfd、symlinkalias與fd操作核對最終實體路徑；O_PATH僅metadata。所需hooks未ready則停止guest，不能帶不受控的whitelist啟動。
- SDK≥33不新增legacyWRITE要求，保留原流程與平台最後判定；本輪不宣稱建立了modernWRITE能力模型。無sharedrules時保持舊native路徑與hook集合，避免新版本回歸。
- 本機 runtime761＋app184=945 tests零failure；host C++ SharedMediaPolicy regression通過；v1009 app/test/fixture APK編譯成功；非作者review確認pointer-return、unknownProvider與SDK回歸已修正，無剩餘已確認blocker。
- 現有libc interception不是OS級sandbox，未聲稱能阻止native直接syscall。實機必須確認一般libc/Provider路徑的實際讀寫與cleanup，unit/ACK不取代E2E。

v1009 已更新ASUS；host WRITE實際grant已取得（使用者已授權），fixture安裝以PM與APKhash確認終態成功。共享寫入與LINE相機實機驗收尚在執行，不能宣稱已修復。

## ARM64 hook 崩潰取證與修正

v1009／v1010 的第一個寫入 phase 未進 Activity。補上 exact fixture PID 與固定 lifecycle stages 後，確認 PID19654 在 `onCreate` 前 SIGILL，fault PC 位於 ASUS libc `open+0`（offset `0x5d7ac`），而不是 Activity resume 判定錯誤。僅保留程式 module／PC／symbol metadata，未讀取記憶體或私人內容；每輪 owned Space、MediaStore rows 與檔案均清零。

系統 ELF 顯示 `creat` offset `0x5d79c`、長度16，下一個 `open` 正好位於其後。既有 A64 far hook 在 entry 位址為4 mod8時加入 NOP，寫入20 bytes；新增 `creat` hook 因此覆蓋 `open` 的首指令，與 fault PC 吻合。ASUS 的47個 required hook targets 皆至少16 bytes。

v1011 將 far jump 固定為16 bytes、4指令 relocation；透過 `memcpy` 寫 literal，entry mprotect／cache flush 同為16。實際 encoder 的 host regression涵蓋0／4 mod8及相鄰指令 sentinel，測試通過；非作者 review 無確認 blocker。所有方向性權限檢查與 ready ACK 保留。原因可靠度分數：99%；修正效果仍須實機六階段寫入與原 LINE 相機預覽驗證。

## 啟動與共享目錄回歸

v1011 不再 SIGILL，但第二階段遇到舊 process orphan cleanup 刪除新 task；刪除發生於新 Activity 尚未 `onActivityCreated` 的窗口，既有 attached-owner 保護無法命中。v1012 補上 server prepared launch 的短 fence：exact physical task／launch ID／user／guest／stub／current generation／pending registry 全部成立，且準備時間不足5秒才保留；不續期，接管、取消、死亡、stop 或 expiry 都解除。ASUS v1012／v1013 的前兩階段 strict launch 已通過。本機 runtime763＋app184=947零失敗，非作者審查無確認 blocker。

接著 native relative write 在兩種授權下均回報 `relative-open` errno2。v1013 有界路由 metadata 證明 parent dirfd 為 private-external、沒有 shared rule match，而 absolute child 寫入 host 檔案成功。實際程式原因是 `SandboxFs::add_keep_item` 未初始化 `is_folder`：尾斜線 keep rule 可匹配 child，卻不能穩定匹配去掉斜線的 exact parent。v1014 初始化该旗標；actual SandboxFs／Path host regression 覆蓋 exact parent、child、相鄰目錄與自有檔案 dirfd，加上方向性權限矩陣，測試通過。未修改 fixture 斷言、permission gate、ACK 或 timeout；實機完整讀寫與 LINE 流程仍待驗證。

## v1014 實機讀寫驗收

ASUS SharedWrite 六階段嚴格 PASS，51.641秒：A唯讀、A允許寫入、B仍拒絕、A撤銷、A無讀取但可寫、A恢復。宿主實際 bytes／hash／existence 斷言通過，包含 Provider、絕對與相對 dirfd 寫入、truncate／rename／delete／create，以及 private／virtual 寫入；未用 redirected write 代替共享檔案驗證。原媒體 READ 五階段亦嚴格 PASS，43.958秒。詳見同目錄 evidence 的 v1014 summaries 與 instrumentation。

這些結果只證明本輪 API31 的一般 Provider／libc 路徑与 Space 權限控制，沒有擴大成 SDK≥33 或 native direct-syscall 安全保證。LINE 原入口預覽與 CAMERA deny／restore 仍在驗收；尚未宣稱相機修復完成。

## LINE 的 maxSdk 宣告缺口

v1014 的真實 AppTwin WRITE 權限頁只有其他已宣告 app，沒有 LINE；runtime setter 的 requested-permission guard 因此也不能授權。重新只讀 APK manifest，確認 LINE 的 `uses-permission android.permission.WRITE_EXTERNAL_STORAGE` 自身亦帶 `maxSdkVersion=29`；API31 的 OS parser 把它從 requestedPermissions 過濾，卻不影響 LINE 執行入口的 legacy WRITE 檢查。先前 host manifest 的 maxSdk29 與此 guest 宣告過濾是兩個必要缺口，不能只改 host grant 宣稱完成。

後續候選只在 SDK≤32、實際 APK package 身分吻合、直接 uses-permission 精確宣告 WRITE 且有效 maxSdk 小於目前 SDK 時，恢復 virtual requested declaration；不自動 grant，既有 installed/requested、host 與 Space gate 均保留。已安裝 package 的驗證重建只同步該項權限，避免需重建 Space 或清除資料。fixture 同樣使用 maxSdk29 原始宣告，六階段驗收會覆蓋真正的 parser 相容性路徑。

v1015 runtime768＋app184=952零失敗，helper／cache規則非作者審查無確認 blocker。ASUS 使用 maxSdk29 fixture 的六階段 SharedWrite 嚴格 PASS，46.893秒，證明 binary XML 解析與 virtual requested／grant 接線有效；並非只測純 predicate。LINE 舊 cache 是否正常更新與原相機流程仍在驗收。

## 原 LINE 流程的實際進展與硬體障礙

v1015 原 READ 五階段再次嚴格 PASS，43.14秒。真實 openinfo 權限頁已出現 LINE 的 WRITE，原值false；只操作該 LINE 開關後，WRITE／READ／CAMERA／RECORD_AUDIO 的持久值均true，沒有修改其他 app。這證明既有 package cache 升級接線有效。

指定聊天原相機按鈕已建立 CameraService client（host org.apptwin，exact guest PID），原權限 toast 未再出現，但不能將 CONNECT 當成預覽通過。後續確認前景是 `com.asus.motorservice` 的硬體提示「請解開華碩背蓋的卡榫後，再嘗試操作」，阻擋翻轉鏡頭。CAMERA 已由真實 UI 關閉再恢復true，未手動 kill；拒絕輪只取得 explicit=false／active clients=0，尚無足夠原入口拒絕決策證據，不能宣稱該輪完整通過。

結論：legacy WRITE 宣告與授權相容性、讀寫隔離已實作並由 ASUS 測試驗證；原 LINE 完整預覽與拒絕／恢復验收尚待使用者解除實體卡榫。軟體根因可靠度分數：99%；完整相機修復狀態仍為「已實作，尚待實際流程驗證」。驗證方式：解除卡榫後，在原入口確認 configured preview stream／持續 frame activity，補齊 CAMERA deny 決策與無額外手動重啟的 grant retry；不拍攝、傳送或保存預覽影像。

使用者解除卡榫後，原入口已取得三個 configured output streams；兩秒內 frameCount 4881→4944、capture requests 4884→4947，且 exact user2 LINE PID24362 相符，確認真正持續預覽。補充 CAMERA deny 亦取得 exact PID30906/user2 native CAMERA decision=-1、active clients=0。

恢復操作遭工具與外部狀態阻擋：legacy uiautomator 的 idle timeout 留下舊XML，因此改成 shell-only helper 直接取得 UiAutomation window root，不輸出聊天／XML、不重新啟動 AppTwin／guest。fresh root 隨後確認手機自動鎖屏（SystemUI／Keyguard secure）；當時 CAMERA 仍false，WRITE／READ仍true。所有 logger 與 debug property 已停止／復原，未以僅獲安裝授權的 PIN 擅自解鎖；等待使用者解鎖或明確允許沿用 PIN。恢復重試仍未完成，不能把已通過的預覽／deny 等同整輪完成。

2026-10-13 使用者已明確授權以先前提供的 PIN 自行解鎖驗收，後續本輪不再需要另問解鎖授權。然而重試時 ADB devices 與 mDNS 均為空，macOS USB inventory 亦沒有 ASUS／Android 裝置。單輪 start-server preflight 未 kill server、未新啟 tcpip／配對或掃描 LAN。尚未執行解鎖或修改授權；CAMERA 最後已知仍false，須手機 USB 資料線重連後優先透過真實 UI 恢復，再完成原入口重試。

## 2026-10-13 授權恢復

重連後由主代理接管 ADB；手機已解鎖。舊 UiAutomation 資訊與實際前景視窗不一致，NotificationShade 覆蓋 AppTwin，造成定位失敗。關閉通知欄後，以實際 MainActivity 權限頁確認 exact openinfo／LINE 的 CAMERA 開關，由false恢復true；沒有強制停止或重新安裝 AppTwin／LINE，也沒有修改其他 App 權限。

透過唯讀解析 durable runtime-permissions.bin，確認 user2／jp.naver.line.android 的 CAMERA、READ_EXTERNAL_STORAGE、WRITE_EXTERNAL_STORAGE、RECORD_AUDIO 均true。證據為 `v1015-final-restored-grants.json`。相機已不再留在測試的拒絕狀態。之後正常啟動 cloned LINE，僅讀取 resource ID／bounds 與指定對象是否命中的安全 metadata，未保存聊天或預覽影像；原入口重試前 USB 再度斷線，ADB列表與macOS USB枚舉再次皆空。最後的恢復預覽重試仍待穩定連線，不能宣稱完整驗收完成。
