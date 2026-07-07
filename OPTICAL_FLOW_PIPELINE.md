# Optical Flow Pipeline Trong App

Tài liệu này viết lại luồng xử lý của 2 thuật toán optical flow đang dùng trong app:

- KLT / Pyramidal Lucas-Kanade: `app/src/main/java/com/example/gnssandopticalflowapp/function/optical_flow/classes/KLT.kt`
- Farneback: `app/src/main/java/com/example/gnssandopticalflowapp/function/optical_flow/classes/Farneback.kt`

## 1. Luồng Chung Từ Camera Đến Optical Flow

Camera được cấu hình trong `CameraOpticalFlowFragment.kt` bằng `ImageAnalysis`:

```kotlin
ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setTargetRotation(rotation)
    .build()
    .also {
        it.setAnalyzer(cameraExecutor, ::analyzeCameraFrame)
    }
```

Ý nghĩa:

- Camera lấy frame liên tục.
- Mỗi frame mới nhất được đưa vào `analyzeCameraFrame`.
- Format frame là `RGBA_8888`, tức mỗi pixel có 4 kênh: R, G, B, A.

Sau đó app convert `ImageProxy` thành OpenCV `Mat`:

```kotlin
return Mat(height, width, CvType.CV_8UC4).apply {
    put(0, 0, rgbaBytes)
}
```

`Mat` là matrix ảnh của OpenCV:

- `height` là số dòng.
- `width` là số cột.
- `CV_8UC4` nghĩa là mỗi pixel có 4 kênh, mỗi kênh 8-bit unsigned.
- Với ảnh xám, app dùng `CV_8UC1`, tức 1 kênh độ sáng.

Frame sau khi convert và xoay đúng hướng sẽ đi vào:

```kotlin
val currentOutput = opticalFlow.run(frame)
```

Nếu đang chọn KLT thì chạy `KLT.run(frame)`. Nếu đang chọn Farneback thì chạy `Farneback.run(frame)`.

Output chung của 2 thuật toán là `OFOutput`:

```kotlin
class OFOutput {
    var ofFrame: Mat? = null
    var position: Point? = null
    var metrics: OpticalFlowMetrics? = null
}
```

Trong đó:

- `ofFrame`: frame đã được vẽ vector hoặc heatmap lên.
- `position`: vector chuyển động tổng hợp.
- `metrics`: thông số đo như số vector, dx/dy trung bình, confidence, FPS xử lý.

## 2. KLT / Pyramidal Lucas-Kanade

KLT trong app là sparse optical flow. Nghĩa là thuật toán không tính chuyển động cho toàn bộ pixel, mà chỉ chọn một số điểm đặc trưng dễ theo dõi, rồi theo dõi chúng qua frame tiếp theo.

### 2.1. Các Biến Chính

Trong `KLT.kt`:

```kotlin
private val prevGray: Mat = Mat()
private val currGray: Mat = Mat()
private val prevPts: MatOfPoint2f = MatOfPoint2f()
private val currPts: MatOfPoint2f = MatOfPoint2f()
private val status: MatOfByte = statusInit()
private val err: MatOfFloat = MatOfFloat()
```

Ý nghĩa:

| Biến | Vai trò |
|---|---|
| `prevGray` | Frame xám trước đó |
| `currGray` | Frame xám hiện tại |
| `prevPts` | Danh sách điểm start ở frame trước |
| `currPts` | Danh sách điểm end tìm được ở frame hiện tại |
| `status` | Điểm nào tracking thành công thì bằng `1`, thất bại thì bằng `0` |
| `err` | Sai số tracking của từng điểm |

### 2.2. Convert Frame Sang Gray

Trong `run(newFrame)`:

```kotlin
val currFrame = newFrame
Imgproc.cvtColor(currFrame, currGray, Imgproc.COLOR_RGBA2GRAY)
```

Frame camera ban đầu là RGBA. KLT chỉ cần độ sáng, nên app convert sang grayscale. Kết quả được lưu vào `currGray`.

### 2.3. Frame Đầu Tiên

Optical flow cần so sánh 2 frame. Vì vậy ở frame đầu tiên chưa tính được gì:

```kotlin
if (prevGray.empty()) {
    this.updatePoints(prevGray, currGray, prevPts)
    return buildOutput(...)
}
```

Trong `updatePoints`:

```kotlin
currGray.copyTo(prevGray)
val corners = MatOfPoint()
Imgproc.goodFeaturesToTrack(prevGray, corners, maxCorners, qualityLevel, minDistance)
prevPts.fromArray(*corners.toArray())
resetTracks(prevPts.toArray().size)
```

Luồng ở frame đầu:

```text
currGray
→ copy vào prevGray
→ goodFeaturesToTrack tìm các điểm góc/texture tốt
→ lưu các điểm này vào prevPts
```

`goodFeaturesToTrack` chưa tính optical flow. Nó chỉ chọn các điểm tốt để theo dõi, ví dụ góc cạnh, vạch đường, chi tiết có texture.

### 2.4. Frame Thứ 2 Trở Đi

Khi đã có `prevGray` và `prevPts`, app gọi Pyramidal Lucas-Kanade:

```kotlin
Video.calcOpticalFlowPyrLK(
    prevGray,
    currGray,
    prevPts,
    currPts,
    status,
    err,
    lkWinSize,
    lkMaxLevel,
    lkCriteria,
    0,
    0.001
)
```

Input:

- `prevGray`: ảnh xám frame trước.
- `currGray`: ảnh xám frame hiện tại.
- `prevPts`: các điểm start ở frame trước.

Output được ghi trực tiếp vào các biến truyền vào hàm:

- `currPts`: vị trí mới của các `prevPts` trên frame hiện tại.
- `status`: tracking thành công hay thất bại.
- `err`: sai số tracking.

Không có biến riêng để hứng pyramid. Hàm `calcOpticalFlowPyrLK` tự xây pyramid bên trong, dựa vào:

```kotlin
private val lkWinSize: Size = Size(21.0, 21.0)
private val lkMaxLevel: Int = 3
private val lkCriteria: TermCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 0.01)
```

Ý nghĩa:

- `lkWinSize = 21x21`: cửa sổ quanh mỗi điểm để so khớp.
- `lkMaxLevel = 3`: dùng pyramid nhiều tầng để bắt được chuyển động lớn hơn.
- `lkCriteria`: điều kiện dừng khi lặp tính toán.

### 2.5. Start, End Và Vector Được Tính Như Nào

Sau khi OpenCV trả kết quả, app chuyển các `MatOfPoint2f` thành array:

```kotlin
val statusArray = status.toArray()
val prevPtsArray = prevPts.toArray()
val currPtsArray = currPts.toArray()
```

Rồi duyệt từng điểm:

```kotlin
for (i in statusArray.indices) {
    if (statusArray[i].toInt() == 1) {
        val pt1 = prevPtsArray[i]
        val pt2 = currPtsArray[i]
        val dx = pt2.x - pt1.x
        val dy = pt2.y - pt1.y
    }
}
```

Tức là:

```text
start = prevPtsArray[i]
end   = currPtsArray[i]
dx    = end.x - start.x
dy    = end.y - start.y
```

Với KLT, start và end là 2 điểm thật sự trong 2 mảng:

| Thành phần | Biến trong code |
|---|---|
| Start point | `prevPtsArray[i]` |
| End point | `currPtsArray[i]` |
| Vector chuyển động | `(dx, dy)` |

### 2.6. Kiểm Tra Độ Tin Cậy Forward-Backward

App còn tính optical flow ngược lại:

```kotlin
Video.calcOpticalFlowPyrLK(
    currGray,
    prevGray,
    currPts,
    backwardPts,
    statusBack,
    errBack,
    lkWinSize,
    lkMaxLevel,
    lkCriteria,
    0,
    0.001
)
```

Ý tưởng:

```text
prevGray → currGray → prevGray
```

Nếu một điểm đi tới frame hiện tại rồi đi ngược về gần đúng vị trí ban đầu, điểm đó đáng tin hơn. App dùng số điểm hợp lệ này để tính `confidence`.

### 2.7. Làm Mượt Và Vẽ Vector

App không vẽ trực tiếp `dx/dy` thô. Nó:

1. Có thể trừ chuyển động dominant bằng median.
2. Làm mượt mỗi track bằng EMA.
3. Chỉ vẽ nếu độ lớn vector vượt threshold.
4. Nhân vector lên để nhìn rõ hơn trên màn hình.

Đoạn vẽ:

