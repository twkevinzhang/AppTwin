# 分身 LINE 電池最佳化設定適配

日期：2026-09-18。範圍為系統設定跳轉與真實豁免狀態查詢，不包含 LINE 背景收訊修復。

## 觀察事實

### 環境與安裝

- 實體 Pixel 7，Android API 37。
- AppTwin `runtimeProbeDebug`，target SDK 28。
- 使用 `adb install -r -t` 更新 APK，保留既有分身與應用程式資料。
- 驗證 APK SHA-256：`ac03e1bfffc4f46e069c82052ad90c8f52398d0b63f38c2bc8e19f07390e5276`。

### 原始問題

- **Given：**分身 LINE 顯示將電池用量變更為無限制的提醒；AppTwin 尚未取得電池最佳化豁免，Manifest 未宣告 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- **When：**使用者按 LINE 提醒中的「變更」。
- **Then（錯誤）：**LINE 發出 `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，系統 Activity 短暫啟動後立即返回，未呈現可操作的授權對話框；下次開啟 LINE 仍提醒。
- **Then（預期）：**顯示以 AppTwin 為對象的系統授權對話框；拒絕時仍回報未豁免，允許時讓分身讀取 AppTwin 的真實豁免狀態。

### 實機驗收結果

| 操作 | 系統狀態與 LINE 結果 |
| --- | --- |
| 分身 LINE 按「變更」 | 系統對話框標示 **AppTwin** |
| 拒絕系統授權後重開 LINE | Host 未在 allowlist，LINE 仍顯示提醒 |
| 允許系統授權後重開 LINE | User whitelist 加入 `org.apptwin`，LINE 首頁不再顯示提醒 |
| 執行 `adb shell dumpsys deviceidle whitelist -org.apptwin` 模擬系統撤銷，再重開 LINE | LINE 再次顯示提醒 |
| 再由 LINE 按「變更」，於系統對話框允許 | Host whitelist 恢復 |
| 重新允許後執行 `adb shell am force-stop org.apptwin`，經 AppTwin UI 再開分身 LINE | 直接檢視實機畫面為 LINE 首頁且無電池提醒，accessibility 顯示主頁分頁勾選，host whitelist 為 true |

最終系統設定保留為允許 AppTwin 電池最佳化豁免。驗收中的 whitelist 撤銷是測試操作，不是產品自動行為。

### 本機驗證

- `:virtual-runtime:testDebugUnitTest`：669 個測試通過。
- `:app:testRuntimeProbeDebugUnitTest`：146 個測試通過；合計 815 個，無 failure、error 或 skipped。
- `:app:assembleRuntimeProbeDebug` 通過。
- `:virtual-runtime:lintDebug` 與 `:app:lintRuntimeProbeDebug` 通過。Runtime 報告 98 warnings 與 2 baseline hints，既有 baseline 過濾 13 errors／358 warnings；App 報告 21 warnings。沒有新增 suppression，也沒有電池權限或新 hook 的 lint finding。
- `git diff --check` 通過。

## 實作邊界

- [App Manifest](../../app/src/main/AndroidManifest.xml) 宣告 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。沿用既有設定 Intent 的 host package 路由，未擴大設定入口改寫。
- [InvocationStubManager](../../virtual-runtime/src/main/java/com/lody/virtual/client/core/InvocationStubManager.java) 僅在 API 23 以上的 guest process 註冊 DeviceIdle hook。
- [DeviceIdleControllerStub](../../virtual-runtime/src/main/java/com/lody/virtual/client/hook/proxies/deviceidle/DeviceIdleControllerStub.java) 僅攔截 `isPowerSaveWhitelistApp` 與 `isPowerSaveWhitelistExceptIdleApp` 兩個唯讀查詢。
- [SelfBatteryWhitelistQuery](../../virtual-runtime/src/main/java/com/lody/virtual/client/hook/proxies/deviceidle/SelfBatteryWhitelistQuery.java) 僅把目前 guest 對自身 package 的查詢映射到 host；host、其他 package、null 或缺少 identity 的查詢保留原值。真實系統 true／false 原樣透傳。
- [回歸測試](../../virtual-runtime/src/test/java/com/lody/virtual/client/hook/proxies/deviceidle/SelfBatteryWhitelistQueryTest.java) 涵蓋上述查詢範圍與 true／false。
- 未自動寫入 whitelist、未新增 UI、未清除資料、未傳送 LINE 訊息。

## 推論

原始系統授權入口已有 host 路由，但缺少 host 權限；guest 對自身 package 的電池豁免查詢也需要對齊實際承載 process 的 host。補上這兩層後，拒絕、允許與撤銷均呈現相符的 LINE 行為，支持設定入口與狀態映射已在這台 Pixel 7 生效。

此設定作用於 AppTwin 宿主，不是各分身獨立的電池開關。Hook 在 `AppTwinApplication.attachBaseContext → VirtualCore.startup → init/injectAll` 執行；目前未見此路徑提前快取 DeviceIdle service 的證據。Pixel 7 的真實狀態切換提供此裝置 call path 生效的直接證據，但不能推廣為所有 Android API／OEM 均已驗證。

## 結論與驗證方式

設定跳轉、拒絕後仍提醒、允許後不再提醒、撤銷後再次提醒及最終冷啟動，已通過上述 Pixel 7 實機流程。這是實機操作、系統狀態查詢與畫面檢視的驗收紀錄，不是完整自動化 E2E suite；ASUS 與其他 Android API 未實測。

可靠度分數：97%（限定本次 Pixel 7 已完成的設定與查詢流程）。驗證方式為重跑上述實機操作並核對 host whitelist 與提醒行為；其他裝置需重跑拒絕、允許、撤銷及冷啟動同一組操作，才可擴大此結論。

本次沒有驗證 LINE 訊息 ingress、背景入庫或通知顯示，**不構成 LINE 背景通知已修復的證據**。
