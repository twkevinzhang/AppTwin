# cloned LINE 麥克風入口 crash

## 問題與驗收

Given：ASUS Zenfone7/API31，AppTwin v1015，openinfo/user2 的 LINE 已授權 RECORD_AUDIO。When：在「張震龍 Kevin」聊天點右下角語音訊息。Then：LINE guest 主執行緒因 SecurityException 崩潰；預期正常開啟錄音面板，取消後可再次開啟。

使用者明確授權 install-r 保留資料、指定聊天原入口、必要時短暫錄音後取消及清理。不播放、不傳送語音，保持原權限與背景設定。主代理唯一 ADB/build owner，子代理僅兩個 Telecom 檔案實作。

## 根因與修正

17:09:20 PID31059 的真實堆疊為 `TelephonyManager.getCallState -> TelecomManager.getCallState -> ITelecomService.getCallStateUsingPackage`；AppOps checkPackage 拒絕 `jp.naver.line.android` 不屬於 host UID10305。主 AppTwin process 仍存活，死亡的是 LINE guest。

TelecomManagerStub 只註冊四個 PhoneAccount hooks，漏掉 getCallStateUsingPackage，因此 fallback 直接送出 guest package。新 exact proxy 只改欄位0 caller package 為 host；欄位1 attributionTag、真實通話狀態和系統例外保留。不改錄音權限、不回傳假 idle、不吞 SecurityException，也不泛化其餘 Telecom 方法。

## 本機與 ASUS 驗收

runtime 770＋app184＝954 tests，failures/errors0；v1016 `0.1.16-telecom-call-state` APK build成功、install-r Success。新增回歸覆蓋實際參數轉換、tag與guest名稱相同仍保留、狀態2原樣回傳，以及同一平台SecurityException cause透傳。

實機原聊天與 `chat_ui_send_button_image` content description「語音訊息」精確確認後點擊，顯示 `voice_record_animation_view`、`record_background_view`、`record_icon_image_view` 及「取消」。按取消返回原入口；再次開啟面板並取消成功。鍵盤改變入口bounds時重新取得當下位置，未把尚未定位的重試算成通過。驗收前後同一guest PID18312存活；17:23後驗收窗口無新增Java/native crash。未按record start，沒有實際錄音、播放或傳送，沒有語音暫存需要刪除。未讀取聊天文字或保存影像／音訊；只存resource ID／bounds及process／crash metadata。

## 其他觀察與收尾

新版啟動期間曾出現 microG process的 SERVICE_INFO_RESPONSE broadcast ANR，以及LINE PID16100在perfetto_hprof thread的libc read+8 SIGILL；相同native PC/thread在此次改碼前14:20／14:55的既有紀錄中亦出現（原始crash buffer 14:55 LINE同PC）。這些是不同路徑，尚未定位或修復，不宣稱本次已消除所有啟動異常。沒有以新增hook吞例外或native workaround掩蓋，後續正常啟動後才驗收原麥克風。

結束回Home，CAMERA／RECORD_AUDIO／READ／WRITE durable grants皆true，背景總設定1，diagnostic property空值；LINE base與split APK hashes與上一輪一致，host/device本輪UI helper刪除。沒有雲端或持續計費資源。

結論：本次麥克風點擊的Telecom身分不符crash已修復，ASUS原入口開啟／取消／再次開啟均通過。可靠度分數：99%。驗證邊界：本次驗收語音面板而非錄製／傳送音訊；其他啟動ANR/native crash未解決。證據見 `evidence/2026-10-13-line-microphone/`。