```kotlin
val displayDx = sdx * vectorDirectionSign * displayVectorLengthMultiplier * track.vis
val displayDy = sdy * vectorDirectionSign * displayVectorLengthMultiplier * track.vis
val displayEnd = Point(motion.start.x + displayDx, motion.start.y + displayDy)
Imgproc.line(currFrame, motion.start, displayEnd, color, vectorThickness)
```

Trong đó:

- `motion.start`: điểm start để vẽ.
- `displayEnd`: điểm end đã được scale cho dễ nhìn.
- `track.vis`: độ hiện dần/mờ dần của vector, tránh nhấp nháy.

### 2.8. Cập Nhật Cho Frame Sau

Cuối mỗi lần chạy:

```kotlin
currGray.copyTo(prevGray)
if (!currPts.empty()) {
    prevPts.fromArray(*currPts.toArray())
}
```

Nghĩa là:

```text
frame hiện tại trở thành frame trước
điểm end hiện tại trở thành điểm start cho lần sau
```

### 2.9. Tóm Tắt KLT

```text
Frame RGBA
→ currGray
→ nếu là frame đầu:
     currGray copy vào prevGray
     goodFeaturesToTrack tìm prevPts
→ nếu là frame sau:
     calcOpticalFlowPyrLK(prevGray, currGray, prevPts)
     output: currPts, status, err
     start = prevPts[i]
     end = currPts[i]
     vector = end - start = (dx, dy)
     vẽ vector lên currFrame
     currGray copy vào prevGray
     currPts copy sang prevPts
```

## 3. Farneback

Farneback trong app là dense optical flow. Nghĩa là thuật toán tính chuyển động cho toàn bộ ảnh hoặc gần như toàn bộ ảnh, thay vì chỉ theo dõi vài trăm điểm như KLT.

### 3.1. Các Biến Chính

Trong `Farneback.kt`:

```kotlin
private val scaledPrevGray: Mat = Mat()
private val scaledCurrGray: Mat = Mat()
private val flowGray: Mat = Mat()
private val backwardFlowGray: Mat = Mat()
private val currGray: Mat = Mat()
```

Ý nghĩa:

| Biến | Vai trò |
|---|---|
| `currGray` | Frame hiện tại sau khi convert sang grayscale |
| `scaledCurrGray` | `currGray` sau khi resize để tính nhanh hơn |
| `scaledPrevGray` | Frame xám trước đó đã resize |
| `flowGray` | Output optical flow chiều tiến: previous → current |
| `backwardFlowGray` | Output optical flow chiều ngược: current → previous |

### 3.2. Convert Frame Sang Gray Và Resize

Trong `run(newFrame)`:

```kotlin
Imgproc.cvtColor(newFrame, currGray, Imgproc.COLOR_RGBA2GRAY)
resizeForFlow(currGray, scaledCurrGray)
```

Sau đó `resizeForFlow` resize frame theo `frameScale`:

```kotlin
Imgproc.resize(
    sourceGray,
    targetGray,
    Size(),
    frameScale,
    frameScale,
    Imgproc.INTER_AREA
)
```

Mục đích resize:

- Giảm số pixel cần tính.
- Tăng tốc xử lý realtime.
- Sau đó khi vẽ lên frame gốc, app scale vector ngược lại bằng `xScale` và `yScale`.

### 3.3. Frame Đầu Tiên

Farneback cũng cần 2 frame. Nếu chưa có frame trước:

```kotlin
if (scaledPrevGray.empty() || flowInputSizeChanged) {
    scaledCurrGray.copyTo(scaledPrevGray)
    return buildOutput(...)
}
```

Luồng:

```text
scaledCurrGray
→ copy vào scaledPrevGray
→ chưa tính optical flow
```

### 3.4. Gọi `calcOpticalFlowFarneback`

Từ frame thứ 2 trở đi:

```kotlin
Video.calcOpticalFlowFarneback(
    scaledPrevGray,
    scaledCurrGray,
    flowGray,
    pyrScale,
    levels,
    winSize,
    iterations,
    polyN,
    polySigma,
    flags
)
```

Input:

- `scaledPrevGray`: frame trước.
- `scaledCurrGray`: frame hiện tại.

Output:

- `flowGray`: ma trận vector optical flow.

`flowGray` thường là `Mat` kiểu `CV_32FC2`, nghĩa là mỗi vị trí có 2 số float:

```text
flowGray[y, x] = [dx, dy]
```

Ý nghĩa:

```text
tại điểm (x, y), pixel được ước lượng dịch sang (x + dx, y + dy)
```

Với Farneback, không có `prevPts` và `currPts`. Start và end được suy ra từ vị trí pixel/grid và vector trong `flowGray`.

### 3.5. Các Tham Số Farneback

Trong code:

```kotlin
private val pyrScale = 0.5
private var levels = 2
private var winSize = 13
private var iterations = 2
private val polyN = 5
private val polySigma = 1.1
private val flags = 0
```

Ý nghĩa ngắn gọn:

| Tham số | Ý nghĩa |
|---|---|
| `pyrScale = 0.5` | Mỗi tầng pyramid giảm còn 1/2 kích thước |
| `levels` | Số tầng pyramid |
| `winSize` | Kích thước vùng lân cận dùng để ước lượng chuyển động |
| `iterations` | Số lần lặp ở mỗi tầng |
| `polyN` | Kích thước vùng dùng để xấp xỉ đa thức |
| `polySigma` | Độ mượt Gaussian cho xấp xỉ đa thức |
| `flags` | Cờ tùy chọn của OpenCV |

### 3.6. Start, End Và Vector Được Tính Như Nào

App không vẽ mọi pixel vì quá dày. Nó lấy mẫu theo grid trong `drawOptFlowMap`:

```kotlin
var screenY = startY
while (screenY < mapRows) {
    var screenX = startX
    while (screenX < mapCols) {
        ...
        screenX += step
    }
    screenY += step
}
```

Với mỗi điểm grid trên màn hình, app map ngược về tọa độ trong `flowGray`:

```kotlin
val flowX = (screenX / xScale).roundToInt().coerceIn(0, flowCols - 1)
val flowY = (screenY / yScale).roundToInt().coerceIn(0, flowRows - 1)
val vector = flow.get(flowY, flowX) ?: doubleArrayOf(0.0, 0.0)
val rawFx = vector[0] * xScale
val rawFy = vector[1] * yScale
```

Vì `flowGray` được tính trên ảnh đã resize, nên phải nhân lại:

```text
rawFx = dx trong flowGray * xScale
rawFy = dy trong flowGray * yScale
```

Sau đó start và end để vẽ:

```kotlin
val start = Point(screenX.toDouble(), screenY.toDouble())
val displayFx = sfx * vectorDirectionSign * vectorLengthMultiplier * cell.vis
val displayFy = sfy * vectorDirectionSign * vectorLengthMultiplier * cell.vis
val end = Point(start.x + displayFx, start.y + displayFy)
```

Tức là:

```text
start = điểm grid trên frame hiển thị
vector = flowGray[flowY, flowX] = [dx, dy]
end = start + vector
```

So với KLT:

| Thuật toán | Start | End | Vector |
|---|---|---|---|
| KLT | `prevPts[i]` | `currPts[i]` | `currPts[i] - prevPts[i]` |
| Farneback | điểm grid/pixel `(x, y)` | `(x + dx, y + dy)` | `flowGray[y, x] = [dx, dy]` |

### 3.7. Làm Mượt Và Vẽ Vector

Farneback dùng `gridCells` để lưu trạng thái từng ô lưới:

```kotlin
private val gridCells = HashMap<Int, GridCell>()
```

Mỗi `GridCell` giữ:

```kotlin
var fx = 0.0
var fy = 0.0
var vis = 0.0
var initialized = false
```

App làm mượt bằng EMA:

```kotlin
cell.fx += emaAlpha * (rawFx - cell.fx)
cell.fy += emaAlpha * (rawFy - cell.fy)
```

Rồi vẽ:

```kotlin
Imgproc.line(flowmap, start, end, color, vectorThickness)
Imgproc.circle(flowmap, start, dotRadius, color, -1)
```

`flowmap` chính là frame màu gốc được truyền vào, nên vector được vẽ trực tiếp lên hình camera.

### 3.8. Confidence Bằng Forward-Backward Error

Tương tự KLT, Farneback cũng tính chiều ngược:

```kotlin
Video.calcOpticalFlowFarneback(
    scaledCurrGray,
    scaledPrevGray,
    backwardFlowGray,
    ...
)
```

Với mỗi vector active, app kiểm tra:

```kotlin
val bx = (flowX + rawFx).roundToInt().coerceIn(0, flowCols - 1)
val by = (flowY + rawFy).roundToInt().coerceIn(0, flowRows - 1)
val bVec = backwardFlow.get(by, bx)
```

