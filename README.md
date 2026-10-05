# GPS 模擬器（Android）

在 Android 裝置上模擬 GPS 位置的 App，支援背景持續模擬、常用座標與路徑步行模擬。

## 功能

| 需求 | 實作方式 |
| --- | --- |
| 1. 於 Android 裝置運作 | Kotlin 原生 App，支援 Android 8.0（API 26）以上，地圖使用 OpenStreetMap（osmdroid，免 API Key） |
| 2. 背景運作時維持 GPS 模擬 | `MockLocationService` 前景服務（`foregroundServiceType="location"`）+ Partial WakeLock，App 切到背景、螢幕關閉仍持續推送座標；服務被系統回收後會依 `START_STICKY` 自動接續上次模擬；通知列可暫停／停止 |
| 3. 儲存與呼叫常用座標 | 「收藏」把目標點存為常用座標；「常用座標」清單可瞬移、設為目標、加入路徑點、重新命名、刪除 |
| 4. 模擬步行（路徑＋速度） | 點擊地圖建立多點路徑，速度 0.5–120 km/h（滑桿、快捷鍵或自訂輸入），支援單程／循環／來回；模擬中可即時調速、暫停、繼續；路徑可儲存與載入 |

模擬位置會同時注入系統 `gps`、`network` 提供者，以及 Google Play 服務的 Fused Location，大多數 App 都能讀到。

## 初次設定

1. 安裝 APK 並開啟，允許「位置」與「通知」權限。
2. 啟用開發者選項：**設定 → 關於手機 → 連點「版本號碼」7 次**。
3. **設定 → 系統 → 開發者選項 → 選取模擬位置應用程式 → GPS 模擬器**。
4.（建議）在 App 右上「設定 → 電池最佳化白名單」把本 App 加入白名單，避免部分廠牌（小米、OPPO、華為等）在背景強制關閉服務。

## 使用方式

### 定點模擬
- 點擊地圖或「輸入座標」（格式 `緯度, 經度`，可直接貼上 Google 地圖複製的座標）設定目標點，按「開始模擬」。
- 模擬中選擇新目標後按「移動到目標」即可瞬移。
- 長按地圖可快速：設為目標、加入路徑點、加入常用座標、立即瞬移。

### 路徑步行
- 切換到「路徑步行」，依序點擊地圖新增路徑點（點擊已存在的路徑點可刪除，「復原」移除最後一點）。
- 設定速度（步行 5／快走 7／慢跑 10／騎車 18／開車 50 km/h 或自訂），選擇單程／循環／來回後按「開始模擬」。
- 模擬時每 0.5 秒更新一次位置，並帶有速度與行進方向（bearing）。

## 建置

需求：JDK 17、Android SDK（compileSdk 35）。

```bash
./gradlew assembleDebug          # 產生 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 路徑計算單元測試
```

也可直接到 GitHub Actions（Android CI）下載自動建置的 APK（artifact：`gps-simulator-apk`）。

## 程式結構

```
app/src/main/java/com/gpssim/app/
├── MainActivity.kt          地圖 UI、模式切換、常用座標／路徑對話框
├── MockLocationService.kt   前景服務：背景持續推送模擬座標、通知控制、狀態 StateFlow
├── MockLocationProvider.kt  注入 GPS / Network / Fused 模擬位置
├── RouteWalker.kt           依速度沿路徑內插位置與方向（單程／循環／來回）
├── GeoUtils.kt              距離、方位角、內插
├── AppStore.kt              常用座標、常用路徑、偏好設定與模擬工作紀錄
└── Models.kt                資料模型
```

## 注意事項

- 部分 App 會透過 `Location.isMock()` 偵測模擬位置，本 App 無法也不嘗試規避。
- 請遵守各 App／遊戲的使用條款，僅於開發測試或合法用途下使用。
