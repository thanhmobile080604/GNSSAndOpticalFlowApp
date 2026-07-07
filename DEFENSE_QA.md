# DEFENSE_QA — Bộ câu hỏi phản biện và trả lời chi tiết

> Tài liệu này liệt kê **mọi câu hỏi bản chất** mà hội đồng có thể hỏi khi bảo vệ đồ án
> **GNSSAndOpticalFlowApp** (Android) + **OpticalFlowServer** (Python FastAPI/RAFT).
> Câu trả lời được diễn giải **dễ hiểu, không máy móc**, kèm số dòng code minh chứng.
>
> Ngôn ngữ: Tiếng Việt. Mục đích: chuẩn bị phản biện.

---

## MỤC LỤC

1. [Optical Flow — Bản chất chuyển đổi ảnh và tính vector](#1-optical-flow--bản-chất)
2. [Optical Flow — Thuật toán KLT (sparse)](#2-klt-sparse-optical-flow)
3. [Optical Flow — Thuật toán Farneback (dense)](#3-farneback-dense-optical-flow)
4. [Optical Flow — Server RAFT (Deep Learning)](#4-raft-server-side)
5. [IMU Estimator và cảm biến quán tính](#5-imu-estimator)
6. [GNSS — Vì sao cần ít nhất 4 vệ tinh](#6-gnss-4-vệ-tinh)
7. [GNSS — Kiến trúc 4 tầng ưu tiên (PVT → IGS → SGP4 → Approximate)](#7-gnss-4-tầng-ưu-tiên)
8. [GNSS — Toán học quỹ đạo Kepler và WGS84](#8-toán-học-quỹ-đạo-và-wgs84)
9. [GNSS — Vị trí Mặt Trời và Mặt Trăng (thuật toán Meeus)](#9-mặt-trời-và-mặt-trăng)
10. [Dialog thông tin vệ tinh 3D — từng biến](#10-map3dinformationdialog)
11. [AR rendering — View matrix, Projection matrix, Skybox](#11-ar-rendering)
12. [Live Routing — Dead reckoning khi mất GNSS](#12-live-routing-dead-reckoning)
13. [Analytics — So sánh KLT vs Farneback (benchmark)](#13-analytics-benchmark)
14. [Server — FastAPI, ONNX Runtime, Chunked upload](#14-server-fastapi-onnx)
15. [Câu hỏi tổng hợp — Kiến trúc, hiệu năng, giới hạn](#15-câu-hỏi-tổng-hợp)

---

## 1. Optical Flow — Bản chất

### 1.1. Optical Flow là gì? Nó tính cái gì?

**Optical flow** là **trường vector 2D** mô tả **chuyển động biểu kiến của các điểm ảnh (pixel)**
giữa hai khung hình liên tiếp. Mỗi pixel `(x, y)` ở khung hình cũ được ánh xạ sang vị trí
mới `(x + dx, y + dy)` ở khung hình mới; cặp `(dx, dy)` chính là **vector optical flow** tại
điểm đó.

Lưu ý bản chất: đây là **chuyển động biểu kiến** (apparent motion), tức là chỉ dựa trên
thay đổi độ sáng pixel. Nó không nhất thiết trùng với chuyển động thực (motion field).
Ví dụ: đèn nhấp nháy trên tường tạo optical flow dù tường không di chuyển; và một quả bóng
quay quanh trục đối xứng (đơn sắc) sẽ có motion field khác 0 nhưng optical flow bằng 0
vì độ sáng pixel không đổi. Trong đồ án, ta chấp nhận sai số này vì camera điện thoại
di chuyển tương đối chậm và bối cảnh có texture đủ phong phú.

### 1.2. Giả thuyết cốt lõi của optical flow (brightness constancy) là gì?

Cả KLT lẫn Farneback đều dựa trên **giả thuyết bảo toàn độ sáng (Brightness Constancy
Assumption)**:

```
I(x, y, t) = I(x + dx, y + dy, t + dt)
```

Nghĩa là một điểm vật chất trong thế giới, dù di chuyển tới đâu trong khung hình,
vẫn giữ cùng cường độ sáng. Khai triển Taylor bậc nhất:

```
∂I/∂x · dx + ∂I/∂y · dy + ∂I/∂t · dt ≈ 0
```

Chia hai vế cho `dt`, ta có **phương trình ràng buộc optical flow (OFCE)**:

```
Ix · u + Iy · v + It = 0
```

Trong đó `u = dx/dt`, `v = dy/dt` là hai thành phần chưa biết. Một phương trình mà hai
ẩn số, nên bài toán bị **thiếu ràng buộc (aperture problem)**: chỉ tính được thành phần
vuông góc với biên, không tính được thành phần song song với biên.

Cách vượt qua:
- **KLT / Lucas-Kanade**: giả thiết flow không đổi trong một cửa sổ nhỏ, gộp nhiều
  phương trình lại → giải bằng bình phương tối thiểu.
- **Farneback**: xấp xỉ vùng lân cận mỗi pixel bằng **đa thức bậc 2**, so sánh hệ số
  đa thức giữa 2 khung → suy ra vector chuyển dịch.

### 1.3. Convert ImageProxy → Mat như thế nào?

Xem `CameraOpticalFlowFragment.kt` — hàm `imageProxyToRgbaMat`. Bản chất từng bước:

1. **CameraX xuất ImageProxy định dạng YUV_420_888.** Đây là chuẩn của Android:
   - `plane[0]` = kênh Y (luminance, 1 byte/pixel, kích thước gốc).
   - `plane[1]` = kênh U (chroma, 1 byte / 4 pixel).
   - `plane[2]` = kênh V (chroma, 1 byte / 4 pixel).
   Định dạng này tiết kiệm bộ nhớ (12 bit/pixel thay vì 32 bit như RGBA).

2. **Đọc từng plane vào `ByteBuffer`.** Vì các plane có thể có `rowStride ≠ width`
   (do hardware padding), ta phải sao chép từng dòng có bù rowStride/pixelStride,
   không được `bytebuffer.get()` toàn khối.

3. **Ghép Y, U, V thành một `Mat` NV21** (`CV_8UC1`, kích thước `1.5 × H × W`).
   NV21 là format YUV 4:2:0 interleaved mà OpenCV nhận trực tiếp.

4. **Gọi `Imgproc.cvtColor(nv21Mat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)`**:
   - Đây là hàm OpenCV được triển khai bằng NEON/SIMD, biến đổi màu theo công thức
     BT.601:
     ```
     R = Y + 1.402·(V-128)
     G = Y - 0.344·(U-128) - 0.714·(V-128)
     B = Y + 1.772·(U-128)
     A = 255
     ```
   - Kết quả là **Mat kiểu `CV_8UC4`** (4 kênh 8-bit unsigned: R, G, B, Alpha),
     kích thước đúng `H × W`.

5. **Xoay Mat theo `imageInfo.rotationDegrees`** (thường là 90° khi cầm dọc):
   `Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE)`. Nếu không xoay, hình sẽ nằm ngang.

Vì sao chọn NV21 thay vì tự tay convert YUV → RGB bằng Kotlin? Vì OpenCV convert
nhanh gấp ~10 lần nhờ SIMD, quan trọng khi ta cần chạy ~30 FPS liên tục.

### 1.4. `CV_8UC4` nghĩa là gì?

- `CV_` = prefix của OpenCV.
- `8` = depth 8-bit.
- `U` = unsigned (giá trị 0-255, không âm).
- `C4` = 4 kênh (channels).

Ghép lại: **`CV_8UC4` = ma trận 4 kênh, mỗi kênh là 1 byte không dấu**. Đúng khớp
với định dạng RGBA mà OpenGL và Android surface sử dụng.

Các loại khác dùng trong đồ án:
- `CV_8UC1` — 1 kênh, dùng cho ảnh xám (`prevGray`, `currGray` trong KLT/Farneback).
- `CV_32FC2` — 2 kênh float, dùng cho **flow map** của Farneback (mỗi pixel lưu `(dx, dy)`).
- `CV_32FC1` — 1 kênh float, dùng cho magnitude/heatmap.

### 1.5. Vì sao phải chuyển RGBA → GRAY trước khi tính flow?

Xem `Farneback.kt:81` và `KLT.kt:124`:

```kotlin
Imgproc.cvtColor(newFrame, currGray, Imgproc.COLOR_RGBA2GRAY)
```

Lý do:
1. **Optical flow chỉ cần độ sáng** — công thức OFCE dùng gradient ảnh xám, không dùng
   thông tin màu.
2. **Giảm 4× dữ liệu** — 4 kênh → 1 kênh, tăng tốc gấp 4 lần, giảm cache miss.
3. **Chuẩn hóa** — hai camera khác trắng (white-balance) sẽ có RGB khác nhau nhưng
   độ sáng gần giống, nên grayscale ổn định hơn với optical flow.

Công thức OpenCV dùng cho `COLOR_RGBA2GRAY` là **BT.601 luma**:

```
Y = 0.299·R + 0.587·G + 0.114·B
```

Hệ số G lớn nhất vì mắt người nhạy nhất với xanh lá.

### 1.6. Vẽ vector màu ra sao? Bản chất `Imgproc.line`?

Xem `Farneback.kt:263` và `KLT.kt:269`:

```kotlin
Imgproc.line(flowmap, start, end, color, vectorThickness)
Imgproc.circle(flowmap, start, dotRadius, color, -1)
```

Bản chất:
1. **Tính điểm cuối vector**: `end = start + (dx, dy) × scale × visibility × sign`.
   - `scale = vectorLengthMultiplier = 4.2` (Farneback) hoặc `4.8` (KLT) — nhân độ dài
     cho dễ nhìn.
   - `visibility ∈ [0, 1]` — hệ số fade in/fade out, giúp mũi tên **không nháy**.
   - `sign = ±1` — đảo chiều nếu ở chế độ moving (đi bộ).
2. **`Imgproc.line`** triển khai thuật toán **Bresenham** (hoặc Xiaolin Wu antialiased):
   tính các pixel nằm trên đường thẳng nối `start → end` và tô màu `color = Scalar(R,G,B)`
   với độ dày `thickness` pixel.
3. **`Imgproc.circle` với `radius = -1`** vẽ vòng tròn **đặc** (filled). Đây là "chấm gốc"
   tại đầu vector, giúp thấy rõ điểm xuất phát ngay cả khi vector rất ngắn.
4. **Màu**: `Scalar(0, 255, 0)` cho Farneback (xanh lá), `Scalar(240, 230, 140)` cho KLT
   (vàng nhạt / khaki). Bản chất `Scalar` là `(B, G, R, A)` khi Mat 4 kênh, hoặc
   `(R, G, B, A)` sau khi cvtColor RGBA. Trong file này ta đã ở không gian RGBA,
   nên `Scalar(0, 255, 0)` chính là **xanh lục thuần**.

### 1.7. Vì sao mũi tên phải fade in/out (visibility ramp)?

Xem `Farneback.kt:52-55` và `KLT.kt:53-56`:

```kotlin
private val emaAlpha = 0.35
private val visRampUp = 0.30
private val visRampDown = 0.12
private val drawVisMin = 0.12
```

Vấn đề: khi độ lớn vector dao động quanh ngưỡng `minMotionMagnitude`, mũi tên sẽ
**nháy liên tục** (frame này vẽ, frame sau không) → khó chịu, gây ảo giác chuyển động
không có thật.

Giải pháp: mỗi cell/track có biến `vis ∈ [0, 1]`:
- Nếu vector đang active → `vis += 0.30` (tăng nhanh) mỗi frame, kẹp về 1.
- Nếu vector đang idle → `vis -= 0.12` (giảm chậm) mỗi frame, kẹp về 0.
- Chỉ vẽ khi `vis > 0.12`.
- **Độ dài hiển thị** được nhân thêm `vis`, nên khi vector vừa xuất hiện, nó vẽ ngắn
  rồi dài dần; khi mất động, nó co ngắn lại rồi biến mất.

Đây là kỹ thuật **hysteresis + soft-thresholding**, quen thuộc trong xử lý ảnh (Canny
edge detector cũng dùng hysteresis 2 ngưỡng).

Cùng với **EMA (Exponential Moving Average) α = 0.35** trên `(dx, dy)`:
```
dx_new = dx_old + 0.35 × (raw_dx - dx_old)
```
mỗi track được **làm mượt theo thời gian**, không giật hướng frame-to-frame.

### 1.8. So sánh pixel giữa 2 frame là "so sánh" cái gì?

Không phải so từng pixel `if (a == b)`. Bản chất là **tối thiểu hoá sai số cường độ**
trên một cửa sổ / một vùng đa thức.

- **KLT**: với mỗi feature point, tìm `(dx, dy)` sao cho
  `Σ (I_prev(x, y) - I_curr(x + dx, y + dy))² → min` trong cửa sổ 21×21.
  Bài toán giải bằng **Gauss-Newton** trên phương trình
  `[G] · [dx, dy]ᵀ = [b]`, với `G` là ma trận **cấu trúc** (structure matrix)
  của gradient `Σ Ix²`, `Σ IxIy`, `Σ Iy²`.
- **Farneback**: xấp xỉ vùng lân cận `(2n+1)×(2n+1)` (n=5) bằng đa thức bậc 2:
  `f(x) ≈ xᵀAx + bᵀx + c`. Với frame 2, đa thức là `f₂(x) = f₁(x - d)`,
  bung ra và cân bằng hệ số → giải `A·d ≈ ½·(b₂ - b₁)` cho vector chuyển dịch `d`.

### 1.9. Vì sao KLT và Farneback cùng chạy song song?

Xem `CameraOpticalFlowFragment.kt` → hàm `processAnalysisFrame`. App clone Mat gốc **hai
lần**, chạy KLT trên copy 1 và Farneback trên copy 2 **trong 2 coroutine song song**,
sau đó ghép side-by-side thành một khung hình để so sánh trực quan.

Mục đích:
- **Demo thực nghiệm** cho hội đồng thấy điểm mạnh/yếu của mỗi thuật toán trên cùng đầu vào.
- **Benchmark**: cùng ghi metric FPS, confidence, số vector active để so trên đồ thị Analytics.

---

## 2. KLT (Sparse Optical Flow)

### 2.1. KLT là gì? Vì sao gọi là "sparse"?

**KLT = Kanade-Lucas-Tomasi**, tổ hợp:
- **Shi-Tomasi corner detector** (`goodFeaturesToTrack`): chọn các **điểm góc** đủ tốt để theo dõi.
- **Lucas-Kanade Pyramid** (`calcOpticalFlowPyrLK`): theo dõi các điểm đó qua khung tiếp theo.

"Sparse" nghĩa là chỉ theo dõi **vài trăm điểm đặc trưng**, không phải mọi pixel.
Trong `KLT.kt:37`, `maxCorners = 240` mặc định, sensitivity 100 → 420 điểm.

Ưu điểm: nhanh, đủ chính xác nếu có góc để bám. Nhược điểm: mất tín hiệu ở vùng phẳng
(tường trắng, bầu trời) — không có góc để track.

### 2.2. `goodFeaturesToTrack` bản chất làm gì?

Xem `KLT.kt:103`:
```kotlin
Imgproc.goodFeaturesToTrack(prevGray, corners, maxCorners, qualityLevel, minDistance)
```

Thuật toán **Shi-Tomasi** (cải tiến từ Harris corner):

1. Tính ma trận **structure tensor** trong cửa sổ 3×3 quanh mỗi pixel:
   ```
   M = Σ [ Ix²    Ix·Iy ]
         [ Ix·Iy   Iy²  ]
   ```
2. Tính 2 giá trị riêng `λ₁, λ₂` của M.
3. **Điểm góc** nếu `min(λ₁, λ₂) > qualityLevel × max_all_lambda_min`.
   - Nếu `λ₁, λ₂` đều lớn → điểm ở góc (2 hướng gradient mạnh).
   - Nếu 1 lớn 1 nhỏ → điểm ở cạnh (chỉ 1 hướng gradient) → loại.
   - Nếu cả hai nhỏ → vùng phẳng → loại.
4. **Non-maximum suppression** với bán kính `minDistance` để tránh feature dồn cụm.
5. Lấy tối đa `maxCorners` điểm mạnh nhất.

Trong đồ án, `qualityLevel` chỉnh động theo sensitivity `0.005 → 0.10` (`KLT.kt:76`):
sensitivity cao → nhận cả điểm yếu → nhiều feature hơn.

### 2.3. `calcOpticalFlowPyrLK` (Pyramidal Lucas-Kanade) hoạt động thế nào?

Bài toán: cho `prevGray, currGray, prevPts` → tìm `currPts` sao cho mỗi điểm được theo
đúng qua khung mới.

Lucas-Kanade cơ bản chỉ chính xác khi chuyển động nhỏ (≤1-2 pixel). Với chuyển động
lớn (xe di chuyển, tay rung), ta cần **pyramid**:

1. Xây pyramid Gauss cho cả 2 ảnh: `L0 (nguyên gốc), L1 (½), L2 (¼), L3 (⅛)`.
   Trong `KLT.kt:47`, `lkMaxLevel = 3` → 4 tầng.
2. Bắt đầu từ tầng thô nhất `L3` — chuyển động lớn thu gọn còn ~1 pixel.
3. Áp dụng Lucas-Kanade trên cửa sổ 21×21 (`lkWinSize`) tại tầng đó:
   giải hệ 2 ẩn từ OFCE bằng bình phương tối thiểu:
   ```
   [Σ Ix²    Σ IxIy] [u]   [-Σ Ix·It]
   [Σ IxIy   Σ Iy² ] [v] = [-Σ Iy·It]
   ```
4. Nội suy kết quả lên tầng mịn hơn (nhân 2), dùng làm điểm khởi đầu, refine tiếp.
5. Về đến `L0` → có `(dx, dy)` chính xác.
6. `TermCriteria(COUNT + EPS, 30, 0.01)` — tối đa 30 iteration hoặc dừng khi
   độ thay đổi < 0.01 pixel.

Output còn có mảng `status[i]`: 1 = theo dõi thành công, 0 = mất dấu (điểm ra khỏi biên,
sai số quá lớn, hoặc gradient suy biến).

### 2.4. Forward-Backward Error (FBE) là gì? Vì sao dùng làm confidence?

Xem `KLT.kt:187-223`:

```kotlin
Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, currPts, ...)
Video.calcOpticalFlowPyrLK(currGray, prevGray, currPts, backwardPts, ...)
val errX = pt1.x - ptBack.x
if (errX*errX + errY*errY <= 2.25) fbeValid = true
```

Bản chất: track một điểm **tới** rồi track **ngược lại**. Nếu tracker tốt, điểm phải
trở về đúng vị trí ban đầu. Nếu sai lệch > 1.5 pixel (2.25 = 1.5²) → điểm này bị nghi ngờ.

Confidence = tỷ lệ điểm có FBE ≤ 1.5 pixel:
```
confidence = inliers / totalTracked × 100%
```

Vì sao dùng FBE thay vì eigenvalue hay minEigThreshold? FBE trực tiếp đo **độ nhất quán
hình học**, không phụ thuộc mức xám. Kinh nghiệm cho thấy FBE loại các điểm bám
"nhầm vào chỗ khác" (occlusion, ánh sáng đổi) rất tốt.

### 2.5. Vì sao trừ dominant motion (median dx/dy)?

Xem `KLT.kt:235`:
```kotlin
val dominantDx = if (subtractDominantMotion && trackedMotions.size >= 8) median(allDxList) else 0.0
```

Khi camera pan (quay ngang), **tất cả** các điểm đều chuyển động cùng chiều. Nhưng
mục đích của chúng ta là detect **chuyển động tương đối** (xe, người đi bộ) trên nền
tĩnh, không phải chuyển động của bản thân camera.

Vì vậy:
- Nếu ở chế độ **standing (đứng yên)** — trừ dominant motion để lộ vật thể đang di chuyển.
- Nếu ở chế độ **moving (đi bộ)** — không trừ, vì bản thân chuyển động của camera chính
  là cái ta muốn ước lượng để làm visual odometry.

**Dominant** dùng **median** thay vì **mean** vì median chống outlier: vài điểm bị track
sai không kéo cả giá trị lệch đi.

### 2.6. `lateralCoherence` là gì? Ứng dụng cho detect rẽ (turn)?

Xem `KLT.kt:277`:
```kotlin
val lateralCoherence = if (coherenceSumAbsDx > 1e-3) coherenceSumDx / coherenceSumAbsDx else 0.0
```

Công thức: `Σ dx / Σ |dx|`, giá trị trong `[-1, 1]`:
- **+1**: mọi vector đều lệch **dương** (sang phải) → camera đang quay sang trái (vì thế
  giới trong ảnh dịch sang phải) hoặc xe đang **rẽ trái**.
- **-1**: mọi vector đều lệch âm → camera quay phải → xe **rẽ phải**.
- **0**: vector chia đều dương/âm → đi thẳng.

Đây là ý tưởng chính trong đề tài phát hiện rẽ: dùng **coherence hướng vector**
thay vì chỉ dựa gyro, vì gyro có drift còn coherence trực tiếp phản ánh cảnh thay đổi.

### 2.7. Vì sao chọn `winSize = 21`?

Trade-off:
- Cửa sổ nhỏ (5-9): nhanh, nhạy chi tiết nhưng dễ **aperture problem** (lỗi kính lỗ), noisy.
- Cửa sổ lớn (31+): mượt, chống noise tốt nhưng chậm, và giả thiết "flow đều trong cửa sổ"
  không còn đúng nếu có nhiều vật thể chuyển động độc lập.
- **21×21** là compromise chuẩn của OpenCV cho video 640-1080p, đủ cover chuyển động 5-10px/frame.

### 2.8. Semaphore trong `setSensitivity` để làm gì?

Xem `KLT.kt:49, 72, 165`:
```kotlin
private val semaphore: Semaphore = Semaphore(1)
```

`Semaphore(1)` ≡ mutex. Lý do: `setSensitivity` thay đổi `maxCorners, qualityLevel,
minDistance` **trong khi** `run()` có thể đang track trên thread khác. Nếu thay đổi
giữa chừng, hệ dữ liệu `tracks[]` sẽ không khớp size với `prevPts` → crash.

Semaphore đảm bảo hai luồng loại trừ nhau khi chạm cùng biến. Đây là điển hình cho
**cross-thread state protection**.

### 2.9. Vì sao motion vector cuối cùng dùng median chứ không mean?

Xem `KLT.kt:284`:
```kotlin
val medDx = median(motions.map { it.dx })
val medDy = median(motions.map { it.dy })
```

Trong một khung hình, luôn có vài điểm bị track sai (occlusion, lá cây đung đưa, người
đi qua). Nếu dùng **mean**, các outlier ~50 px kéo trung bình đi rất xa. **Median**
robust — cần > 50% điểm bị sai thì mới lệch, mà thực tế FBE đã loại bớt outliers rồi.

### 2.10. Motion vector output còn được smooth thêm bằng cách nào?

Xem `KLT.kt:296-300`:
```kotlin
currMv = Point(
    prevMv!!.x * 0.85 + newMv.x * 0.15,
    prevMv!!.y * 0.85 + newMv.y * 0.15
)
```

Đây là **low-pass filter bậc 1** (còn gọi là exponential smoothing) với hệ số 0.15/0.85.
Bản chất giống EMA α = 0.15: giữ 85% giá trị cũ, thêm 15% giá trị mới → mượt, phản ứng
chậm với đột biến. Áp dụng cho vector chuyển động đầu ra (dùng cho live routing).

---

## 3. Farneback (Dense Optical Flow)

### 3.1. "Dense" khác "Sparse" thế nào?

- **Sparse** (KLT): tính flow cho vài trăm feature points. Kết quả là `MatOfPoint2f`.
- **Dense** (Farneback): tính flow **cho mọi pixel**. Kết quả là `Mat CV_32FC2` cùng
  kích thước ảnh, mỗi pixel lưu `(dx, dy)` dạng float.

Dense có ưu thế: không phụ thuộc feature detector, hoạt động tốt trên vùng phẳng, có
đủ thông tin để visualize heatmap. Nhược điểm: chậm hơn nhiều — mỗi pixel một phép tính.

### 3.2. Farneback bản chất là gì?

Được Gunnar Farnebäck đề xuất năm 2003. Ý tưởng chính:

**Xấp xỉ đa thức bậc 2** cho vùng lân cận mỗi pixel:
```
f(x) ≈ xᵀ A x + bᵀ x + c
```
- `A` (ma trận 2×2), `b` (vector 2), `c` (scalar) — 6 hệ số/pixel.
- Được ước lượng bằng bình phương tối thiểu có trọng số Gauss trên cửa sổ `(2n+1)²`
  với `n = polyN = 5`.

**Giả thuyết**: khung sau là khung trước dịch chuyển `d`:
```
f₂(x) = f₁(x - d) = (x-d)ᵀ A (x-d) + bᵀ(x-d) + c
```
Bung ra và cân bằng hệ số, được:
```
A · d ≈ (b₁ - b₂) / 2
```
Giải hệ 2×2 này ra được `d = (dx, dy)`. Đây là công thức lõi.

**Multi-scale**: giống LK, chạy Gauss pyramid nhiều tầng (`levels = 2-3` trong đồ án)
để bắt chuyển động lớn.

### 3.3. Ý nghĩa từng tham số Farneback?

Xem `Farneback.kt:29-35`:

| Tham số | Giá trị | Ý nghĩa |
|---------|---------|---------|
| `pyrScale = 0.5` | scale mỗi tầng pyramid = 1/2 | Chuẩn |
| `levels = 2..3` | số tầng | Cao hơn → bắt chuyển động lớn, chậm hơn |
| `winSize = 11..19` | cửa sổ lấy trung bình | Lớn → mượt, mất chi tiết |
| `iterations = 2..3` | vòng lặp Gauss-Newton mỗi tầng | Cao hơn → chính xác, chậm hơn |
| `polyN = 5` | bán kính khai triển đa thức | 5 → dùng cửa sổ 11×11, cân bằng |
| `polySigma = 1.1` | σ của Gauss trong xấp xỉ đa thức | 1.1 hợp với `polyN=5` (theo Farnebäck) |
| `flags = 0` | không dùng flow trước, dùng box filter | Nhanh |

### 3.4. Vì sao downscale ảnh trước (frameScale 0.35-0.7)?

Xem `Farneback.kt:36, 168-182`:
```kotlin
private var frameScale = 0.5
...
Imgproc.resize(sourceGray, targetGray, Size(), frameScale, frameScale, Imgproc.INTER_AREA)
```

Farneback O(W×H) — thu ảnh còn 50% giảm 4× phép tính. Chuyển động 10 px trên full-res
thành 5 px trên half-res, vẫn nằm trong phạm vi bắt được của winSize=11. Không mất
thông tin do:
- `INTER_AREA` = **area-averaging**, chống aliasing tốt (giống box filter).
- Sau đó scale ngược `dx, dy` bằng `xScale = mapCols / flowCols` (`Farneback.kt:201-202`)
  để vẽ đúng trên ảnh gốc.

### 3.5. `HashMap<Int, GridCell>` để làm gì?

Xem `Farneback.kt:56-66`:
```kotlin
private class GridCell {
    var fx = 0.0; var fy = 0.0; var vis = 0.0; var initialized = false
}
private val gridCells = HashMap<Int, GridCell>()
```

Farneback vẽ mũi tên tại các điểm lưới cố định cách nhau `drawStep = 20-40` px. Mỗi
ô lưới có một `GridCell` để **lưu state** qua các frame:
- `fx, fy` — vector đã EMA smooth.
- `vis` — mức hiển thị (fade in/out).
- `initialized` — cờ để lần đầu không interpolate.

Key = `rowIndex * 100_000 + colIndex` (`GRID_KEY_STRIDE = 100_000`). Đây là **encoding
2D → 1D** đơn giản (miễn `colIndex < 100k`, luôn đúng cho video < 100k cột).

### 3.6. Heatmap mode làm việc thế nào?

Xem `Farneback.kt:332-411`:

1. **Tách 2 kênh flow** thành `dx, dy` (`Core.split`).
2. **Scale ngược lên ảnh gốc**: `dx *= xScale, dy *= yScale`.
3. **`Core.magnitude`**: tính `mag = √(dx² + dy²)` per-pixel.
4. **Gaussian blur 9×9**: làm mượt để tránh nhiễu chấm.
5. **Normalize theo max**: `normalized = mag / maxMagnitude`, giá trị [0, 1].
6. **Convert sang 8-bit**: `heatmap8u = normalized * 255`.
7. **Blur thêm 15×15**: mềm hơn nữa.
8. **`applyColorMap(COLORMAP_TURBO)`**: bảng LUT màu (xanh dương → xanh lá → vàng → đỏ)
   giống nhiệt kế nhưng có gradient đẹp hơn Jet.
9. **Resize lên full-size** bằng `INTER_CUBIC`.
10. **Tạo mask**: chỉ blend heatmap ở vùng magnitude vượt ngưỡng
    (`HEATMAP_MASK_THRESHOLD_MULTIPLIER = 0.32`), tránh nhiễu ở vùng không có chuyển động.
11. **`Core.addWeighted`**: blend frame gốc (58%) + heatmap (70%) tại vùng mask.

Kết quả: khu vực chuyển động mạnh hiện thành mảng màu đỏ/vàng, vùng tĩnh giữ ảnh gốc.

### 3.7. Vì sao dùng `Turbo` colormap thay vì `Jet`?

`Jet` (dài dòng) có "band artifacts" — người xem hiểu nhầm gradient. `Turbo` (Google
Research, 2019) là **perceptually uniform** — 2 giá trị cách đều nhau trên thang đo
thì cách đều nhau về màu → nhìn quantitative chính xác hơn.

### 3.8. `computeCenteredGridStart` để làm gì?

Xem `Farneback.kt:414-421`. Vấn đề: nếu bắt đầu vẽ từ pixel 0, lưới sẽ **lệch về phía
trên-trái**, phía dưới-phải mất một dải trống. Hàm này tính offset để lưới **cân đối
ở giữa frame**.

Bản chất:
```
sampleCount = ((size - 1 - halfStep) / step) + 1
occupiedSpan = (sampleCount - 1) × step
start = round((size - 1 - occupiedSpan) / 2)
```

### 3.9. So sánh KLT vs Farneback về sức mạnh, nhược điểm?

| Tiêu chí | KLT (sparse) | Farneback (dense) |
|----------|-------------|--------------------|
| Tốc độ | Nhanh (30-60 FPS mobile) | Chậm (~10-15 FPS) |
| Vùng phẳng | Không track được | Track được nhờ đa thức bậc 2 |
| Precision | Cao ở điểm góc | Đều, hơi mờ ở biên |
| Bộ nhớ | Nhẹ (vài trăm points) | Nặng (Mat CV_32FC2 full-size) |
| Visualize | Chỉ mũi tên rời rạc | Có heatmap density |
| Ứng dụng chính | Object tracking, VO | Motion segmentation, ego-motion |

Trong đồ án, KLT dùng cho **live camera streaming** (đòi hỏi FPS), Farneback dùng
cho **video mode** (không real-time nhưng cần visualize dense).

---

## 4. RAFT Server-side

### 4.1. Vì sao có server riêng khi Android đã có KLT + Farneback?

RAFT (Recurrent All-Pairs Field Transforms, ECCV 2020) là **state-of-the-art** dense
optical flow, chính xác hơn Farneback ~10× nhưng nặng ~1000× → chạy không nổi trên
Android. Server (GPU/DirectML) offload xử lý này:
- App upload video → server chạy RAFT → trả video có overlay + metrics.
- Đóng vai trò "gold standard" để so sánh với KLT/Farneback đang chạy trên thiết bị.

### 4.2. RAFT hoạt động ra sao (bản chất)?

RAFT gồm 3 thành phần:
1. **Feature encoder**: CNN ResNet-like biến 2 khung `I1, I2` thành 2 tensor feature
   ở độ phân giải 1/8. Đồng thời có **context encoder** riêng cho `I1`.
2. **Correlation volume 4D**: tính tương quan feature vector giữa mọi cặp `(x1, y1, x2, y2)`.
   Đây là "all-pairs" — ai dịch chuyển tới đâu đều có tương quan.
3. **Iterative refinement (GRU)**: khởi tạo flow = 0, mỗi vòng lặp mạng GRU truy vấn
   correlation volume tại flow hiện tại + refine dần. Sau 12-32 iteration, converge
   thành flow chính xác.

Ưu thế:
- Không cần multi-scale (khác Farneback/PWC-Net).
- Học được biểu diễn tương quan tốt hơn heuristic thủ công.
- SOTA trên KITTI, Sintel.

### 4.3. Onnx Runtime providers CUDA/DirectML/CPU chọn thế nào?

Xem server `inference.py`:

```python
providers = []
if CUDA_AVAILABLE: providers.append('CUDAExecutionProvider')
if DML_AVAILABLE:  providers.append('DmlExecutionProvider')
providers.append('CPUExecutionProvider')
InferenceSession(model_path, providers=providers)
```

ONNX Runtime tự chọn provider **theo thứ tự ưu tiên**, fallback nếu provider trên
không khả dụng:
- **CUDAExecutionProvider**: NVIDIA GPU, nhanh nhất (nếu có CUDA + cuDNN).
- **DmlExecutionProvider (DirectML)**: chạy trên bất kỳ GPU nào có DirectX 12 (Intel,
  AMD, cả Nvidia). Trên Windows là tự nhiên nhất.
- **CPUExecutionProvider**: fallback cuối cùng, chậm nhưng luôn chạy.

Điều này giúp server portable — chạy trên laptop demo hoặc server GPU đều được.

### 4.4. Input/Output tensor của RAFT là gì?

- **Input**: 2 tensor `NCHW float32`, shape `[1, 3, 360, 480]`. Giá trị BGR chuyển
  sang RGB, chuẩn hoá [0, 1] hoặc [-1, 1] tuỳ model. Server dùng RGB [0, 255] rồi
  scale nội bộ theo config model.
- **Output**: tensor 2 kênh flow `[1, 2, 360, 480]` (dx, dy tính bằng pixel ở scale 480×360).
  Trước khi vẽ, phải scale ngược lên full-size của frame gốc bằng `xScale, yScale`.

### 4.5. Vì sao input phải resize về 480×360?

RAFT phát hành nhiều biến thể; model đã export ONNX trong đồ án được train ở 480×360
(hoặc 512×384). Nếu đổi size, cần padding sao cho `H, W chia hết cho 8` (vì downsample
1/8 trong feature encoder). Do đó server fix cứng 480×360 rồi scale ngược ở postprocess.

### 4.6. Server dùng chunked upload — vì sao?

Video 5-10 phút → 50-200 MB. Một request duy nhất dễ:
- Timeout HTTP.
- Chiếm RAM server (buffer toàn bộ).
- Retry lại toàn bộ nếu lỗi mạng.

**Chunked upload** (giống S3 multipart, YouTube upload):
1. `POST /uploads` → server trả về `uploadId`.
2. Client chia file thành chunk (~4 MB), gửi từng `PUT /uploads/{id}/chunks/{index}`.
3. `POST /uploads/{id}/complete` → server ghép chunk theo thứ tự index, tạo job.
4. Client poll `GET /jobs/{id}` để lấy progress.
5. Khi xong, `GET /jobs/{id}/result` để tải video output.

Ưu điểm:
- Có thể **resume** nếu mất mạng.
- Cho phép **progress bar** chính xác.
- Server giữ RAM ổn định (chỉ buffer 1 chunk).

### 4.7. Semaphore giới hạn concurrency trên server có ý nghĩa gì?

Server dùng `asyncio.Semaphore` để giới hạn số job xử lý song song. Vì:
- GPU chỉ có 1 (hoặc vài) đơn vị compute, nếu 10 job cùng chạy → tranh chấp memory,
  crash `CUDA out of memory`.
- CPU inference cũng vậy — chạy quá 4-8 process song song không nhanh hơn mà còn chậm
  do context switch.

Concurrency limit = số job đồng thời tối ưu → phần còn lại chờ trong queue (`status: queued`).

### 4.8. Vì sao vẽ vector trên server dùng LUT turbo + shadow layer?

Xem `inference.py:draw_vectors`. Grid step tương tự Farneback client-side. Điểm khác:
- **Percentile motion threshold**: thay vì `minMagnitude` cố định, ngưỡng = `percentile(58) × 0.6`.
  Tức là ~40% pixel mạnh nhất được xem là active. Điều này tự động thích nghi với
  video có nhiều/ít chuyển động.
- **Turbo LUT indexed by strength**: màu mũi tên = `LUT[normalized_magnitude * 255]`.
  Vector mạnh → đỏ, vector yếu → xanh dương. Cung cấp thêm chiều thông tin.
- **Shadow layer**: vẽ mũi tên đen với thickness lớn hơn 2px trước, rồi vẽ mũi tên
  màu lên trên. Giúp mũi tên nổi bật trên nền sáng.

### 4.9. `select_flow_output_index` và `extract_flow_channels` giải quyết vấn đề gì?

Model RAFT khác nhau xuất output khác:
- 1 output tensor 4D `[N, 2, H, W]`.
- 1 output tensor 4D `[N, H, W, 2]` (NHWC).
- Nhiều output (RAFT gốc trả về flow ở nhiều scale).

Server chọn output có **2 kênh và độ phân giải lớn nhất** làm flow chính, dùng
`extract_flow_channels` để chuẩn hoá về `[H, W, 2]` (float32), thay `NaN/Inf` bằng 0.
Đảm bảo backend robust với đa dạng model ONNX bên ngoài (RAFT2023, quantized, dequant,
int8bq).

---

## 5. IMU Estimator

### 5.1. IMUEstimator dùng những cảm biến nào? Mục đích?

Xem `function/optical_flow/classes/IMUEstimator.kt`. Sử dụng:
- `TYPE_GRAVITY` (nếu thiết bị hỗ trợ) hoặc **low-pass α = 0.8** từ `TYPE_ACCELEROMETER` để
  trích trọng lực.
- `TYPE_ACCELEROMETER` — gia tốc thô 3 trục (m/s²), gồm cả trọng lực.
- `TYPE_GYROSCOPE` — vận tốc góc (rad/s).
- `TYPE_MAGNETIC_FIELD` — từ trường Trái Đất (μT).

Mục đích: cung cấp **prior motion** (chuyển động dự đoán) độc lập với optical flow,
làm nguồn dead-reckoning khi mất GNSS và giúp phân biệt turn thật/false-positive.

### 5.2. `linearAcceleration = raw - gravity` — vì sao?

Accelerometer đo cả **trọng lực** lẫn **gia tốc chuyển động**. Ví dụ điện thoại nằm
im trên bàn: `raw = (0, 0, 9.81)` — không có chuyển động vẫn thấy 9.81 m/s² do trọng lực.

Muốn ra "chuyển động thực", phải trừ trọng lực:
```
linear_a = raw_a - g_vector
```

Nếu thiết bị có `TYPE_GRAVITY` sensor cứng (Sensor fusion do OEM cung cấp), dùng
trực tiếp. Nếu không, ước lượng bằng **low-pass filter**:
```
gravity = 0.8 × prev_gravity + 0.2 × raw_a
```
Ý tưởng: trọng lực gần như không đổi theo thời gian, còn chuyển động thay đổi nhanh.
Low-pass giữ lại tần số thấp = trọng lực.

### 5.3. Integrate acceleration → velocity ra sao? Có drift không?

Xem `IMUEstimator.kt` — velocity integration:
```
v[n] = 0.8 × (v[n-1] + a × dt) + 0.2 × gyro-projected_v
```

Đây là **discrete integration** với leaky term (0.8) để chống drift tích luỹ. Còn
`0.2 × gyro-projected_v` là hồi tiếp từ gyro để chỉnh hướng.

**Drift là gì**: accelerometer có bias ~0.01-0.1 m/s². Tích phân bias × time² → sau
10 giây, sai số vị trí có thể ~5-50m. Vì thế IMU velocity **chỉ tin cậy trong 1-3 giây**.
Cần phải reset/correct bằng nguồn khác (GNSS, visual odometry).

### 5.4. Gyro bias learning là gì?

Gyro cũng có bias (khi đứng yên vẫn báo ~0.001-0.01 rad/s). Ta học bias online bằng EWMA:
```
bias = (1 - α) × bias + α × current_gyro   khi thiết bị đang đứng yên
```
với `α = 0.05`. Điều kiện "đứng yên" dựa vào `|linear_a| < ngưỡng` và
`|gyro_raw - bias| < ngưỡng`. Sau vài giây, bias hội tụ về giá trị offset thực tế.

### 5.5. `yawRate` là gì? Tính thế nào?

`yawRate` = tốc độ quay quanh trục dọc (heading), rad/s. Bản chất là **chiếu vector
angular velocity lên hướng gravity (ngược chiều)**:
```
yawRate = -dot(gyro_bias_corrected, gravity_normalized)
```
Vì trục Z của thế giới trùng với `-gravity` (khi thiết bị đứng thẳng), thành phần
gyro dọc trục này chính là tốc độ xoay ngang. Không cần phải solve full quaternion.

Dùng để: kết hợp với `lateralCoherence` của optical flow để phát hiện rẽ chắc chắn hơn
(nếu cả 2 cùng dấu và vượt ngưỡng).

---

## 6. GNSS 4 vệ tinh

### 6.1. Vì sao cần ít nhất 4 vệ tinh để định vị GPS?

Đây là câu hỏi phản biện **thường xuyên**. Câu trả lời bản chất:

**GPS có 4 ẩn số cần giải**:
1. `x` — toạ độ máy thu (ECEF X).
2. `y` — toạ độ máy thu (ECEF Y).
3. `z` — toạ độ máy thu (ECEF Z).
4. `dt` — **độ lệch đồng hồ máy thu** so với đồng hồ nguyên tử vệ tinh.

**Mỗi vệ tinh cho 1 phương trình pseudorange**:
```
ρᵢ = √((xᵢ - x)² + (yᵢ - y)² + (zᵢ - z)²) + c · dt
```
Trong đó `ρᵢ` là khoảng cách đo được (biết), `(xᵢ, yᵢ, zᵢ)` là vị trí vệ tinh
(broadcast bởi vệ tinh, biết), `c` là tốc độ ánh sáng.

Với 4 ẩn số → cần **4 phương trình độc lập** → **4 vệ tinh**.

**Vì sao có ẩn số dt?** Vì đồng hồ trong điện thoại là thạch anh (crystal) rẻ tiền,
sai số ~10 μs/s. Nếu bỏ qua dt, sai số vị trí = `c × 10⁻⁵ = 3000 m` mỗi giây!

Vệ tinh có **đồng hồ nguyên tử** (cesium/rubidium) chính xác 10⁻¹³, gần như coi là chân lý.
Máy thu **giải đồng thời** vị trí `(x, y, z)` và độ lệch đồng hồ `dt` từ 4 pseudorange.

Nếu chỉ 3 vệ tinh → 3 phương trình 4 ẩn → **vô số nghiệm** → không định vị được.

**3 vệ tinh + biết độ cao**: có thể thay ẩn `z` bằng ràng buộc `x² + y² + z² = (R_earth + h)²`.
Chỉ dùng trong hàng hải (biết h ~ 0). Điện thoại không giả thiết được.

### 6.2. Vì sao càng nhiều vệ tinh càng chính xác?

Với 4 vệ tinh, hệ 4×4 có nghiệm duy nhất (nếu không suy biến hình học). Nhưng đo lường
có noise (multipath, ionosphere, tropospheric delay), nên nghiệm bị lệch.

Với ≥5 vệ tinh → hệ **overdetermined**, giải bằng bình phương tối thiểu:
```
x_hat = (Hᵀ W H)⁻¹ Hᵀ W ρ
```
với `H` là ma trận Jacobi hình học, `W` là ma trận trọng số (theo CN0). Càng nhiều
phương trình, sai số càng bị "trung bình hoá" → chính xác hơn.

Chất lượng còn phụ thuộc **DOP (Dilution of Precision)**:
- `GDOP < 2` — geometric tốt (vệ tinh phân bố đều trên bầu trời).
- `GDOP > 6` — xấu (vệ tinh dồn cụm).

### 6.3. Pseudorange đo bằng cách nào?

Máy thu đo **thời gian truyền tín hiệu** từ vệ tinh đến máy:
```
τ = t_receive - t_transmit
ρ = c × τ
```
- `t_transmit` được nhúng trong tín hiệu (mã C/A hoặc P), vệ tinh phát ra cùng dữ liệu
  navigation.
- `t_receive` là thời điểm máy thu decode được tín hiệu.
- `τ ~ 60-90 ms` cho vệ tinh MEO.

Gọi là **pseudo**range vì có sai số `c × dt`, không phải range thật.

### 6.4. Trong đồ án, ta có tự giải 4 phương trình này không?

**Không**. Android system service `LocationManager` + chipset GNSS đã làm điều đó.
Đồ án nhận vị trí đã tính (lat, lon, alt) qua `Location` object và **tập trung vào
việc tính vị trí VỆ TINH** để render 3D — đó là bài toán ngược: cho epoch time,
tính vị trí vệ tinh trong ECEF, sau đó biến đổi để hiển thị trên globe.

### 6.5. Constellation types (GPS, GLONASS, Galileo, BeiDou, QZSS, IRNSS, SBAS) khác nhau ra sao?

| System | Quốc gia | Số vệ tinh | Quỹ đạo | Ghi chú |
|--------|---------|-----------|---------|---------|
| GPS | Mỹ | 31 | MEO, 20,200 km | Đầu tiên (1978), phổ biến nhất |
| GLONASS | Nga | 24 | MEO, 19,100 km | Frequency Division Multiplexing (mỗi vệ tinh tần số riêng) |
| Galileo | EU | 30 (kế hoạch) | MEO, 23,222 km | Cho dân sự, độ chính xác cao |
| BeiDou | Trung Quốc | 35 | MEO+IGSO+GEO | Có cả GEO đứng yên |
| QZSS | Nhật | 4 | HEO tựa 8-figure | Chỉ phủ Nhật + Australia |
| IRNSS/NavIC | Ấn Độ | 7 | GEO+IGSO | Chỉ Ấn Độ + lân cận |
| SBAS | Không phải constellation riêng | GEO (WAAS, EGNOS, MSAS, GAGAN) | Gửi correction cho GPS |

App đọc **tất cả constellation** qua `GnssStatus` — mỗi vệ tinh có `constellationType`
để biết thuộc hệ nào.

### 6.6. `SBAS` (Satellite-Based Augmentation System) là gì?

SBAS **không phải hệ định vị độc lập**, mà là các vệ tinh GEO phát tín hiệu **điều
chỉnh** cho GPS/Galileo. Chúng gửi:
- Ionospheric correction cho vùng phủ.
- Ephemeris error correction.
- Integrity monitoring (báo vệ tinh nào đang bị lỗi).

Các SBAS chính:
- **WAAS** (US) — vùng Bắc Mỹ.
- **EGNOS** (Europe).
- **MSAS** (Japan).
- **GAGAN** (India).
- **SDCM** (Russia).

App tại Việt Nam thường **không thấy** SBAS vì không có SBAS phủ Đông Nam Á.

---

## 7. GNSS 4 tầng ưu tiên

### 7.1. Vì sao chia thành 4 tầng nguồn dữ liệu vị trí vệ tinh?

Xem `GnssSatelliteTracker.kt:resolvePosition`:

```
1. PVT (Position/Velocity/Time từ chipset)  — tin cậy nhất, cần Android ≥ 12 + chipset hỗ trợ
2. IGS Broadcast Ephemeris                  — tải RINEX từ IGS/CDDIS, chính xác ~1m
3. CelesTrak SGP4                            — TLE từ CelesTrak, chính xác ~1km
4. Approximate (Kepler thủ công)            — chỉ dùng constellation type mặc định
```

Lý do:
- **Tầng 1 (PVT)**: chipset đã giải tất cả (position ECEF, velocity, clock), độ chính
  xác cỡ meter. Nhưng chỉ Android ≥ 12, phải dùng **reflection** vì API còn hidden.
- **Tầng 2 (IGS)**: tải file RINEX navigation từ IGS/CDDIS, giải Kepler theo lịch bay
  chính thức. Chính xác vài mét, đủ cho hiển thị đúng vị trí thực.
- **Tầng 3 (SGP4)**: dùng khi vệ tinh không có trong IGS (LEO satellites, hoặc IGS
  thiếu dữ liệu). TLE là bộ tham số quỹ đạo public trên CelesTrak.
- **Tầng 4 (Approximate)**: fallback cuối, dùng khi mất mạng ban đầu — tính vị trí giả
  định trên vòng tròn quỹ đạo trung bình.

### 7.2. Vì sao PVT phải dùng reflection?

Google chưa mở public API `Location.hasSatellitePvt()` / `getSatellitePvt()` cho tất
cả cấp Android. Nó tồn tại từ Android 12 (API 31) nhưng bị đánh dấu `@hide`. Reflection
là cách duy nhất truy cập:

```kotlin
val method = LocationManager::class.java.getDeclaredMethod("getSatellitePvt", ...)
method.isAccessible = true
val pvt = method.invoke(locationManager, satelliteInfo)
```

Rủi ro: nếu Google đổi tên method, code break. Đồ án kiểm tra `Build.VERSION.SDK_INT`
và bắt exception cẩn thận.

### 7.3. Broadcast Ephemeris chính xác đến mức nào?

RINEX navigation file phát bởi IGS gồm **các tham số Kepler + hiệu chỉnh phi Kepler**:
- 6 tham số quỹ đạo cổ điển (a, e, i, Ω, ω, M₀).
- Các số hạng nhiễu loạn (Δn, Cuc, Cus, Crc, Crs, Cic, Cis) để tính chính xác đến sub-meter.
- Đồng hồ vệ tinh (af0, af1, af2).

Có hiệu lực ~2-4 giờ, sau đó phải tải mới. App cache `BROADCAST_MAX_AGE_MS = 12h` để
không tải lại quá thường xuyên.

### 7.4. Vì sao PVT_STALE_THRESHOLD_NANOS = 10 giây?

PVT được cập nhật liên tục từ chipset. Nhưng nếu > 10s không có PVT mới (chipset không
báo cáo), coi là stale và fallback xuống tầng 2. 10s đủ dài để tránh flicker khi
mất tín hiệu tạm thời (đi trong tunnel ngắn), đủ ngắn để không hiển thị dữ liệu quá cũ.

### 7.5. IgsBroadcastEphemerisPropagator: vì sao GLONASS xử lý riêng?

GPS/Galileo/BeiDou dùng **Kepler orbit model** trong ECEF (WGS84). GLONASS dùng
**PZ-90.11 datum** và **ephemeris kiểu positions/velocities/accelerations** thay vì
Kepler. Nó tích phân số học phương trình đại số:

```
d²r/dt² = -μ/r³ · r + accelerations
```

Orekit cung cấp `GLONASSNumericalPropagator` riêng, dùng bộ integrator ODE (Runge-Kutta 4)
để tích phân từ epoch tham chiếu đến epoch cần tính. Sau đó chuyển đổi PZ-90.11 → WGS84
bằng ma trận Helmert 7-parameter transformation.

---

## 8. Toán học quỹ đạo và WGS84

### 8.1. Kepler's equation là gì? Vì sao phải giải?

Kepler: **quỹ đạo hành tinh/vệ tinh là ellipse**, Trái Đất ở một tiêu điểm. 6 tham số
xác định quỹ đạo:
- `a` — semi-major axis (trục lớn).
- `e` — eccentricity (độ dẹt).
- `i` — inclination (góc nghiêng so với xích đạo).
- `Ω` — right ascension of ascending node (kinh độ nút lên).
- `ω` — argument of perigee (góc từ nút lên đến cận điểm).
- `M₀` — mean anomaly at epoch (góc trung bình tại thời điểm gốc).

Cho epoch time `t`, ta biết **mean anomaly**:
```
M(t) = M₀ + n · (t - t₀), với n = √(μ / a³)
```

Nhưng cần **eccentric anomaly E** để tính vị trí thực. Phương trình Kepler:
```
M = E - e · sin(E)
```

Không giải được đóng (transcendental), phải giải số bằng **Newton-Raphson**:
```
E_new = E - (E - e·sin(E) - M) / (1 - e·cos(E))
```

Xem `SatelliteCalculator.kt:solveKepler`, dùng **10 iteration** — với `e < 0.02`
(vệ tinh GPS), hội tụ sau 3-4 iteration, 10 iteration là dư dả.

### 8.2. Sau khi có E, tính vị trí vệ tinh trong ECEF ra sao?

1. **True anomaly** ν:
   ```
   tan(ν/2) = √((1+e)/(1-e)) · tan(E/2)
   ```
2. **Radius**:
   ```
   r = a(1 - e·cos(E))
   ```
3. **Toạ độ trong mặt phẳng quỹ đạo**:
   ```
   x' = r·cos(ν)
   y' = r·sin(ν)
   ```
4. **Quay 3 lần để về ECEF**:
   - Quay `-ω` quanh Z (đưa cận điểm về trục X).
   - Quay `-i` quanh X (nghiêng mặt phẳng quỹ đạo về mặt phẳng xích đạo).
   - Quay `-Ω` quanh Z (đưa nút lên về hướng gốc).
   - Cuối cùng, phải trừ **rotation của Trái Đất** vì ECEF quay:
     ```
     θ_earth = ω_earth · (t - t₀), với ω_earth = 7.2921150e-5 rad/s
     ```
     → quay quanh Z thêm góc `-θ_earth`.

### 8.3. ECEF ↔ Lat/Lon/Alt (WGS84) — thuật toán Bowring?

**ECEF** (Earth-Centered, Earth-Fixed) là hệ toạ độ Descartes, gốc tâm Trái Đất, trục Z
qua Bắc Cực, trục X qua điểm giao Greenwich với xích đạo.

**LLA** (Latitude, Longitude, Altitude) là toạ độ địa lý thông thường.

Chuyển ECEF → LLA đơn giản cho Lon:
```
lon = atan2(y, x)
```

Với Lat và Alt phải iteration vì Trái Đất là **ellipsoid** không phải cầu. WGS84:
- `a = 6378137 m` (bán trục lớn xích đạo).
- `f = 1/298.257223563` (flattening).
- `b = a(1-f) = 6356752.314 m` (bán trục nhỏ, hai cực).
- `e² = 2f - f² = 0.006694...` (eccentricity²).

**Thuật toán Bowring** (closed-form, 1 lần iteration là đủ):
```
p = √(x² + y²)
θ = atan2(z·a, p·b)
lat = atan2(z + e'²·b·sin³(θ), p - e²·a·cos³(θ))
N = a / √(1 - e²·sin²(lat))
alt = p / cos(lat) - N
```

Xem `SatelliteCalculator.kt:ecefToLla`.

### 8.4. GMST là gì? Vì sao cần?

**GMST** = Greenwich Mean Sidereal Time — góc quay của Trái Đất so với **các sao cố định**
(không phải Mặt Trời). Ngày sidereal = 23h56m4s, ngắn hơn ngày mặt trời ~4 phút.

Cần GMST để chuyển từ hệ **TEME/ECI** (inertial, gắn với các sao) sang **ECEF**
(gắn với mặt đất):
```
ECEF_xy = R_z(GMST) · ECI_xy
```

Nghĩa là để hiển thị vệ tinh đúng vị trí trên globe, ta phải **quay hệ inertial đi
một góc GMST** để đồng bộ với Trái Đất đang quay.

Công thức GMST (IAU 1982, đơn giản hoá):
```
GMST(rad) = 18.697374558 + 24.06570982441908 · D  (giờ)
```
với `D` = số ngày Julian từ J2000.0.

Trong đồ án, xem `SatelliteCalculator.kt:computeGmst`.

### 8.5. Julian Date là gì? Vì sao dùng?

Astronomers dùng **Julian Date (JD)** — số ngày liên tục từ ngày 1/1/4713 BC 12:00 UT.
Ưu điểm: **liền mạch**, không cần lo tháng, năm nhuận, timezone.

- **JD 2440587.5** = 1/1/1970 00:00 UTC (Unix epoch).
- **J2000.0** = JD 2451545.0 = 1/1/2000 12:00 UTC — epoch chuẩn hiện đại.

Công thức: `JD = 2440587.5 + unix_time / 86400`.

### 8.6. TLE (Two-Line Element) là gì? Vì sao dùng cho SGP4?

TLE là format text 2 dòng chứa **tham số quỹ đạo trung bình** ở epoch. Ví dụ ISS:
```
1 25544U 98067A   24001.12345678 -.00001764  00000-0 -32908-4 0  9990
2 25544  51.6423  10.7654 0002123 45.6789 12.3456 15.49812345 34567
```

Đọc được:
- Dòng 1: satellite number, epoch (year + day), drag terms.
- Dòng 2: inclination, RAAN, eccentricity, argument of perigee, mean anomaly, mean motion.

**SGP4** (Simplified General Perturbations #4) là bộ propagator do NASA/NORAD phát triển
để tính vị trí từ TLE, tính đến nhiễu loạn `J2, J3, J4` (gương phẳng Trái Đất), drag khí quyển.

Đồ án dùng **Orekit's TLE propagator** — cache 256 entry LRU vì tính toán SGP4 tốn CPU.
CelesTrak cập nhật TLE mỗi ngày.

### 8.7. `inertialSpeed` bù rotation Trái Đất là gì?

Xem `SatelliteCalculator.kt:inertialSpeed`. Vấn đề: khi ta tính velocity vệ tinh trong
ECEF (rotating frame), nó **không phải velocity thật** so với inertial frame vì Trái
Đất đang quay.

Muốn hiển thị "speed thực" (~7 km/s cho GPS), phải cộng vector Coriolis:
```
v_inertial = v_ecef + ω × r
```
với `ω = (0, 0, 7.2921150e-5)` rad/s, `r` là vị trí ECEF.

Nếu bỏ qua, sẽ thấy vệ tinh GEO có `v = 0` (đúng trong ECEF vì đứng yên trên mặt đất
so với mặt đất), nhưng thực tế trong inertial nó bay ~3 km/s.

---

## 9. Mặt Trời và Mặt Trăng (Meeus algorithm)

### 9.1. Vì sao app tính vị trí Mặt Trời/Mặt Trăng?

Xem `EarthRenderer.kt`. Để render **AR globe chân thực**:
- Mặt Trời chiếu sáng half-Earth → dùng để tính vector chiếu sáng shader.
- Mặt Trăng hiện thực có pha (crescent, gibbous) → cần biết vị trí Mặt Trời để tính pha.
- Vị trí ngày/đêm trên Trái Đất đúng theo giờ hiện tại.

### 9.2. Thuật toán Meeus tính Mặt Trời ra sao?

Từ sách "Astronomical Algorithms" của Jean Meeus (1998), độ chính xác ~0.01°.

1. **Julian Day**:
   ```
   T = (JD - 2451545.0) / 36525  (thế kỷ Julian từ J2000)
   ```
2. **Mean longitude of Sun** (độ):
   ```
   L₀ = 280.46646 + 36000.76983·T + 0.0003032·T²
   ```
3. **Mean anomaly**:
   ```
   M = 357.52911 + 35999.05029·T - 0.0001537·T²
   ```
4. **Equation of center** (correction cho quỹ đạo ellipse):
   ```
   C = (1.914602 - 0.004817·T)·sin(M)
     + (0.019993 - 0.000101·T)·sin(2M)
     + 0.000289·sin(3M)
   ```
5. **True longitude**:
   ```
   λ = L₀ + C
   ```
6. **Right ascension và declination** (đổi sang toạ độ xích đạo):
   ```
   α = atan2(cos(ε)·sin(λ), cos(λ))
   δ = asin(sin(ε)·sin(λ))
   ```
   với `ε = 23.4393°` là độ nghiêng trục Trái Đất.

Cuối cùng biến đổi α, δ → vector 3D trong world space để shader.

### 9.3. Thuật toán Meeus tính Mặt Trăng ra sao?

Phức tạp hơn Mặt Trời vì quỹ đạo Mặt Trăng bị nhiễu loạn bởi Mặt Trời và độ dẹt Trái
Đất. Cần **các tham số fundamental**:
- `L` — mean longitude of Moon.
- `D` — mean elongation Moon-Sun.
- `M` — mean anomaly of Sun.
- `M'` — mean anomaly of Moon.
- `F` — argument of latitude (từ nút lên).

Vị trí longitude:
```
λ = L + 6.289·sin(M') + 0.214·sin(2M') + 0.658·sin(2F) + ...
```

Đây là **truncated series** — chuỗi các số hạng chu kỳ. Meeus phiên bản đầy đủ có 60+
số hạng đạt độ chính xác 0.5" (giây cung), nhưng đồ án dùng 5-6 số hạng chính, đủ hiển thị.

Latitude ~ ±5° (mặt phẳng quỹ đạo Mặt Trăng nghiêng 5° so với ecliptic):
```
β ≈ 5.128·sin(F) + 0.281·sin(M'+F) + ...
```

### 9.4. Vì sao dùng tọa độ **ecliptic** trước rồi mới đổi sang equatorial?

Ecliptic (hoàng đạo) = mặt phẳng quỹ đạo Trái Đất quanh Mặt Trời. Rất tự nhiên để mô tả
chuyển động Mặt Trời/Mặt Trăng.

Equatorial (xích đạo) = mặt phẳng xích đạo Trái Đất. Cần cho rendering vì Earth model
xoay quanh trục cực.

Chuyển đổi bằng ma trận quay quanh trục X một góc `ε = 23.44°`.

---

## 10. Map3DInformationDialog

Dialog này hiển thị mọi thông tin về **một vệ tinh** khi user tap chọn nó trên globe 3D.
Từng biến trong `Map3DInformationDialog.kt`:

### 10.1. `totalSatellites` — Tổng số vệ tinh đang nhận

Tổng số vệ tinh mà app đang "thấy" (có tín hiệu, bất kể có được dùng để định vị hay
không). Bao gồm cả các constellation. Điển hình tại VN: 20-40 vệ tinh.

### 10.2. `svid` (Space Vehicle ID)

**Số hiệu vệ tinh** trong hệ constellation của nó. Ví dụ:
- GPS: 1-32 (mỗi số ứng với 1 vệ tinh cụ thể).
- GLONASS: 1-24.
- Galileo: 1-30.
- BeiDou: 1-63.

Kết hợp `svid + constellationType` mới xác định duy nhất một vệ tinh.

### 10.3. `constellationType`

Enum `GnssStatus.CONSTELLATION_*`:
- `CONSTELLATION_GPS` (1)
- `CONSTELLATION_SBAS` (2)
- `CONSTELLATION_GLONASS` (3)
- `CONSTELLATION_QZSS` (4)
- `CONSTELLATION_BEIDOU` (5)
- `CONSTELLATION_GALILEO` (6)
- `CONSTELLATION_IRNSS` (7)

App map sang tên đọc được.

### 10.4. `cn0DbHz` (Carrier-to-Noise density ratio)

**Cường độ tín hiệu** = tỉ số công suất sóng mang trên mật độ nhiễu, đơn vị dB-Hz.

- **< 20 dB-Hz**: rất yếu, gần như không tracking được.
- **25-35**: yếu, có thể mất dấu.
- **35-45**: tốt.
- **> 45**: xuất sắc, lộ thiên trực tiếp.

CN0 khác **SNR** ở chỗ nó chuẩn hoá theo băng thông (Hz), vì tín hiệu GPS được điều
chế trải phổ (spread spectrum) trên băng ~2 MHz. CN0 là chỉ số **so sánh được** giữa
các máy thu khác nhau.

### 10.5. `elevationDegrees`

**Góc ngẩng** = góc giữa vệ tinh và mặt phẳng ngang cục bộ, tại vị trí máy thu.
- 0° = ngay đường chân trời.
- 90° = ngay đỉnh đầu (zenith).

Vệ tinh elevation < 10° thường bị **atmospheric delay** lớn nhất và multipath từ mặt đất,
nên máy thu thường loại khỏi lời giải PVT.

### 10.6. `azimuthDegrees`

**Góc phương vị** = hướng từ máy thu đến vệ tinh chiếu lên mặt phẳng ngang, đo từ Bắc
theo chiều kim đồng hồ.
- 0° = Bắc.
- 90° = Đông.
- 180° = Nam.
- 270° = Tây.

Kết hợp (elevation, azimuth) → **tọa độ ngang địa phương** — đủ để định vị vệ tinh
trên bầu trời từ góc nhìn máy thu.

### 10.7. `carrierFrequencyHz`

Tần số sóng mang tín hiệu, đơn vị Hz. GNSS phát nhiều băng tần:
- **GPS L1**: 1575.42 MHz (dân sự C/A).
- **GPS L2**: 1227.60 MHz (quân sự P(Y), dân sự L2C).
- **GPS L5**: 1176.45 MHz (aviation safety).
- **Galileo E1**: 1575.42 MHz (trùng L1).
- **Galileo E5**: 1191.795 MHz.
- **GLONASS G1**: 1602 MHz + k·0.5625 MHz (mỗi vệ tinh khác nhau — FDMA).

Nếu N/A → chipset không expose tần số cho vệ tinh đó.

### 10.8. `usedInFix`

Boolean: vệ tinh này **có** được dùng để giải PVT hiện tại không? Nếu `false`, có thể do:
- Elevation quá thấp.
- CN0 quá yếu.
- Chưa decode được ephemeris.
- Máy thu quyết định không cần (đã đủ 8 vệ tinh khác).

### 10.9. `positionSource`

Nguồn dữ liệu vị trí vệ tinh, một trong:
- `"PVT"` — từ chipset (chính xác nhất).
- `"IGS Broadcast"` — từ RINEX file IGS.
- `"CelesTrak SGP4"` — từ TLE + SGP4.
- `"CelesTrak GP"` — từ CelesTrak nhưng dùng approximation (Kepler thủ công).
- `"Approximate"` — fallback cuối.

Cho biết **độ tin cậy** của toạ độ đang hiển thị.

### 10.10. `latitude / longitude / altitude` (của vệ tinh)

Toạ độ **của vệ tinh** trong hệ WGS84:
- `latitude ∈ [-90°, 90°]` — vĩ độ subpoint (điểm ngay dưới vệ tinh trên mặt Trái Đất).
- `longitude ∈ [-180°, 180°]` — kinh độ subpoint.
- `altitude` — độ cao **trên bề mặt Trái Đất** (không phải độ cao trên mực nước biển):
  - GPS MEO: ~20,200 km.
  - GLONASS MEO: ~19,100 km.
  - Galileo MEO: ~23,222 km.
  - GEO (SBAS, BeiDou GEO): ~35,786 km.
  - LEO: 400-2000 km.

Format `%,.0f` in số kèm phân tách hàng nghìn (`20,200`).

### 10.11. `speed` (của vệ tinh)

Tốc độ vệ tinh, đơn vị m/s. Hiển thị dưới dạng `km/s` và `km/h`.

Định luật III Kepler: `v = √(μ/r)`. Với μ = 3.986e14 m³/s² (Earth's gravity parameter):
- GPS MEO (r=26,600 km): v ≈ 3.87 km/s ≈ 13,900 km/h.
- LEO (r=6800 km): v ≈ 7.66 km/s ≈ 27,600 km/h.
- GEO (r=42,164 km): v ≈ 3.07 km/s ≈ 11,000 km/h (nhưng so với mặt đất = 0).

App sử dụng `inertialSpeed` (bao gồm cả rotation Trái Đất) để hiển thị đúng "tốc độ thực".

### 10.12. `ephemerisSource` (nội bộ, không hiển thị nhưng có ý nghĩa)

Trong `SatelliteInfo`, có thêm trường `ephemerisSource ∈ {0, 1, 2, 3}` (từ PVT resolver):
- `0` — Broadcast (từ chính vệ tinh).
- `1` — Server normal (từ Google Assisted GPS server).
- `2` — Server long-term (predicted ephemeris, dùng khi mất mạng dài).
- `3` — Other/unknown.

---

## 11. AR Rendering

### 11.1. GLSurfaceView và OpenGL ES 3.2 là gì?

- **GLSurfaceView** là view Android cho phép render OpenGL ES ở thread riêng
  (`GLThread`), không block UI thread.
- **OpenGL ES 3.2** là chuẩn OpenGL cho embedded (mobile), version 3.2 hỗ trợ:
  - Compute shaders.
  - Geometry shaders.
  - Tessellation.
  - MSAA render buffer.
  - Đủ mạnh để render globe + skybox + shading advanced.

### 11.2. Bản chất Model-View-Projection matrix?

Rendering 3D trong OpenGL cần 3 matrix nối tiếp:

1. **Model matrix M** — biến hình từ **local space** (toạ độ trong định nghĩa mesh)
   sang **world space** (toạ độ chung của scene). Gồm translate, rotate, scale.

2. **View matrix V** — biến từ world space sang **camera space** (camera ở gốc, nhìn
   theo trục -Z). `Matrix.setLookAtM(V, 0, eye, center, up)`.

3. **Projection matrix P** — biến từ camera space (3D) sang **clip space** (4D
   homogeneous). Sau khi divide by W → NDC ([-1, 1]³). App dùng:
   ```kotlin
   Matrix.perspectiveM(P, 0, 45.0f, aspect, 0.1f, 20f)
   ```
   - `45°` fov (góc mở dọc).
   - `aspect = width/height`.
   - `near = 0.1, far = 20` (world units).

Trong vertex shader:
```glsl
gl_Position = P * V * M * vec4(position, 1.0);
```

### 11.3. Camera trong `EarthRenderer` dùng toạ độ cầu (spherical)?

Xem `EarthRenderer.kt`. Camera có 3 tham số:
- `theta ∈ [0, 2π]` — góc quay quanh trục Y (kinh độ camera).
- `phi ∈ [ε, π-ε]` — góc từ cực Bắc xuống (vĩ độ, tránh singularity).
- `scale` — khoảng cách từ camera đến tâm Earth (zoom).

Chuyển sang Cartesian:
```
eye = (r·sin(phi)·cos(theta), r·cos(phi), r·sin(phi)·sin(theta))
```

Ưu điểm: user pan/zoom bằng touch → chỉ update θ, φ, scale, giữ nguyên `center = (0,0,0)`.
Không có gimbal lock nhờ kẹp phi.

### 11.4. Bản chất SphereMesh — dựng cầu Earth ra sao?

Xem `SphereMesh.kt`. Tạo mesh cầu bằng **UV subdivision**:
- Chia longitude `[0, 2π]` thành `n` bước (n=64-128 tuỳ chất lượng).
- Chia latitude `[-π/2, π/2]` thành `m` bước.
- Mỗi ô UV → 2 tam giác (2 triangle strip).
- Vertex: `(cos(lat)·cos(lon), sin(lat), cos(lat)·sin(lon))`.
- UV: `(lon/2π, lat/π + 0.5)`.

Texture Earth (2K/4K JPG) map vào UV → hiện cả lục địa/đại dương đúng vị trí.

### 11.5. Skybox là gì?

Xem `Skybox.kt`. Skybox = **hình lập phương lớn bao quanh scene**, mặt trong dán texture
bầu trời sao. Vertex shader triệt tiêu translation của view matrix:
```glsl
vec4 pos = P * mat4(mat3(V)) * vec4(position, 1.0);
gl_Position = pos.xyww;  // depth = 1 (xa nhất)
```

Kết quả: skybox luôn xa nhất, không bao giờ va chạm objects, camera xoay chỉ thấy sao
chuyển động → cảm giác không gian rộng.

### 11.6. Atmosphere halo (viền quyển khí) dựng thế nào?

Xem `EarthRenderer`. Trick phổ biến: **fake atmosphere shader**:
- Vẽ thêm 1 quả cầu bán trong suốt, bán kính lớn hơn Earth ~2-5%.
- Fragment shader tính `dot(normal, view_dir)`, `dot(normal, sun_dir)`:
  - Càng gần rìa (silhouette) → alpha càng cao → phần rìa sáng lên.
  - Kèm màu **Rayleigh scattering** (blueish khi nhìn phía có Mặt Trời).

Không phải volumetric thật, chỉ shader analytic — nhưng nhìn rất đẹp và rẻ.

### 11.7. Country label dựng thế nào?

Xem `EarthRenderer`. Với mỗi quốc gia lớn, có bảng `(lat, lon, name)`. Runtime:
1. Chuyển `(lat, lon)` → world position trên bề mặt Earth (nhân với bán kính globe).
2. Chiếu qua MVP → NDC.
3. Kiểm tra **backface culling**: `dot(normal, view_dir) > 0` → label ẩn phía sau Earth.
4. Dùng SDF text hoặc bitmap font, vẽ 2D overlay tại screen position.

### 11.8. Vệ tinh render như boxes + solar panels — vì sao?

Vệ tinh có kích thước ~vài mét — nếu vẽ đúng scale, sẽ vô hình so với Earth (12,756 km).
Vì thế app **exaggerate kích thước** và render dạng biểu tượng:
- Thân: hộp nhỏ.
- 2 panel: hình chữ nhật kéo sang bên.
- Text SVID nổi bên cạnh.

**Altitude compression**: `rSat = 0.15 + 0.02·normalizedAlt` (Earth world radius = 0.1).
Nếu render đúng khoảng cách thực, GEO cách Earth 5 lần đường kính Earth → không vừa
màn hình. Compression giữ mọi vệ tinh trong 1 dải hẹp, dễ nhìn.

### 11.9. GNSSARFragment — AR overlay lên camera thực tế?

`GNSSARFragment` mở camera sau, hiển thị **preview live** + overlay các mũi tên chỉ vệ
tinh. Bản chất:
1. **Rotation vector** từ `SensorManager.TYPE_ROTATION_VECTOR` → matrix xoay `R` giữa
   world frame (East-North-Up) và device frame.
2. Với mỗi vệ tinh (elevation, azimuth), tính vector ENU:
   ```
   x = cos(el)·sin(az)   (East)
   y = cos(el)·cos(az)   (North)
   z = sin(el)            (Up)
   ```
3. Nhân `R⁻¹ · v` để về device frame → dùng làm 3D point trong camera space.
4. Chiếu qua projection matrix của **camera thật** (có FOV riêng của phone camera).
5. Nếu z > 0 (trước camera) → vẽ mũi tên tại (screen_x, screen_y).

Kết quả: hướng phone lên trời, thấy các vệ tinh đúng chỗ chúng đang bay.

---

## 12. Live Routing & Dead Reckoning

### 12.1. Live Routing làm gì?

`LiveRoutingViewModel` tổ chức luồng dữ liệu định vị realtime:
1. Nhận `Location` từ GPS provider.
2. So khớp với **route đã plan** (bằng OpenStreetMap/Mapbox).
3. Hiển thị đường đi thật (path đã đi qua) trên map.
4. Phát hiện lệch route → prompt reroute.
5. Xử lý **mất GNSS** bằng dead reckoning.

### 12.2. Weak/Strong markers và red/black paths là gì?

- **Strong marker**: điểm GPS chất lượng tốt (accuracy < 15m, có PVT).
- **Weak marker**: điểm suy đoán (từ dead reckoning, hoặc GPS accuracy > 30m).
- **Red path**: đoạn đường **có GPS tốt** — vẽ đỏ để nổi bật.
- **Black path**: đoạn đường **thiếu GPS**, chỉ dựa IMU + optical flow — vẽ đen để user
  biết đây là ước lượng.

Ý nghĩa: user thấy rõ đoạn nào đáng tin, đoạn nào cần cẩn trọng.

### 12.3. Dead reckoning là gì? Vì sao cần?

**Dead reckoning** = ước lượng vị trí bằng **hướng + tốc độ + thời gian**, không cần GPS.
```
new_lat = old_lat + (v·dt·cos(heading)) / R_earth
new_lon = old_lon + (v·dt·sin(heading)) / (R_earth·cos(lat))
```

Cần khi:
- Đi qua tunnel (GPS không xuyên).
- Trong nhà (indoor).
- Bị che (rừng rậm, đô thị canyon).

Vấn đề: không có nguồn ngoài → sai số **tích luỹ theo thời gian**. IMU thuần drift
~1-10 m/s. Ta bổ sung optical flow làm visual odometry.

### 12.4. Visual odometry từ optical flow ra sao?

Ý tưởng: nếu camera nhìn mặt đường, tốc độ pixel dịch (`translationFlowPxPerSec`) tỷ lệ
với tốc độ xe:
```
v_mps ≈ flow_pxps × dynamicFlowToMpsRatio
```

`dynamicFlowToMpsRatio` được **học** từ giai đoạn có GPS: khi biết cả `v_gps` và
`flow_pxps`, tính `ratio = v_gps / flow_pxps`, lấy trung bình trượt. Khi mất GPS → dùng
ratio đã học × flow hiện tại.

Điểm mấu chốt: ratio phụ thuộc **độ cao camera so với mặt đường** và **pitch camera**.
Nếu user thay đổi tư thế điện thoại → ratio không còn đúng → sai. Vì thế app còn kết
hợp IMU forward-axis learning để phát hiện đổi tư thế.

### 12.5. Cog trust speed là gì?

**COG (Course Over Ground)** = hướng đi thực tế (từ GPS). Khi tốc độ **thấp** (< 1 m/s),
COG không đáng tin (nhiễu do noise, nhất là khi đứng yên). App gate:
```
if (v_gps > COG_TRUST_SPEED_MPS) use_cog_as_heading
else use_gyro_heading
```

Ngưỡng ~1-2 m/s là chuẩn trong car navigation.

### 12.6. Zero-Velocity Update (ZUPT)?

Khi phát hiện thiết bị đứng yên (accelerometer variance thấp, gyro thấp) trong ≥1s,
reset velocity IMU về 0. Giúp giảm drift dài hạn. Kỹ thuật chuẩn của INS.

### 12.7. Map matching là gì?

Sau khi có ước lượng vị trí (từ GPS + IMU + VO), nếu điểm nằm gần một đoạn của route đã
plan (< 20m), **snap** vị trí về gần nhất trên polyline route. Giúp đường đi hiển thị
mượt, không zigzag do noise GPS.

---

## 13. Analytics Benchmark

### 13.1. Analytics module làm gì?

Cho phép user:
- Ghi lại các session chạy KLT vs Farneback vs RAFT trên cùng đoạn video.
- Xem đồ thị FPS, confidence, active vectors theo thời gian.
- Xuất báo cáo so sánh.

Xem `screen/fragment/AnalyticsListFragment.kt`, `AnalyticsViewFragment.kt`,
`AnalyticsChartView.kt`, `model/AnalyticsModels.kt`.

### 13.2. `AnalyticsChartView` vẽ chart bằng gì?

Custom View kế thừa `View`, override `onDraw(Canvas)`. Bản chất:
- Nhận list các `AnalyticsSample`, mỗi sample có `time, metric_value`.
- Tính min/max để chuẩn hoá.
- Vẽ trục X, Y bằng `canvas.drawLine`.
- Vẽ đường: `canvas.drawPath(path)` với Path nối các điểm.
- Vẽ fill dưới đường bằng gradient.

Vì sao không dùng MPAndroidChart? Vì đồ án cần control cụ thể (2-3 line cùng đồ thị,
tooltip touch, decimation cho long series) — custom View gọn hơn.

### 13.3. Vì sao dùng Room database?

Xem migration commit `6072af1` — data storage migrate từ file JSON sang Room DB. Room:
- ORM chuẩn Google, type-safe (kiểm compile-time).
- Reactive (Flow, LiveData).
- Migration schema tự động.
- Query bằng annotation `@Query("SELECT ...")`.

Data model gồm: `AnalyticsSession`, `AnalyticsSample`. Session 1-N Sample.

---

## 14. Server FastAPI/ONNX

### 14.1. FastAPI vì sao chọn?

- **Async native**: dùng `asyncio` → xử lý concurrent requests hiệu quả.
- **Type hints native**: tự động validate/document theo Pydantic.
- **OpenAPI auto**: `/docs` swagger UI miễn phí.
- **Nhanh**: bench 2-3× Flask.

### 14.2. Endpoints chính?

- `POST /process-video` (sync) — upload nhỏ, xử lý xong trả về link download.
- `POST /process-video/jobs` (async) — tạo job, trả `jobId`, xử lý background.
- `POST /process-video/uploads/{id}/chunks` — chunk upload.
- `POST /process-video/jobs/{id}/cancel` — huỷ job đang chạy.
- `GET /process-video/jobs/{id}/result` — tải output video.
- `GET /health` — check server còn sống, dùng cho Android probe.

### 14.3. VideoJob state machine?

```
queued → processing → completed
                    ↓
                cancelling → cancelled
                    ↓
                 failed (exception)
```

- `queued`: job vừa tạo, chưa có slot.
- `processing`: đang chạy inference.
- `cancelling`: user request cancel, worker đang gracefully dừng.
- `completed`: xong, có `result_path`.
- `failed`: có exception, có `error_message`.
- `cancelled`: đã dừng thành công.

### 14.4. ROI selection trên client ↔ server ra sao?

Client cho user vẽ 4-point ROI trên frame preview. Gửi lên server dưới dạng
**normalized points**:
```json
{"points": [[0.1, 0.2], [0.9, 0.2], [0.9, 0.8], [0.1, 0.8]], "view_aspect_ratio": 1.777}
```

Server nhận, biết `view_aspect_ratio` để scale đúng lên frame gốc (frame video có thể
rộng hơn/khác aspect với preview). Sau đó mask hoặc crop.

Đồ án có tuỳ chọn dùng **Cutie** (Vision-Language Object Segmentation model) để tự
segment vùng ROI thay vì user vẽ tay.

### 14.5. Prepare blob cho ONNX ra sao?

```python
# 1. BGR → RGB
img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
# 2. Resize about 480×360
img_resized = cv2.resize(img_rgb, (480, 360), interpolation=cv2.INTER_LINEAR)
# 3. HWC → CHW
img_chw = np.transpose(img_resized, (2, 0, 1))
# 4. Batch dim: [3, 360, 480] → [1, 3, 360, 480]
img_batch = np.expand_dims(img_chw, axis=0).astype(np.float32)
```

Lý do:
- OpenCV mặc định BGR, PyTorch/ONNX train ở RGB → phải convert.
- ONNX PyTorch dùng NCHW (batch, channels, height, width).
- Cần thêm batch dim vì model expect input 4D.

### 14.6. `infer()` có 2 input hay 1 input?

Một số RAFT ONNX **concatenate** 2 frame thành tensor 6 kênh `[1, 6, H, W]`. Số khác
giữ 2 input riêng `frame1, frame2`. Server tự detect từ `session.get_inputs()`:
- Nếu 2 input names → feed dict `{name1: blob1, name2: blob2}`.
- Nếu 1 input → concat: `np.concatenate([blob1, blob2], axis=1)`.

---

## 15. Câu hỏi tổng hợp

### 15.1. Tại sao đề tài của bạn có ý nghĩa? (Vấn đề trọng tâm)

Bài toán cốt lõi là **duy trì tốc độ và vị trí chính xác khi GNSS mất tín hiệu**
(tunnel, indoor, urban canyon). GPS-only rất phổ biến nhưng có điểm mù. Đề tài kết hợp:
- Optical flow (visual odometry).
- IMU (inertial).
- GNSS chính xác cao khi có.

Là **cascaded VIO** (Visual-Inertial Odometry) đơn giản hoá, không đầy đủ như VINS-Mono
nhưng đủ cho mobile deployment.

### 15.2. Ưu điểm và giới hạn của cách tiếp cận?

**Ưu**:
- Đa nguồn dữ liệu, robust hơn single-source.
- Tận dụng cả CPU và GPU trên phone.
- Server fallback cho case cần độ chính xác cao (RAFT).

**Nhược**:
- Optical flow phụ thuộc texture — không hoạt động trên bề mặt phẳng, tối.
- IMU drift nhanh — chỉ tin cậy vài giây.
- Chưa có tight-coupled fusion (Kalman filter đầy đủ) — mỗi module tương đối độc lập.
- RAFT server yêu cầu mạng — không phải giải pháp offline hoàn toàn.

### 15.3. Vì sao chọn OpenCV thay vì tự implement optical flow?

- OpenCV được **kiểm định bởi cộng đồng ~10 triệu dev**, tránh bug ẩn.
- **NEON/SIMD optimize** cho ARM — nhanh gấp 10× so với Kotlin thuần.
- **Time to market** — focus vào phần đóng góp (integration, sensor fusion, UI), không
  reinventing wheel.

### 15.4. Vì sao dùng Orekit thay vì tự implement SGP4?

Orekit là library **chuẩn ngành hàng không vũ trụ** (dùng bởi ESA, CNES, JAXA). Chuyển
đổi datum, IAU rotation, GLONASS PZ-90.11 → WGS84 rất phức tạp. Tự implement dễ sai
1-2 km. Orekit đảm bảo chính xác < 1 m.

### 15.5. Kotlin coroutines dùng cho gì?

- `Dispatchers.Default` — CPU-bound (optical flow processing).
- `Dispatchers.IO` — mạng, disk (upload chunk, save video).
- `Dispatchers.Main` — cập nhật UI.
- `withContext(...)` — switch dispatcher trong hàm suspend.
- `Flow` — reactive stream (location, sensor updates).

Ưu điểm so với RxJava: syntax gọn, tích hợp Kotlin gốc, cancellation tự động khi
scope huỷ (ViewModelScope).

### 15.6. WorkManager dùng cho gì?

`function/video/worker/VideoProcessingWorker.kt` — xử lý video ở background, có thể:
- Chạy tiếp khi app bị kill.
- Retry khi mất mạng.
- Constraint (chỉ chạy khi có Wi-Fi + đang sạc).
- Post notification progress.

### 15.7. Nếu hội đồng hỏi "code này có gì mới so với có sẵn?" thì trả lời ra sao?

- **Phần mới**:
  - **Tích hợp** 4 tầng ưu tiên vị trí vệ tinh (PVT → IGS → SGP4 → Approx) trong một
    resolver duy nhất — cách này chưa thấy trong app dân sự.
  - **Visualization** globe 3D interactive với Sun/Moon Meeus + skybox — dùng OpenGL
    ES 3.2 raw thay vì library.
  - **Cascaded VIO cho mobile**: kết hợp KLT/Farneback + IMU + map matching tuỳ biến,
    không phải import ARCore/SLAM library.
  - **Server RAFT tự deploy**: chọn provider CUDA/DirectML/CPU automatic, chunked upload
    tự implement — không dùng managed service.
- **Phần chuẩn hoá**: math (Kepler, Meeus, WGS84), optical flow (LK, Farneback) là kinh
  điển — không sáng chế lại.

### 15.8. Kết luận cho đề tài

Đề tài chứng minh rằng **smartphone hiện đại đủ sức thực hiện GNSS analysis + visual
odometry ở mức nghiên cứu**, kết hợp cùng backend AI (RAFT) khi cần độ chính xác. Có
tiềm năng ứng dụng trong navigation cho vùng thiếu GPS, drone chỉ dùng camera + IMU,
hoặc giáo dục về orbital mechanics.

---

*Hết. Nếu có câu hỏi ngoài phạm vi trên, hãy dựa vào mã nguồn thực tế và giải thích
theo tinh thần: "bản chất là gì → công thức toán → code thực hiện thế nào → tại sao
chọn tham số/cách tiếp cận đó".*