Nếu vector tiến và vector ngược gần triệt tiêu nhau:

```kotlin
val errX = rawFx + (bVec[0] * xScale)
val errY = rawFy + (bVec[1] * yScale)
if (errX * errX + errY * errY <= 2.25) fbeInliers++
```

Thì vector được xem là đáng tin hơn. `confidence` được tính:

```kotlin
val confidence = (fbeInliers.toDouble() / sampleCount.toDouble()) * 100.0
```

### 3.9. Heatmap Mode

Nếu `visualizationMode == HEATMAP`, app không chỉ vẽ mũi tên mà còn tạo heatmap:

```kotlin
if (visualizationMode == VisualizationMode.HEATMAP) {
    drawDenseHeatmap(flow, flowmap, xScale, yScale)
}
```

Trong `drawDenseHeatmap`:

1. Tách `flow` thành 2 kênh `dx`, `dy`.
2. Tính độ lớn vector bằng `Core.magnitude(dx, dy, magnitude)`.
3. Normalize độ lớn sang ảnh 8-bit.
4. Áp màu bằng `Imgproc.applyColorMap`.
5. Blend heatmap lên frame camera.

Luồng heatmap:

```text
flowGray [dx, dy]
→ magnitude = sqrt(dx² + dy²)
→ normalize
→ applyColorMap
→ blend lên frame gốc
```

### 3.10. Cập Nhật Cho Frame Sau

Cuối `run`:

```kotlin
scaledCurrGray.copyTo(scaledPrevGray)
```

Nghĩa là frame hiện tại trở thành frame trước cho vòng xử lý tiếp theo.

### 3.11. Tóm Tắt Farneback

```text
Frame RGBA
→ currGray
→ resize thành scaledCurrGray
→ nếu là frame đầu:
     scaledCurrGray copy vào scaledPrevGray
→ nếu là frame sau:
     calcOpticalFlowFarneback(scaledPrevGray, scaledCurrGray)
     output: flowGray
     flowGray[y, x] = [dx, dy]
     lấy mẫu theo grid
     start = điểm grid
     end = start + [dx, dy]
     vẽ vector hoặc heatmap lên frame
     tính backwardFlowGray để đo confidence
     scaledCurrGray copy vào scaledPrevGray
```

## 4. So Sánh Nhanh KLT Và Farneback

| Tiêu chí | KLT | Farneback |
|---|---|---|
| Loại optical flow | Sparse | Dense |
| Tính trên đâu | Một số điểm đặc trưng | Gần như toàn bộ ảnh |
| Output chính | `currPts`, `status`, `err` | `flowGray` |
| Start point | `prevPts[i]` | điểm grid/pixel `(x, y)` |
| End point | `currPts[i]` | `(x + dx, y + dy)` |
| Vector | `currPts[i] - prevPts[i]` | `flowGray[y, x] = [dx, dy]` |
| Ưu điểm | Nhanh, nhẹ, dễ tracking điểm | Dày hơn, thấy chuyển động toàn vùng |
| Nhược điểm | Phụ thuộc feature point tốt | Nặng hơn, dễ nhiễu nếu texture/kamera rung |

## 5. Câu Trả Lời Ngắn Khi Bị Hỏi

KLT chọn các điểm đặc trưng ở frame trước bằng `goodFeaturesToTrack`, lưu vào `prevPts`, rồi dùng `calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, ...)` để tìm vị trí mới của các điểm đó ở frame hiện tại. Vì vậy `prevPts[i]` là start, `currPts[i]` là end, còn vector chuyển động là `currPts[i] - prevPts[i]`.

Farneback không theo dõi danh sách điểm riêng. Nó nhận 2 ảnh xám liên tiếp `scaledPrevGray` và `scaledCurrGray`, rồi ghi kết quả vào `flowGray`. Mỗi ô trong `flowGray` chứa `[dx, dy]`, nghĩa là tại vị trí `(x, y)` pixel đã dịch sang `(x + dx, y + dy)`. App lấy mẫu theo grid để vẽ vector hoặc dùng toàn bộ độ lớn vector để tạo heatmap.

## 6. Ý Tưởng Thuật Toán Trong `LiveRoutingViewModel`

`LiveRoutingViewModel.kt` là nơi biến optical flow thành hỗ trợ dẫn đường khi GNSS yếu hoặc mất. Nếu `KLT.kt` và `Farneback.kt` trả về chuyển động ảnh theo pixel, thì `LiveRoutingViewModel` quyết định:

- GNSS hiện tại có đáng tin không.
- Khi nào bật `gnssAssistActive`.
- Lấy optical flow đổi sang vận tốc mét/giây như thế nào.
- Dùng gyro/IMU để giữ hướng và vận tốc ra sao.
- Khi mất GNSS thì cập nhật vị trí bằng dead reckoning như thế nào.
- Có snap vị trí lên route hay để chạy tự do.
- Khi nào route bị lệch và cần fetch route mới.

### 6.1. Các Input Chính

ViewModel nhận dữ liệu từ nhiều nguồn:

| Nguồn | Hàm nhận | Dữ liệu |
|---|---|---|
| Route ban đầu | `initialize(state)` | điểm bắt đầu, điểm đến, polyline route |
| GNSS location | `onLocationUpdate(location)` | lat/lon, accuracy, speed, bearing |
| GNSS status | `onGnssStatusChanged(satelliteCount)` | số vệ tinh đang dùng |
| Timer 50 ms | `onTick(nowMs, dtSec, yawRate, accel)` | gyro yaw rate, gia tốc ngang |
| Optical flow | `onOpticalMetrics(metrics)` | avg dx/dy, magnitude, confidence, active vector count |
| Route refresh | `applyRoute(route)` | route mới sau khi lệch đường |

`LiveRoutingFragment` gọi các hàm này:

```kotlin
liveRoutingViewModel.onLocationUpdate(location)
liveRoutingViewModel.onTick(nowMs, dtSec, yawRateDegPerSec, horizontalAccelDevice)
liveRoutingViewModel.onOpticalMetrics(metrics, nowMs)
```

### 6.2. State Quan Trọng

Một số biến quan trọng trong ViewModel:

| Biến | Ý nghĩa |
|---|---|
| `currentPoint` | vị trí hiện tại đang hiển thị |
| `currentHeadingDeg` | hướng hiện tại của mũi tên điều hướng |
| `lastTrueSpeedMps` | tốc độ thật từ GNSS hoặc suy từ GNSS |
| `deadReckoningSpeedMps` | tốc độ ước lượng khi mất GNSS |
| `vehicleDeadReckoningSpeedMps` | tốc độ nội bộ sau khi fusion camera + IMU + prior |
| `positionUncertaintyM` | độ bất định vị trí, tăng khi dead reckoning lâu |
| `gnssAssistActive` | true khi đang dùng optical/IMU assist thay GNSS |
| `dynamicFlowToMpsRatio` | hệ số đổi pixel/s sang m/s |
| `cameraSpeedScaleConfidence` | độ tin của hệ số đổi pixel/s sang m/s |
| `routeLocked` | true khi vị trí dead reckoning đang snap lên route |
| `routeProgressM` | quãng đường đã đi dọc theo route khi route lock |
| `gnssTravelPathSegments` | đoạn đường đi bằng GNSS tốt |
| `opticalAssistSegments` | đoạn đường đi bằng optical assist |
| `weakGnssPoints` | điểm bắt đầu vùng GNSS yếu |
| `strongGnssPoints` | điểm GNSS mạnh trở lại |

### 6.3. Khởi Tạo Route

Khi bắt đầu live routing, `initialize(state)` làm các việc chính:

```text
routeState = state
clear các segment cũ
reset optical runtime
reset camera speed scale
reset dead reckoning runtime
reset inertial runtime
currentPoint = startPoint
currentHeadingDeg = bearing từ GNSS hoặc bearing theo route
lastTrueSpeedMps = speed từ GNSS nếu có
positionUncertaintyM = accuracy từ GNSS nếu có
startNewSegment(gnssTravelPathSegments, startPoint)
```

Nếu GNSS start có bearing thì dùng bearing đó. Nếu không có bearing nhưng route có ít nhất 2 điểm, heading ban đầu được lấy theo hướng từ điểm route đầu sang điểm route thứ 2:

```kotlin
currentHeadingDeg = when {
    state.startLocation.hasBearing() -> normalizeDeg(state.startLocation.bearing.toDouble())
    routePoints.size > 1 -> bearingBetween(routePoints[0], routePoints[1])
    else -> 0.0
}
```

