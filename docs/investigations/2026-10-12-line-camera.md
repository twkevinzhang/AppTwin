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

已提出新增 scope：host/LINE legacy WRITE grant、方向性 native/Provider/AppOps write 控制、兩個臨時 Space 只寫本次 fixture 檔案後清理、原 LINE only-preview 验收。此 scope 尚待使用者答覆。當前 WRITE 未改，CAMERA=true/global1；property 恢復原值，所有 collectors 結束。暫存 APK／靜態解碼資料已由主代理清理。
