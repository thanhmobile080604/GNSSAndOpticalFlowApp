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