Ý tưởng: khi bắt đầu, app lấy GNSS làm nguồn thật, sau đó chuẩn bị các bộ nhớ runtime để nếu GNSS mất thì có thể tiếp tục nội suy.

### 6.4. GNSS Gating: Quyết Định GNSS Có Dùng Được Không

Mỗi location update đi qua `onLocationUpdate(location)`. Trước tiên app kiểm tra GNSS có đủ tin cậy không:

```kotlin
private fun isLocationUsableForGnss(location: Location, nowMs: Long): Boolean {
    val accuracyOk = !location.hasAccuracy() || location.accuracy <= MAX_USABLE_GNSS_ACCURACY_M
    val satelliteStatusFresh = nowMs - lastGnssStatusMs <= GNSS_STATUS_STALE_MS
    val satellitesOk = !satelliteStatusFresh || lastGnssSatelliteCount >= MIN_USABLE_GNSS_SATELLITES
    return accuracyOk && satellitesOk
}
```

Điều kiện:

- Accuracy phải không quá `25 m`.
- Nếu satellite status còn mới, số vệ tinh phải ít nhất `4`.
- Nếu đang ở test mode dropout và GNSS bị suppress, GNSS cũng bị xem như không dùng được.

Ngoài ra, GNSS chỉ được xem là đang còn hiện tại nếu fix cuối chưa quá cũ:

```kotlin
ageMs <= GNSS_LOCATION_STALE_MS
```

Trong code, `GNSS_LOCATION_STALE_MS = 5_000L`. Tức là quá 5 giây không có fix tốt thì app bật assist.

### 6.5. Khi GNSS Tốt: Cập Nhật Vị Trí Thật

Nếu location hợp lệ, `onLocationUpdate`:

1. Lấy `point = GeoPoint(location.latitude, location.longitude)`.
2. Tính tốc độ thật `lastTrueSpeedMps`.
3. Cập nhật hướng đi theo GNSS course-over-ground.
4. Reset tốc độ dead reckoning về tốc độ GNSS.
5. Cập nhật độ bất định bằng `location.accuracy`.
6. Học trục tiến của IMU bằng GNSS.
7. Append điểm vào `gnssTravelPathSegments`.
8. Tắt assist bằng `setGnssAssistActive(false)`.

Tốc độ được lấy theo thứ tự ưu tiên:

```kotlin
lastTrueSpeedMps = when {
    location.hasSpeed() -> location.speed.toDouble()
    previousPoint != null && previousDistance >= MIN_GNSS_DISTANCE_FOR_DERIVED_SPEED_M ->
        previousDistance / dtSec
    else -> 0.0
}
```

Nếu tốc độ quá nhỏ hơn `0.20 m/s`, app xem là đứng yên:

```kotlin
if (speed < GNSS_STATIONARY_SPEED_FLOOR_MPS) 0.0 else speed
```

Hướng GNSS được cập nhật nếu:

- Location có bearing và tốc độ đủ lớn.
- Hoặc không có bearing nhưng khoảng cách giữa 2 fix đủ lớn để suy ra bearing.

```text
nếu speed >= 1.5 m/s và location có bearing:
    lastCogDeg = location.bearing
ngược lại nếu dịch >= 2 m:
    lastCogDeg = bearing(previousPoint, point)
```

Ý tưởng: GNSS tốt là nguồn ground truth. Khi GNSS tốt, app không cần dead reckoning; nó dùng giai đoạn này để hiệu chuẩn camera và IMU cho lúc GNSS mất.

### 6.6. Timer `onTick`: Bộ Điều Phối Chính

`onTick` chạy mỗi `50 ms`:

```kotlin
const val TICK_MS = 50L
```

Luồng chính:

```text
updateImuAccel(accel)
runHeadingFilter(yawRate)
visualOdometry = resolveVisualOdometry()
assistDecision = setGnssAssistActive(!hasCurrentlyUsableGnss())

nếu GNSS còn tốt:
    calibrateCameraSpeedScale(visualOdometry)
    speed = lastTrueSpeedMps
    point = currentPoint từ GNSS
ngược lại:
    integrateDeadReckoning(visualOdometry, gyro, accel)
```

Code:

```kotlin
updateImuAccel(horizontalAccelDevice)
runHeadingFilter(nowMs, dtSec, yawRateDegPerSec)
val visualOdometry = resolveVisualOdometry(nowMs)
val assistDecision = setGnssAssistActive(!hasCurrentlyUsableGnss(nowMs))
```

Ý tưởng: `onTick` là vòng lặp realtime. GNSS update có thể chỉ 1 Hz, nhưng tick 20 Hz giúp mũi tên quay mượt và khi GNSS mất thì vị trí vẫn tiếp tục chạy.

### 6.7. Heading Filter: Gyro + GNSS Course

Heading được cập nhật bằng gyro yaw rate:

```kotlin
currentHeadingDeg = normalizeDeg(currentHeadingDeg + yawRateDegPerSec * dtSec)
```

Nhưng nếu xe gần như đứng yên và không xoay, app không cập nhật heading để tránh nhiễu:

```kotlin
if (speed < HEADING_FREEZE_SPEED_MPS && !rotating) return
```

Khi GNSS còn tốt, tốc độ đủ lớn, course-over-ground còn mới, và xe không đang rẽ gắt, app kéo heading về hướng GNSS:

```kotlin
val err = signedHeadingDelta(currentHeadingDeg, lastCogDeg)
currentHeadingDeg = normalizeDeg(currentHeadingDeg + err * cogGain(speed, nowMs))
```

Ý tưởng:

```text
gyro cho phản ứng nhanh khi quay
GNSS course sửa drift chậm khi đang chạy thẳng
```

Đây là complementary filter đơn giản.

### 6.8. Optical Metrics Thành Pixel/S

`KLT` hoặc `Farneback` trả `OpticalFlowMetrics`:

- `avgDx`
- `avgDy`
- `avgMagnitude`
- `confidence`
- `featureCount`
- `activeVectorCount`
- `lateralCoherence`

Trong `onOpticalMetrics`, ViewModel đổi displacement mỗi frame thành pixel/second:

```kotlin
val dtSec = dtMs / 1000.0
val mag = metrics.avgMagnitude / dtSec
val dx = metrics.avgDx / dtSec
val dy = metrics.avgDy / dtSec
```

Rồi làm mượt bằng EMA:

```kotlin
emaFlowMagPxPerSec = EMA_ALPHA * mag + (1 - EMA_ALPHA) * emaFlowMagPxPerSec
emaFlowDxPxPerSec = EMA_ALPHA * dx + (1 - EMA_ALPHA) * emaFlowDxPxPerSec
emaFlowDyPxPerSec = EMA_ALPHA * dy + (1 - EMA_ALPHA) * emaFlowDyPxPerSec
emaFlowCoherence = EMA_ALPHA * metrics.lateralCoherence + (1 - EMA_ALPHA) * emaFlowCoherence
```

`movingFraction` đo tỷ lệ vector đang active:

```kotlin
movingFraction = activeVectorCount / featureCount
```

Ý tưởng: optical flow thô rất nhiễu theo từng frame, nên ViewModel biến nó thành tín hiệu tốc độ pixel/s đã làm mượt.

### 6.9. Visual Odometry: Từ Flow Pixel/S Sang Tốc Độ Camera

`resolveVisualOdometry` quyết định optical flow có dùng được không và đổi nó sang m/s.

Đầu tiên kiểm tra flow còn mới không:

```kotlin
val flowFresh = nowMs - lastFlowSampleMs < FLOW_STALE_MS
```

`FLOW_STALE_MS = 650 ms`.

Sau đó tách flow theo 2 hướng:

```kotlin
val forwardFlowPxPerSec = abs(emaFlowDyPxPerSec)
val lateralFlowPxPerSec = abs(emaFlowDxPxPerSec)
```

Trong app này, `dy` được xem là thành phần tiến/lùi chính của camera, còn `dx` là ngang.

#### Lọc object cắt ngang

Nếu chỉ một vùng nhỏ chuyển động ngang mạnh, app xem đó có thể là object cắt ngang chứ không phải xe đang di chuyển:

```kotlin
val crossingObject = emaMovingFraction < MIN_EGO_MOTION_FRAME_FRACTION &&
    abs(emaFlowCoherence) > CROSSING_OBJECT_MIN_COHERENCE &&
    lateralFlowPxPerSec > forwardFlowPxPerSec * CROSSING_OBJECT_LATERAL_DOMINANCE
```

Nếu flow cũ, confidence thấp, hoặc bị xem là object cắt ngang, tốc độ translation bị set về 0:

