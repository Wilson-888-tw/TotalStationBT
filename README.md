# 全站儀藍牙接收 App（TotalStationBT）

Android 應用程式，透過藍牙 SPP（Serial Port Profile）接收全站儀測量資料，自動解析並匯出 CSV。

---

## 支援格式

| 品牌 | 格式 | 說明 |
|------|------|------|
| Leica | GSI-8 / GSI-16 | 最常見格式，支援角度/距離/坐標 |
| Sokkia / Pentax | SDR33 | 量測記錄 (07TR) 及坐標記錄 (13CO) |
| Nikon | RAW | SS/SP 量測、CO 坐標記錄 |
| Topcon | GTS | HA=/VA=/SD= 鍵值格式 |
| 通用 | - | 逗號或空白分隔的數值 |

---

## 專案結構

```
app/src/main/
├── java/com/survey/totalstationbt/
│   ├── bluetooth/
│   │   └── BluetoothSerialService.kt   ← 核心 SPP 連線服務
│   ├── parser/
│   │   └── TotalStationParser.kt       ← 多格式資料解析器
│   ├── model/
│   │   └── Models.kt                   ← 資料模型
│   ├── ui/
│   │   ├── MainActivity.kt             ← 主畫面
│   │   ├── MainViewModel.kt            ← 業務邏輯
│   │   └── DataAdapter.kt             ← RecyclerView 適配器
│   └── utils/
│       └── FileExporter.kt             ← CSV / 原始檔匯出
└── res/
    ├── layout/activity_main.xml
    ├── layout/item_data_row.xml
    └── values/{colors,strings,themes}.xml
```

---

## 環境需求

- **Android Studio** Hedgehog 以上
- **minSdk 26**（Android 8.0）
- **targetSdk 34**（Android 14）
- Kotlin 1.9.x

---

## 使用步驟

### 1. 全站儀端設定
1. 開啟全站儀藍牙功能（各品牌操作不同，參閱儀器手冊）
2. 設定通訊參數：
   - **波特率**：9600 或 38400（與儀器一致）
   - **資料位**：8-N-1
3. 將儀器設為「資料輸出」或「連線」模式

### 2. 手機配對
1. 前往系統設定 → 藍牙 → 掃描裝置
2. 找到全站儀（通常顯示型號名稱）→ 配對
3. 配對碼通常為 **0000** 或 **1234**

### 3. App 操作
1. 開啟 App，點擊右下角 **藍牙圖示** FAB
2. 從配對裝置清單選擇全站儀
3. 在儀器端執行量測或傳送資料
4. 資料即時顯示於畫面（綠色 = 解析成功，紅色 = 無法解析）
5. 點擊 **匯出 CSV** → 存至 Documents/TotalStationBT/

---

## 匯出欄位（CSV）

```
點號, N(縱坐標), E(橫坐標), H(高程),
水平角, 垂直角, 斜距, 水平距, 垂直距, 格式, 時間
```

---

## 常見問題

**Q: 連線失敗**
- 確認藍牙已配對（系統層級）
- 確認儀器已進入通訊模式
- 部分儀器需先在儀器端「接受連線」

**Q: 資料顯示紅色（無法解析）**
- 使用「原始檔」匯出，確認原始格式
- 可自行擴充 `TotalStationParser.kt` 新增解析邏輯

**Q: Android 12+ 連線問題**
- 確認已授予 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT 權限

---

## 後續擴充建議

- [ ] 新增 Room 資料庫持久化
- [ ] 地圖顯示測量點（MapBox / OSM）
- [ ] 支援 BLE（藍牙 5.0 全站儀）
- [ ] 自訂格式設定畫面
- [ ] 匯出 DXF / Shapefile
