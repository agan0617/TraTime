# 台鐵時刻（TraTime）

查台鐵兩站之間、今天某個時間以後班次的 Android App。

## 功能

- **起站／終站**：點站名挑選，可打字篩選（「台北」「臺北」都找得到）；中間的 ⇄ 對調起訖站
- **出發時間**：點時間改，**長按回到現在**；沒手動改過時，每次打開 App 都跟著現在
- 預設 **汐止 → 臺北、現在**，打開就列出今天這個時間之後的班次
- 每班顯示：出發 → 抵達、行駛時間、往哪裡、車種（自強系紅、莒光／復興橘、區間藍）、車次；停駛的會標出來並變淡

## 資料來源

交通部 [TDX 運輸資料流通服務](https://tdx.transportdata.tw/) 的台鐵每日時刻表（`/v3/Rail/TRA/DailyTrainTimetable/OD/{起}/to/{訖}/{日期}`）。

- **不帶金鑰**呼叫，TDX 對免金鑰有每日次數上限。同一天同一段查詢會快取 2 小時，連不上時先用舊的
- 額度用完時 App 會顯示「TDX 免金鑰的每日查詢次數用完了」
- 車站清單打包在 `app/src/main/assets/stations.json`（從 TDX `/v3/Rail/TRA/Station` 取的站碼與站名），不用每次連線

## 安裝

到 [Releases](../../releases) 下載最新的 `.apk`，在手機上打開安裝。

## 建置

需要 Android Studio 內建的 JDK（`C:\Program Files\Android\Android Studio\jbr`）與 SDK 34。

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew assembleRelease
```

產出 `app/build/outputs/apk/release/app-release.apk`（用這台電腦的 debug 金鑰簽章）。改版時改 `app/build.gradle` 的 `versionCode`（+1）與 `versionName`，Release tag 用 `v` + versionName。

⚠️ 從 Claude 桌面版開的 shell 建置時，`%TEMP%` 會被 MSIX 沙盒導走，Gradle 會報「Unable to establish loopback connection」。把 `TEMP`／`TMP` 設到 `D:\Temp\jdk`，並加 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Temp\jdk -Djava.io.tmpdir=D:\Temp\jdk`。