```kotlin
val translationFlowPxPerSec = when {
    !flowFresh || lastFlowConfidence < MIN_FLOW_CONFIDENCE -> 0.0
    crossingObject -> 0.0
    else -> max(
        forwardFlowPxPerSec,
        emaFlowMagPxPerSec - lateralFlowPxPerSec * ROTATION_LATERAL_FLOW_DISCOUNT
    )
}
```

Ý tưởng:

- `forwardFlowPxPerSec`: chuyển động theo hướng tiến/lùi.
- `emaFlowMagPxPerSec - lateral discount`: tổng chuyển động sau khi giảm phần ngang do xoay/lệch.
- Lấy `max` để không bỏ sót chuyển động tiến.

Flow chỉ usable nếu lớn hơn ngưỡng:

```kotlin
translationFlowPxPerSec >= TRANSLATION_FLOW_STILL_PX_PER_SEC
```

`TRANSLATION_FLOW_STILL_PX_PER_SEC = 4.0`.

Sau đó trừ ngưỡng đứng yên:

```kotlin
effectiveTranslationFlowPxPerSec = translationFlowPxPerSec - 4.0
```

Đổi sang m/s:

```kotlin
speedMps = effectiveTranslationFlowPxPerSec * dynamicFlowToMpsRatio
```

Và tính quality:

```text
quality = 0.65 * confidenceScore + 0.35 * flowScore
```

Kết quả được gói thành:

```kotlin
VisualOdometry(
    usable = usable,
    speedMps = speedMps,
    translationPxPerSec = translationFlowPxPerSec,
    quality = quality
)
```

### 6.10. Học Hệ Số Pixel/S Sang M/S Khi GNSS Tốt

Optical flow ban đầu chỉ biết pixel/s, không tự biết là bao nhiêu m/s. Vì vậy app học hệ số:

```kotlin
dynamicFlowToMpsRatio
```

Khi GNSS còn tốt, `onTick` gọi:

```kotlin
calibrateCameraSpeedScale(visualOdometry)
```

Điều kiện học:

- visual odometry usable.
- tốc độ GNSS ít nhất `2.0 m/s`.
- flow ít nhất `10 px/s`.

Tỷ lệ hiện tại:

```kotlin
currentRatio = lastTrueSpeedMps / effectiveTranslationFlowPxPerSec
```

Rồi clamp:

```text
0.006 <= ratio <= 0.30
```

Sau đó EMA vào `dynamicFlowToMpsRatio`:

```kotlin
dynamicFlowToMpsRatio =
    alpha * ratio + (1 - alpha) * dynamicFlowToMpsRatio
```

Nếu confidence camera scale còn thấp thì học nhanh hơn:

```kotlin
alpha = 0.42 nếu confidence < 0.35
alpha = 0.16 nếu đã ổn hơn
```

Ý tưởng:

```text
khi GPS tốt:
    biết speed thật từ GNSS
    biết flow pixel/s từ camera
    học ratio = m/s / pixel/s
khi GPS mất:
    dùng ratio đã học để đổi flow thành tốc độ
```

### 6.11. Học Trục Tiến Của IMU Từ GNSS

Điện thoại có thể đặt lệch trong xe, nên trục X/Y/Z của máy chưa chắc trùng hướng xe chạy. ViewModel học hướng "forward axis" bằng cách so gia tốc thiết bị với thay đổi tốc độ GNSS.

Khi GNSS tốt, `onLocationUpdate` gọi:

```kotlin
learnForwardAxisFromGnss(...)
```

Điều kiện học:

- Có sample GNSS trước đó.
- Có accelerometer sample.
- Khoảng thời gian GNSS hợp lý.
- Tốc độ đủ lớn `>= 3.0 m/s`.
- Heading không đổi quá `6 độ`.
- Gia tốc dọc suy từ GNSS đủ lớn `>= 0.45 m/s²`.

Gia tốc dọc từ GNSS:

```kotlin
gnssLongAccel = (currentSpeedMps - prevSpeed) / dtGnssSec
```

Vector gia tốc thiết bị được normalize, rồi nhân sign theo xe đang tăng hay giảm tốc:

```kotlin
val sign = if (gnssLongAccel >= 0.0) 1.0 else -1.0
val dirX = ax / accelMag * sign
val dirY = ay / accelMag * sign
val dirZ = az / accelMag * sign
```

Sau đó blend vào `forwardAxisDevice` và tăng `forwardAxisConfidence`.

Khi đủ confidence, app còn học bias gia tốc:

```kotlin
measuredLongAccel = accel dot forwardAxisDevice
residual = measuredLongAccel - gnssLongAccel
longitudinalAccelBiasMps2 = EMA(residual)
```

Ý tưởng:

```text
khi GNSS tốt:
    GNSS cho biết xe đang tăng/giảm tốc thật
    accelerometer cho vector gia tốc trong hệ tọa độ điện thoại
    từ đó học trục nào của điện thoại là hướng tiến của xe
khi GNSS mất:
    chiếu accelerometer lên trục này để lấy gia tốc dọc
```

Khi cần gia tốc dọc:

```kotlin
projected = emaAccelDevice dot forwardAxisDevice
currentLongitudinalAccel = projected - longitudinalAccelBiasMps2
```

### 6.12. Ước Lượng Tốc Độ Khi GNSS Mất

Khi `gnssAssistActive = true`, `integrateDeadReckoning` gọi:

```kotlin
deadReckoningSpeedMps = estimateVehicleDeadReckoningSpeed(...)
```

Hàm này fusion 3 nguồn:

| Nguồn | Biến | Ý nghĩa |
|---|---|---|
| GNSS cũ | `priorSpeed` | tốc độ GNSS cuối cùng, decay dần theo thời gian |
| IMU | `inertialSpeed` | tốc độ sau khi tích phân gia tốc dọc |
| Optical flow | `visualOdometry.speedMps` | tốc độ camera đổi từ pixel/s sang m/s |

#### Prior speed từ GNSS cũ

Nếu vừa mất GNSS, tốc độ GNSS cuối vẫn còn giá trị tham khảo. App làm nó giảm dần:

```kotlin
lastTrueSpeedMps * exp(-outageSec / LAST_GNSS_SPEED_DECAY_SEC)
```

`LAST_GNSS_SPEED_DECAY_SEC = 14.0`.

#### Inertial speed

Nếu IMU forward axis đủ tin:

```kotlin
inertialSpeed = currentSpeed + longitudinalAccel * accelTrust * dtSec
```

#### Visual speed

Nếu optical flow usable, app blend visual speed với inertial speed:

```kotlin
targetSpeed =
    visualWeight * visualOdometry.speedMps +
    (1.0 - visualWeight) * inertialSpeed
```

Trong đó:

```kotlin
visualWeight = 0.78 * visualOdometry.quality * scaleConfidenceWeight
```

`scaleConfidenceWeight` phụ thuộc `cameraSpeedScaleConfidence`, nên camera càng được hiệu chuẩn tốt thì càng được tin hơn.

Nếu camera chưa hiệu chuẩn đủ tin mà visual speed thấp hơn prior speed, app không cho tốc độ tụt quá mạnh:

```text
targetSpeed >= priorSpeed * priorGuard
```

Nếu IMU không cho thấy đang phanh mạnh, app cũng tránh drop speed quá sâu so với inertial speed.

Cuối cùng tốc độ không nhảy đột ngột mà đi dần bằng `approachSpeed`:

```kotlin
vehicleDeadReckoningSpeedMps = approachSpeed(
    current = currentSpeed,
    target = targetSpeed,
    dtSec = dtSec,
    maxRiseMps2 = speedRiseLimit,
    maxDropMps2 = dropLimit
)
```

Ý tưởng:

```text
không tin 100% vào camera
không tin 100% vào IMU
không giữ mãi tốc độ GPS cũ
trộn cả ba, rồi giới hạn gia tốc tăng/giảm cho giống xe thật
```

### 6.13. Zero Velocity Update: Phát Hiện Đang Đứng Yên

Trước khi fusion tốc độ, app kiểm tra xe có đang đứng yên không:

```kotlin
detectStationary(visualOdometry, yawRateDegPerSec, nowMs)
```

Điều kiện:

- visual speed nhỏ hơn `zuptEnterSpeedMps`.
- tốc độ GNSS cũ đã decay xuống thấp.
- yaw rate nhỏ.
- gia tốc tổng không vượt ngưỡng.

Nếu đứng yên:

```kotlin
vehicleDeadReckoningSpeedMps = 0.0
return 0.0
```

Ý tưởng: đây là ZUPT, tức zero velocity update. Khi chắc chắn đứng yên, set tốc độ về 0 để tránh drift làm vị trí tự trôi.

### 6.14. Tích Phân Vị Trí Khi GNSS Mất

Sau khi có tốc độ dead reckoning:

```kotlin
val stepDistanceMeters = deadReckoningSpeedMps * dtSec
val pose = resolveOutagePose(origin, stepDistanceMeters)
currentPoint = pose.point
```

Nếu không snap route, vị trí mới được offset từ điểm cũ theo heading:

```kotlin
offsetPoint(origin, distanceMeters, currentHeadingDeg)
```

`offsetPoint` dùng công thức địa cầu với `EARTH_RADIUS_M`, không cộng lat/lon thô.

### 6.15. Route Lock / Snap Mode Khi Mất GNSS

Nếu `snapMode == SNAP`, app cố gắng khóa vị trí dead reckoning lên route.

Khi assist mới bật, trong `setGnssAssistActive(true)`:

```kotlin
val projection = currentPoint?.let { projectOnRoute(it) }
if (projection != null && projection.distanceFromRouteM <= ROUTE_LOCK_ENTER_M) {
    routeLocked = true
    routeProgressM = projection.distanceAlongRouteM
    lockReferencePoint = projection.point
}
```

`ROUTE_LOCK_ENTER_M = 25.0`. Nghĩa là nếu lúc mất GNSS xe đang cách route không quá 25 m, app lock vào route.

Khi đã `routeLocked`, mỗi tick:

```kotlin
routeProgressM += distanceMeters
routePose = pointAtRouteDistance(routeProgressM)
```

Tức là app không lấy hướng tự do để ra lat/lon nữa, mà đi dọc theo polyline route theo quãng đường đã tính.

Đồng thời app vẫn tính một điểm dead reckoning tự do `reckon` từ heading:

```kotlin
reckon = offsetPoint(lockReferencePoint ?: origin, distanceMeters, currentHeadingDeg)
```

Sau đó đo drift giữa điểm route và điểm tự do:

```kotlin
drift = routePose.point.distanceToAsDouble(reckon)
```

Nếu drift <= `50 m`, app tiếp tục snap:

```kotlin
return FusedPose(routePose.point, routePose.segmentHeadingDeg, confidence)
```

Nếu drift quá lớn hoặc đã đi quá cuối route, app release lock:

```kotlin
routeLocked = false
```

Nếu chưa lock, app đi tự do trước, rồi thử project điểm tự do lên route. Nếu gần route <= 25 m thì lock lại:

```kotlin
val freePoint = offsetPoint(origin, distanceMeters, currentHeadingDeg)
val projection = projectOnRoute(freePoint)
if (projection.distanceFromRouteM <= ROUTE_LOCK_ENTER_M) {
    routeLocked = true
    return FusedPose(projection.point, projection.segmentHeadingDeg, 1.0)
}
```

Ý tưởng:

```text
SNAP mode:
    mất GNSS nhưng đang gần route thì chạy dọc route
    dùng dead reckoning tự do để kiểm tra drift
    drift nhỏ thì giữ lock
    drift lớn thì thả ra REAL/free position
```

### 6.16. `projectOnRoute`: Chiếu Một Điểm Lên Polyline Route

`projectOnRoute(point)` duyệt từng segment route:

```text
route[i] → route[i + 1]
```

Với mỗi segment, code đổi lat/lon sang hệ mét local quanh `point`:

```kotlin
metersPerDegreeLatitude = 111_132.0
metersPerDegreeLongitude = 111_320.0 * cos(latitude)
```

Rồi tính projection ratio trên đoạn thẳng:

```kotlin
projectionRatio = dot(pointToStart, segment) / segmentLengthSq
```

Trong code vì hệ tọa độ được đặt tương đối quanh `point`, công thức viết là:

```kotlin
(-(startX * segmentX + startY * segmentY) / segmentLengthSq).coerceIn(0.0, 1.0)
```

Sau đó lấy điểm projected:

```kotlin
projected = interpolatePoint(start, end, projectionRatio)
```

Candidate tốt nhất là điểm projected có `distanceFromRouteM` nhỏ nhất.

Kết quả `RouteProjection` gồm:

- `point`: điểm gần nhất trên route.
- `segmentIndex`: đang nằm trên segment nào.
- `distanceAlongRouteM`: đã đi bao nhiêu mét từ đầu route tới projection.
- `distanceFromRouteM`: cách route bao xa.
- `segmentHeadingDeg`: hướng của segment route.

### 6.17. Độ Bất Định Vị Trí

Khi GNSS tốt, uncertainty lấy từ accuracy hoặc default:

```text
positionUncertaintyM = location.accuracy hoặc 6 m
```

Khi dead reckoning, uncertainty tăng theo thời gian và quãng đường:

```kotlin
positionUncertaintyM += sensorGrowthMps * dtSec + distanceGrowth
```

Nếu có visual odometry usable thì tăng chậm hơn:

```text
visual usable: 0.8 m/s + 8% quãng đường
no camera:     2.2 m/s + 25% quãng đường
```

Khi route matching có confidence, uncertainty được giảm:

```kotlin
positionUncertaintyM *= (1.0 - confidence * ROUTE_MATCH_UNCERTAINTY_REDUCTION)
```

Ý tưởng: mất GNSS càng lâu thì vị trí càng kém chắc chắn, nhưng nếu camera tốt hoặc đang lock route thì giảm drift.

### 6.18. Bật/Tắt GNSS Assist

Assist được bật/tắt qua:

```kotlin
setGnssAssistActive(active)
```

Khi bật assist:

```text
gnssAssistActive = true
positionUncertaintyM ít nhất 8 m
startNewSegment(opticalAssistSegments, currentPoint)
weakGnssPoints.add(currentPoint)
gnssTravelSegmentOpen = false
thử route lock nếu SNAP mode và gần route
```

Khi tắt assist:

```text
gnssAssistActive = false
reconvergeUntilMs = now + 2 giây
```

`reconvergeUntilMs` giúp heading filter kéo heading về GNSS course mượt hơn khi GNSS vừa quay lại.

### 6.19. Vẽ Path GNSS Và Optical Assist

ViewModel không vẽ trực tiếp. Nó trả `NavigationSnapshot`, trong đó có:

- `gnssTravelPathSegments`
- `opticalAssistSegments`
- `weakGnssPoints`
- `strongGnssPoints`
- `remainingRoutePoints`

Khi GNSS tốt, điểm được append vào `gnssTravelPathSegments` với khoảng cách tối thiểu:

```kotlin
GNSS_PATH_APPEND_DISTANCE_M = 0.75
```

Khi assist active, điểm dead reckoning được append vào `opticalAssistSegments`:

```kotlin
DEAD_RECKONING_APPEND_DISTANCE_M = 0.35
```

Ý tưởng UI:

```text
GNSS tốt: vẽ đường GNSS bình thường
GNSS yếu/mất: vẽ đoạn optical assist riêng
weak/strong point: đánh dấu nơi mất và có lại GNSS
```

### 6.20. Phát Hiện Lệch Route Và Fetch Route Mới

`updateRouteDeviation()` kiểm tra current point có lệch route không.

Điều kiện không reroute:

- Chưa có route.
- Chưa có current point.
- Đã gần destination trong `18 m`.
- Khoảng cách tới route <= `35 m`.

Nếu cách route > `35 m`, app không reroute ngay. Nó lấy mẫu mỗi `1 s`:

```kotlin
ROUTE_DEVIATION_SAMPLE_INTERVAL_MS = 1_000L
```

Cần ít nhất 2 mẫu liên tiếp:

```kotlin
ROUTE_DEVIATION_REQUIRED_SAMPLES = 2
```

Và có cooldown giữa các lần refresh:

```kotlin
ROUTE_REFRESH_COOLDOWN_MS = 12_000L
```

Nếu đủ điều kiện, `LiveRoutingFragment` gọi `fetchRoute(origin, destination)`, rồi đưa route mới về:

```kotlin
liveRoutingViewModel.applyRoute(route)
```

Ý tưởng: tránh gọi route API liên tục do GPS jitter hoặc dead reckoning lệch nhẹ.

### 6.21. Tóm Tắt Thuật Toán Live Routing

```text
Khởi tạo:
    lấy start, destination, route polyline
    currentPoint = start
    heading = GNSS bearing hoặc hướng route
    reset optical/IMU/dead-reckoning runtime

Khi GNSS update:
    nếu accuracy/satellite không đạt:
        xem như GNSS yếu
    nếu GNSS tốt:
        currentPoint = GNSS point
        lastTrueSpeed = speed GNSS hoặc distance/time
        lastCog = bearing GNSS hoặc bearing giữa 2 fix
        học camera scale bằng tick
        học forward axis IMU
        append GNSS path
        tắt assist

Mỗi optical frame:
    nhận metrics từ KLT/Farneback
    đổi dx/dy/magnitude thành px/s
    EMA smoothing
    lưu confidence, moving fraction, coherence

Mỗi tick 50 ms:
    cập nhật acceleration EMA
    heading = heading + gyroYaw * dt
    nếu GNSS tốt thì kéo heading về GNSS COG
    resolve visual odometry từ optical flow
    nếu GNSS tốt:
        hiệu chuẩn pixel/s → m/s
        dùng vị trí GNSS
    nếu GNSS mất:
        bật assist
        speed = fusion(prior GNSS speed, optical speed, IMU acceleration)
        distance = speed * dt
        nếu SNAP mode và gần route:
            đi dọc route theo routeProgress
        ngược lại:
            offset vị trí theo heading
        append optical assist path
        trả NavigationSnapshot cho UI

Reroute:
    nếu currentPoint cách route > 35 m
    và lặp lại đủ 2 mẫu
    và cooldown 12 s đã qua:
        fetch route mới
```

### 6.22. Câu Trả Lời Ngắn Khi Bị Hỏi

`LiveRoutingViewModel` là bộ điều phối sensor fusion cho live routing. Khi GNSS tốt, app dùng GNSS làm vị trí thật, đồng thời học hệ số đổi optical flow pixel/s sang m/s và học trục tiến của IMU. Khi GNSS mất quá 5 giây hoặc fix không đủ chất lượng, `gnssAssistActive` bật lên. Lúc đó app lấy optical flow để ước lượng tốc độ, lấy gyro để cập nhật heading, lấy acceleration để hỗ trợ tăng/giảm tốc, rồi tích phân `distance = speed * dt` để cập nhật vị trí. Nếu đang ở SNAP mode và xe gần route, vị trí dead reckoning được khóa lên polyline route bằng `routeProgressM`; nếu drift quá lớn thì thả lock và chạy tự do.

## 7. Đóng Góp Thuật Toán Trong Phần Optical Flow

Phần này chỉ nói về thuật toán optical flow, không tính phần GNSS, IMU hay live routing.

OpenCV cung cấp thuật toán nền:

- `calcOpticalFlowPyrLK` cho KLT.
- `calcOpticalFlowFarneback` cho Farneback.
- `goodFeaturesToTrack` để chọn điểm góc cho KLT.

Phần xử lý thêm trong code là lớp hậu xử lý sau khi OpenCV trả vector thô:

```text
OpenCV tính vector thô
→ lọc điểm/vector không đáng tin
→ lấy median để chống giá trị rác
→ trừ dominant motion nếu cần
→ EMA smoothing để giảm giật
→ visibility ramp để vector hiện/mất dần
→ forward-backward error để tính confidence
→ xuất metrics chung cho KLT/Farneback
```

### 7.1. Median Để Loại Giá Trị Rác

Trong optical flow, không phải vector nào cũng đúng. Một số vector có thể sai vì:

- Điểm bị tracking nhầm.
- Vật thể đi ngang qua camera.
- Bóng sáng, phản chiếu, vùng thiếu texture.
- Camera rung.
- Frame bị motion blur.

Nếu dùng trung bình cộng, chỉ một vài vector sai rất lớn cũng kéo kết quả lệch.

Ví dụ:

```text
dx = [2, 2, 3, 2, 100]
mean   = 21.8
median = 2
```

`100` là giá trị rác. Trung bình bị kéo lên `21.8`, còn median vẫn đại diện đúng cho phần lớn vector.

Trong KLT:

```kotlin
val medDx = median(motions.map { it.dx })
val medDy = median(motions.map { it.dy })
```

Trong Farneback:

```kotlin
val avgDx = median(activeFx)
val avgDy = median(activeFy)
```

Tên biến `avgDx/avgDy` trong Farneback là average theo nghĩa đại diện tổng quát, nhưng code thực tế dùng median.

Ý nghĩa đóng góp:

```text
Median giúp vector đại diện không bị lệch bởi một số điểm optical flow sai.
```

### 7.2. Dominant Motion Là Gì?

`Dominant motion` là chuyển động chủ đạo của toàn frame.

Ví dụ camera bị rung hoặc cả khung hình trôi sang phải:

```text
vector 1: dx = 5
vector 2: dx = 6
vector 3: dx = 5
vector 4: dx = 4
vector 5: dx = 100  // rác
```

Phần lớn vector quanh `5 px`, nên chuyển động chủ đạo của frame là khoảng `5 px`.

Trong KLT, code tính dominant motion bằng median:

```kotlin
val dominantDx = if (subtractDominantMotion && trackedMotions.size >= 8) median(allDxList) else 0.0
val dominantDy = if (subtractDominantMotion && trackedMotions.size >= 8) median(allDyList) else 0.0
```

Sau đó trừ khỏi từng vector:

```kotlin
val rawDx = motion.dx - dominantDx
val rawDy = motion.dy - dominantDy
```

Tức là:

```text
vector tương đối = vector gốc - chuyển động chủ đạo toàn frame
```

### 7.3. Vì Sao Cần Trừ Dominant Motion?

Nếu camera đứng yên nhưng bị rung nhẹ, tất cả điểm nền có thể cùng dịch một hướng. Nếu vẽ trực tiếp, màn hình sẽ đầy mũi tên giống nhau, dù thật ra đó chỉ là rung/camera drift.

Sau khi trừ dominant motion:

```text
dominantDx = 5

điểm nền:              dx = 5   → dx mới = 0
điểm chuyển động riêng: dx = 12  → dx mới = 7
điểm nhiễu lớn:         dx = 100 → median vẫn không bị kéo lệch mạnh
```

Kết quả:

- Chuyển động chung của nền bị giảm.
- Chuyển động tương đối nổi bật hơn.
- Overlay ít bị nhiễu bởi rung/camera drift.

Trong code, dominant motion chỉ bị trừ khi:

```kotlin
subtractDominantMotion = !isMoving
```

Nghĩa là:

```text
isMoving = false → trừ dominant motion
isMoving = true  → giữ dominant motion
```

Lý do: khi camera/xe thật sự đang di chuyển, dominant motion có thể chính là tín hiệu quan trọng, nên không phải lúc nào cũng trừ.

### 7.4. EMA Smoothing Là Gì?

EMA là `Exponential Moving Average`, tức trung bình động hàm mũ.

Công thức:

```text
smooth = alpha * newValue + (1 - alpha) * oldSmooth
```

Trong code viết dưới dạng:

```kotlin
track.dx += emaAlpha * (rawDx - track.dx)
track.dy += emaAlpha * (rawDy - track.dy)
```

Với:

```kotlin
emaAlpha = 0.35
```

Nghĩa là mỗi frame:

```text
giá trị mới đóng góp 35%
giá trị cũ giữ lại 65%
```

Ví dụ:

```text
oldSmooth = 10
rawDx = 20
alpha = 0.35

smooth mới = 10 + 0.35 * (20 - 10)
           = 13.5
```

Vector không nhảy thẳng từ `10` lên `20`, mà đi dần:

```text
10 → 13.5 → 15.8 → 17.3 → ...
```

Ý nghĩa đóng góp:

```text
EMA làm vector optical flow ổn định theo thời gian, giảm hiện tượng mũi tên đổi hướng/độ dài liên tục giữa các frame.
```

### 7.5. EMA Trong KLT

KLT lưu trạng thái cho từng track:

```kotlin
private class Track {
    var dx = 0.0
    var dy = 0.0
    var vis = 0.0
    var initialized = false
}
```

Mỗi lần có vector mới, code không dùng trực tiếp `rawDx/rawDy`, mà làm mượt:

```kotlin
track.dx += emaAlpha * (rawDx - track.dx)
track.dy += emaAlpha * (rawDy - track.dy)
```

Sau đó mới vẽ:

```kotlin
val displayDx = sdx * vectorDirectionSign * displayVectorLengthMultiplier * track.vis
val displayDy = sdy * vectorDirectionSign * displayVectorLengthMultiplier * track.vis
```

Ý nghĩa:

```text
mỗi mũi tên KLT có bộ nhớ riêng, nên hướng/độ dài của nó thay đổi mượt hơn qua thời gian.
```

### 7.6. EMA Trong Farneback

Farneback không theo dõi từng feature point như KLT. Nó lấy mẫu theo grid. Mỗi ô grid có trạng thái:

```kotlin
private class GridCell {
    var fx = 0.0
    var fy = 0.0
    var vis = 0.0
    var initialized = false
}
```

Với mỗi cell:

```kotlin
cell.fx += emaAlpha * (rawFx - cell.fx)
cell.fy += emaAlpha * (rawFy - cell.fy)
```

Ý nghĩa:

```text
Farneback làm mượt theo từng ô lưới, giúp các vector dense không rung/nhấp nháy quá mạnh.
```

### 7.7. Visibility Ramp: Hiện Dần Và Mất Dần

Ngoài làm mượt `dx/dy`, code còn làm mượt độ xuất hiện của vector bằng biến `vis`.

Config:

```kotlin
private val visRampUp = 0.30
private val visRampDown = 0.12
private val drawVisMin = 0.12
```

Nếu vector đủ mạnh:

```kotlin
track.vis = (track.vis + visRampUp).coerceAtMost(1.0)
```

Nếu vector yếu hoặc không còn active:

```kotlin
track.vis = (track.vis - visRampDown).coerceAtLeast(0.0)
```

Ví dụ khi vector mới xuất hiện:

```text
vis: 0.0 → 0.3 → 0.6 → 0.9 → 1.0
```

Khi vector biến mất:

```text
vis: 1.0 → 0.88 → 0.76 → 0.64 → ...
```

Vì `vis` được nhân vào độ dài vector:

```kotlin
displayDx = sdx * multiplier * vis
displayDy = sdy * multiplier * vis
```

nên mũi tên không bật/tắt đột ngột. Nó ngắn/dài dần theo thời gian.

Ý nghĩa đóng góp:

```text
Visibility ramp giúp overlay optical flow nhìn tự nhiên hơn, tránh việc vector nhấp nháy khi magnitude dao động quanh threshold.
```

### 7.8. Threshold Active Vector

Không phải vector nhỏ nào cũng nên dùng. Các chuyển động rất nhỏ thường là nhiễu.

KLT:

```kotlin
val active = mag >= minTrackedMotionMagnitude
```

Farneback:

```kotlin
val active = (sfx * sfx) + (sfy * sfy) >= minMotionSquared
```

Ý nghĩa:

```text
Chỉ vector đủ lớn mới được xem là chuyển động thật; vector nhỏ do nhiễu bị giảm ảnh hưởng.
```

Threshold này thay đổi theo sensitivity.

### 7.9. Forward-Backward Error Để Tính Confidence

OpenCV trả vector chiều tiến:

```text
frame trước → frame hiện tại
```

Nhưng code tính thêm chiều ngược:

```text
frame hiện tại → frame trước
```

KLT:

```kotlin
Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, ...)
Video.calcOpticalFlowPyrLK(currGray, prevGray, currPts, backwardPts, ...)
```

Farneback:

```kotlin
Video.calcOpticalFlowFarneback(scaledPrevGray, scaledCurrGray, flowGray, ...)
Video.calcOpticalFlowFarneback(scaledCurrGray, scaledPrevGray, backwardFlowGray, ...)
```

Ý tưởng:

```text
nếu một điểm đi từ A sang B,
rồi tính ngược từ B về gần A,
thì vector đó đáng tin.
```

KLT kiểm tra:

```kotlin
val errX = pt1.x - ptBack.x
val errY = pt1.y - ptBack.y
if (errX * errX + errY * errY <= 2.25) fbeValid = true
```

Farneback cũng kiểm tra sai số tiến-ngược:

```kotlin
val errX = rawFx + (bVec[0] * xScale)
val errY = rawFy + (bVec[1] * yScale)
if (errX * errX + errY * errY <= 2.25) fbeInliers++
```

`2.25` tương đương sai số bình phương của `1.5 px`.

Sau đó confidence:

```text
confidence = số vector pass forward-backward / tổng vector tracked
```

Ý nghĩa đóng góp:

```text
Không tin hoàn toàn output thô từ OpenCV, mà tự đánh giá độ tin cậy bằng forward-backward consistency.
```

### 7.10. Sensitivity Tuning

Code cho phép chỉnh độ nhạy optical flow thay vì cố định tham số.

KLT chỉnh:

```kotlin
maxCorners
qualityLevel
minDistance
minTrackedMotionMagnitude
```

Ý nghĩa:

- `maxCorners`: số điểm tối đa để track.
- `qualityLevel`: ngưỡng chất lượng điểm góc.
- `minDistance`: khoảng cách tối thiểu giữa các điểm.
- `minTrackedMotionMagnitude`: ngưỡng vector đủ mạnh.

Farneback chỉnh:

```kotlin
frameScale
drawStep
levels
winSize
iterations
minMotionMagnitude
```

Ý nghĩa:

- `frameScale`: scale frame để cân bằng tốc độ/chất lượng.
- `drawStep`: khoảng cách giữa các vector grid.
- `levels`: số tầng pyramid.
- `winSize`: kích thước cửa sổ ước lượng.
- `iterations`: số vòng lặp.
- `minMotionMagnitude`: ngưỡng chuyển động.

Ý nghĩa đóng góp:

```text
Sensitivity tuning giúp thuật toán thích nghi giữa chế độ nhẹ/nhanh và chế độ nhạy/chi tiết.
```

### 7.11. Grid Sampling Cho Farneback

Farneback trả dense flow, tức flow cho rất nhiều pixel. Nếu vẽ tất cả pixel thì:

- Rất rối.
- Tốn tài nguyên.
- Người dùng khó đọc hướng chuyển động.

Vì vậy code lấy mẫu theo lưới:

```kotlin
screenX += step
screenY += step
```

Mỗi grid cell lấy một vector đại diện từ `flowGray`.

Ý nghĩa đóng góp:

```text
Grid sampling biến dense flow thành overlay dễ nhìn và đủ nhẹ cho realtime.
```

### 7.12. Heatmap Cho Farneback

Ngoài vector, Farneback còn có heatmap mode:

```kotlin
drawDenseHeatmap(flow, flowmap, xScale, yScale)
```

Ý tưởng:

```text
flow [dx, dy]
→ magnitude = sqrt(dx² + dy²)
→ normalize
→ applyColorMap
→ blend lên frame camera
```

Heatmap giúp nhìn vùng nào chuyển động mạnh/yếu thay vì chỉ nhìn mũi tên.

Ý nghĩa đóng góp:

```text
Heatmap mode tạo cách trực quan hóa dense optical flow khác, phù hợp khi muốn xem phân bố chuyển động toàn frame.
```

### 7.13. Metrics Chung Cho KLT Và Farneback

Code không chỉ vẽ vector, mà còn xuất metrics:

```kotlin
OpticalFlowMetrics(
    algorithm = "...",
    frameIndex = ...,
    processTimeMs = ...,
    instantFps = ...,
    featureCount = ...,
    activeVectorCount = ...,
    avgDx = ...,
    avgDy = ...,
    avgMagnitude = ...,
    confidence = ...,
    threshold = ...,
    sensitivity = ...,
    lateralCoherence = ...
)
```

Nhờ đó có thể so sánh KLT và Farneback theo:

- FPS xử lý.
- Số vector track được.
- Số vector active.
- Vector đại diện.
- Độ lớn chuyển động.
- Confidence.
- Lateral coherence.

Ý nghĩa đóng góp:

```text
Metrics biến optical flow từ phần hiển thị trực quan thành dữ liệu có thể phân tích, so sánh và đưa sang các module khác.
```

### 7.14. Interface Chung Cho 2 Thuật Toán

KLT và Farneback được đóng gói qua interface:

```kotlin
interface OpticalFlow {
    fun run(newFrame: Mat): OFOutput?
    fun resetMotionVector()
    fun updateFeatures()
    fun setSensitivity(value: Int)
    fun setMovingMode(isMoving: Boolean)
}
```

Output chung là:

```kotlin
class OFOutput {
    var ofFrame: Mat? = null
    var position: Point? = null
    var metrics: OpticalFlowMetrics? = null
}
```

Ý nghĩa:

```text
App có thể đổi giữa KLT và Farneback mà phần UI/analysis/live routing vẫn dùng cùng một kiểu output.
```

### 7.15. Câu Trả Lời Ngắn Khi Bị Hỏi

Trong phần thuật toán optical flow, đóng góp của tôi là lớp hậu xử lý sau output thô của OpenCV. Tôi dùng median để loại outlier, dominant motion subtraction để tách chuyển động chủ đạo của toàn frame khỏi chuyển động tương đối, EMA để làm mượt vector theo thời gian, visibility ramp để mũi tên xuất hiện và mất dần, threshold để bỏ chuyển động quá nhỏ, forward-backward error để tính confidence, sensitivity tuning để điều chỉnh độ nhạy, grid sampling và heatmap cho Farneback, đồng thời chuẩn hóa output thành metrics chung cho KLT và Farneback.
