# Tài liệu Kỹ thuật - GNSSAndOpticalFlowApp

## Mục lục
1. [Kiến trúc GNSS Tracking và Ưu tiên Nguồn Dữ liệu](#1-kiến-trúc-gnss-tracking-và-ưu-tiên-nguồn-dữ-liệu)
2. [Cách tính vị trí GNSS Vệ tinh (Cơ sở Toán học & WGS84)](#2-cách-tính-vị-trí-gnss-vệ-tinh-cơ-sở-toán-học--wgs84)
3. [Cách tính vị trí Mặt Trăng và Mặt Trời (Thiên văn học)](#3-cách-tính-vị-trí-mặt-trăng-và-mặt-trời-thiên-văn-học)
4. [Tọa độ OpenGL và Hệ thống AR Rendering](#4-tọa-độ-opengl-và-hệ-thống-ar-rendering)
5. [Thuật toán Optical Flow (KLT & Farneback)](#5-thuật-toán-optical-flow-klt--farneback)
6. [Luồng Phân tích (Analytics Flow) Optical Flow](#6-luồng-phân-tích-analytics-flow-optical-flow)
7. [Sensor Fusion - Kết hợp IMU](#7-sensor-fusion---kết-hợp-imu)
8. [Tổng hợp Công thức Quan trọng](#8-tổng-hợp-công-thức-quan-trọng)

---

## 1. Kiến trúc GNSS Tracking và Ưu tiên Nguồn Dữ liệu

Ứng dụng không chỉ tính toán vị trí vệ tinh dựa trên góc nhìn xấp xỉ (azimuth/elevation) mà sử dụng một hệ thống phân cấp ưu tiên các nguồn dữ liệu chính xác từ API và dữ liệu quỹ đạo bên ngoài.

Lớp `GnssSatelliteTracker` chịu trách nhiệm thu thập và phân giải (`resolvePosition`) tọa độ của vệ tinh theo thứ tự ưu tiên sau:

### 1.1 Real GNSS PVT (Position, Velocity, Time)
Đây là nguồn dữ liệu chính xác nhất, được lấy trực tiếp từ chipset GNSS trên thiết bị Android thông qua `GnssMeasurement`. Do API này bị ẩn (hidden API) ở một số phiên bản, ứng dụng sử dụng Reflection (`GnssSatellitePVTResolver`) để trích xuất `SatellitePvt`.
- **Dữ liệu cung cấp:** Tọa độ ECEF (Trái đất làm trung tâm), Vận tốc ECEF.
- **Cache:** Lưu trữ tạm thời (timeout 10 giây) để đồng bộ với callback `GnssStatus`.

### 1.2 IGS Broadcast Ephemeris
Nếu không có PVT thật từ thiết bị, ứng dụng fetch dữ liệu lịch thiên văn (Broadcast Ephemeris) từ dịch vụ IGS (International GNSS Service).
- **Propagator:** Giải phương trình quỹ đạo để tìm tọa độ vệ tinh tại thời điểm hiện tại.
- **Cache:** Tối đa 12 giờ cho một bản ghi.

### 1.3 CelesTrak (SGP4 / Mean Elements)
Nguồn thứ 3 là dữ liệu OMM (Orbit Mean Elements Message) từ CelesTrak, cung cấp thông tin quỹ đạo cho các nhóm vệ tinh (GPS, Galileo, BeiDou).
- Áp dụng mô hình SGP4 (`Sgp4OrbitPropagator`) để xử lý tác động nhiễu loạn từ Trái đất.
- Hoặc fallback về giải phương trình Kepler tiêu chuẩn nếu SGP4 không khả dụng.

### 1.4 Approximate (Xấp xỉ)
Mức dự phòng cuối cùng. Dùng tọa độ của người dùng (`observerLat`, `observerLon`), góc nhìn (`azimuth`, `elevation`) do `GnssStatus` cung cấp, kết hợp giả định quỹ đạo hình cầu để tính ngược ra tọa độ vệ tinh.

---

## 2. Cách tính vị trí GNSS Vệ tinh (Cơ sở Toán học & WGS84)

Phần tính toán cốt lõi nằm trong `SatelliteCalculator.kt`.

### 2.1 Chuyển đổi ECEF sang LLA (WGS84)
Dữ liệu PVT thường ở dạng ECEF (Earth-Centered, Earth-Fixed). Ứng dụng sử dụng thuật toán Bowring với tham số của Ellipsoid WGS84 để chuyển sang hệ LLA (Latitude, Longitude, Altitude).

**Tham số WGS84:**
```kotlin
private const val WGS84_A = 6378137.0
private const val WGS84_F = 1.0 / 298.257223563
private const val WGS84_B = WGS84_A * (1.0 - WGS84_F)
private const val WGS84_E2 = 1.0 - (WGS84_B * WGS84_B) / (WGS84_A * WGS84_A)
private const val WGS84_EP2 = (WGS84_A * WGS84_A - WGS84_B * WGS84_B) / (WGS84_B * WGS84_B)
```

**Thuật toán Bowring:**
```kotlin
val p = sqrt((ecefX * ecefX) + (ecefY * ecefY))
val theta = atan2(ecefZ * WGS84_A, p * WGS84_B)

val latitude = atan2(
    ecefZ + (WGS84_EP2 * WGS84_B * sin³(theta)),
    p - (WGS84_E2 * WGS84_A * cos³(theta))
)
val longitude = atan2(ecefY, ecefX)
val radiusOfCurvature = WGS84_A / sqrt(1.0 - (WGS84_E2 * sin²(latitude)))
val altitude = (p / cos(latitude)) - radiusOfCurvature
```

### 2.2 Vận tốc Quán tính (Inertial Speed) từ ECEF
Vận tốc trả về từ PVT là tương đối so với Trái Đất đang tự quay. Để có tốc độ thực tế của vệ tinh trong không gian quán tính, ta bù trừ thêm vận tốc quay của Trái Đất (`ω = 7.292115e-5 rad/s`).
```kotlin
val inertialVelocityX = velocityX - (ω * ecefY)
val inertialVelocityY = velocityY + (ω * ecefX)
val inertialVelocityZ = velocityZ
val speed = sqrt(vX² + vY² + vZ²)
```

### 2.3 Chuyển đổi TEME sang ECEF và Giải phương trình Kepler
Dữ liệu CelesTrak nằm trong hệ trục TEME (True Equator, Mean Equinox). Ứng dụng dùng Góc Sidereal Greenwich (GMST) để thực hiện quay ma trận.

**Chuyển đổi hệ trục:**
```kotlin
val earthRotationAngle = calculateGreenwichSiderealAngleRadians(observationUtcMillis)
val ecefX = (cos(θ) * temeX) + (sin(θ) * temeY)
val ecefY = (-sin(θ) * temeX) + (cos(θ) * temeY)
val ecefZ = temeZ
```

**Giải phương trình Kepler (Newton-Raphson):**
```kotlin
var E = M // Bắt đầu với Eccentric Anomaly = Mean Anomaly
repeat(10) {
    val num = E - (e * sin(E)) - M
    val den = 1.0 - (e * cos(E))
    E -= num / den
}
```

### 2.4 Approximate Fallback
Sử dụng hình học không gian (giao tuyến của đường nhìn từ observer và mặt cầu quỹ đạo):
1. Tính khoảng cách `range` bằng định lý Cosin:
   `r² + 2*R_earth*sin(El)*r + R_earth² - R_sat² = 0`
2. Đổi sang trục Topocentric ENU (East, North, Up):
   `E = range * cos(El) * sin(Az)`
3. Đổi trục ENU về ECEF dựa vào vĩ độ/kinh độ của observer.

---

## 3. Cách tính vị trí Mặt Trăng và Mặt Trời (Thiên văn học)

Trong `EarthRenderer.kt`, Mặt Trăng và Mặt Trời được định vị dựa trên thuật toán Astronomical Algorithms (Meeus).

### 3.1 Tính thời gian chuẩn (Julian Date)
```kotlin
val jd = (utcTimeMillis / 86400000.0) + 2440587.5
val dJD = jd - 2451545.0 // Thời gian tính từ kỉ nguyên J2000.0
```

### 3.2 Tọa độ Mặt Trăng (Moon)
Tính toán các yếu tố nhiễu loạn quỹ đạo để tìm ra Kinh độ (`λ`) và Vĩ độ Hoàng đạo (`β`):
- Kinh độ trung bình (`L`), Cận điểm (`M`), Nút lên (`Ω`).
```kotlin
val lambdaMoon = toRadians(L + 6.289 * sin(M) + 0.214 * sin(2M) + 0.658 * sin(2F))
val betaMoon = toRadians(5.128 * sin(F) + 0.281 * sin(M + F) + 0.278 * sin(M - F))
```
Chuyển từ Hoàng đạo (Ecliptic) sang Xích đạo (Equatorial) với độ nghiêng `ε ≈ 23.4°`:
```kotlin
sin(δ) = sin(β) * cos(ε) + cos(β) * sin(ε) * sin(λ)
tan(α) = (sin(λ) * cos(ε) - tan(β) * sin(ε)) / cos(λ)
```

### 3.3 Tọa độ Mặt Trời (Sun)
Mặt Trời nằm ngay trên mặt phẳng Hoàng đạo nên `β = 0`.
```kotlin
val C = (1.915 * sin(M) + 0.020 * sin(2M)) * (1 - 0.003 * M) // Equation of Center
val lambdaSun = toRadians(L + C)
```

---

## 4. Tọa độ OpenGL và Hệ thống AR Rendering

### 4.1 Quy ước tọa độ OpenGL 3D
Hệ thống đồ họa 3D quy định:
- **Gốc (0,0,0)**: Tâm Trái Đất.
- Trục **Y**: Hướng Bắc.
- Trục **Z**: Kinh tuyến gốc (Kinh độ 0°).
- Trục **X**: Kinh độ 90° Đông.

Công thức mapping tọa độ Cầu (Lat/Lon) lên Cartesian 3D:
```kotlin
tx = r * cos(lat) * sin(lon)
ty = r * sin(lat)
tz = r * cos(lat) * cos(lon)
```

### 4.2 Thực tế ảo tăng cường (AR GNSS Rendering)
`GNSSARFragment` và `GNSSARRenderer` phủ ảnh vệ tinh lên không gian thực dựa trên camera:
1. **Camera Feed**: Background được vẽ bằng `SurfaceTexture` từ luồng CameraX.
2. **Device Orientation**: `Sensor.TYPE_ROTATION_VECTOR` trả về Quaternion mô tả góc xoay của điện thoại.
3. **View Matrix**: Lấy ma trận quay `RotationMatrix` từ sensor, áp dụng vào `Matrix.setLookAtM` hoặc cấu hình thành `ViewMatrix` ngược hướng nhìn của thiết bị. Khi thiết bị quay sang hướng Đông (Azimuth 90°), bầu trời ảo OpenGL sẽ xoay ngược lại giữ nguyên vệ tinh ở cố định trong thế giới vật lý.

---

## 5. Thuật toán Optical Flow (KLT & Farneback)

### 5.1 KLT (Sparse Optical Flow - `KLT.kt`)
Thuật toán KLT bám sát các "đặc trưng" (góc/cạnh) rải rác trên màn hình thay vì toàn bộ pixel.
- **Tìm Điểm Đặc Trưng (Shi-Tomasi)**:
  ```kotlin
  Imgproc.goodFeaturesToTrack(prevGray, corners, 50, 0.01, 3.0)
  ```
  Tìm điểm có eigenvalues lớn (tối đa 50 điểm).
- **Theo Dõi (Lucas-Kanade Pyramid)**: Giải phương trình Gradient ảnh.
  Giả thiết độ sáng không đổi: `I(x, y, t) = I(x+dx, y+dy, t+dt)`
  Khai triển Taylor ra ma trận `A * v = -b` và tính vận tốc `v`. Ứng dụng dùng Pyramid (nhiều độ phân giải) để tìm chuyển động lớn (`maxLevel=3`).
- **Làm Mượt (Exponential Smoothing)**:
  Lọc lỗi (`err < 50`), lấy Median các vector dịch chuyển. Làm mượt với α = 0.85:
  ```kotlin
  currMv = Point(prev.x * 0.85 + new.x * 0.15, prev.y * 0.85 + new.y * 0.15)
  ```

### 5.2 Farneback (Dense Optical Flow - `Farneback.kt`)
Tính vector dịch chuyển cho **từng pixel** trên khung hình. Phù hợp phân tích toàn thể ảnh nhưng rất nặng phần cứng.
- **Đa Thức Bậc 2 (Polynomial Expansion)**: Xấp xỉ hóa vùng lân cận quanh pixel:
  `f(x) ≈ x^T * A * x + b^T * x + c`
- **Tính Displacement**: `d = (G^T * G)^(-1) * G^T * h`
- **Cấu hình**: `levels=3`, `winSize=15`, `iterations=3`, `polyN=5`. Render bản đồ vector mũi tên phân bố cách nhau `step=32` pixels.

---

## 6. Luồng Phân tích (Analytics Flow) Optical Flow

Ứng dụng chạy benchmark song song KLT và Farneback qua tính năng **Start Analysis**.

### 6.1 Cơ chế
- Khóa độ nhạy của 2 thuật toán về chung mức (`ANALYSIS_SENSITIVITY = 50`).
- Màn hình bị chia đôi (Trái: KLT, Phải: Farneback) đi kèm HUD lớp phủ đo đạc Real-time.
- Cứ sau mỗi `ANALYSIS_SAMPLE_INTERVAL_MS`, thông số được lưu lại vào một `AnalyticsSample`.
- Kết thúc đo đạc, tổng hợp ra `AnalyticsSession` (.json file) bằng `AnalyticsStorageUtil`.

### 6.2 Ý nghĩa thông số & Màn hình Chi tiết (Analytics Detail)

Trong màn hình **Analytics Detail** (Chi tiết Phân tích), hệ thống chỉ tập trung vẽ biểu đồ cho các thông số mang tính chất **Đo lường thuần túy (Pure Measurements)** để đảm bảo tính khách quan và minh bạch về hiệu năng phần cứng/thuật toán. Các thông số này bao gồm:

1. **FPS (Frames Per Second)**: 
   - Đo lường trực tiếp tốc độ khung hình thực tế mà thiết bị có thể xử lý được (nghịch đảo của thời gian trễ).
   - *Trên Detail Fragment*: Cho thấy độ mượt mà của thuật toán trên từng frame. Mức chênh lệch (Delta) thể hiện rõ KLT hay Farneback tối ưu hơn.
2. **Process Time (Thời gian xử lý - ms)**: 
   - Đo trực tiếp thời gian CPU/GPU phải bỏ ra để chạy xong hàm thuật toán cho 1 frame bằng clock hệ thống.
3. **Tracks / Active Vectors (Số lượng điểm đang track)**: 
   - Phép đếm thuần túy số lượng điểm ảnh (features) hoặc ô lưới (grid) đang được thuật toán bám sát thành công. 

#### Sự khác biệt với thông số Tính toán (Calculated/Inferred Metrics)
Các biểu đồ về các thông số sau đã được gỡ bỏ khỏi màn hình Detail do bản chất chúng là thông số nội suy hoặc thống kê, tuy nhiên **chỉ số trung bình tổng thể vẫn được giữ lại** trên thẻ thông tin (Summary Card) để làm cơ sở đánh giá:

- **Average Magnitude (Độ lớn Vector trung bình) & Flow X**: Thực chất là phép thống kê (trung bình cộng) của các vector dịch chuyển. Nó phản ánh hành vi camera (lia ngang/dọc) thay vì đo hiệu năng hệ thống.
- **Confidence (Độ tin cậy %)**: Đây là một **điểm số Heuristic (Kinh nghiệm)** không phải là một đại lượng vật lý. Cách tính Confidence thường phụ thuộc vào:
  - Tỉ lệ số điểm bị mất track (Lost tracks ratio).
  - Lỗi thuật toán (Trạng thái `status` và `error` trả về từ `calcOpticalFlowPyrLK`).
  - Kiểm tra đối chiếu (Forward-Backward Error) hoặc độ phân tán của ma trận vector (Variance).
  - Vì mang tính chất "chấm điểm" suy đoán, Confidence giúp người dùng có cái nhìn tổng quan về chất lượng tracking nhưng không phù hợp làm thước đo hiệu năng trực tiếp trên biểu đồ.

#### Chi tiết 12 Thông số tại Màn hình `Analytics Sample Detail`
Khi nhấn vào một điểm trên biểu đồ, màn hình chi tiết sẽ hiển thị cụ thể các thông số cho **duy nhất 1 khung hình (Sample) đó**. Ý nghĩa chi tiết như sau:

1. **Frame (`#...`)**: Số thứ tự của khung hình trong phiên chạy. Giúp định vị khung hình nào bị giật lag.
2. **Time (`...s`)**: Thời điểm tương đối (tính bằng giây) kể từ lúc bắt đầu phiên đo đạc.
3. **Giá trị KLT (Động)**: Hiển thị giá trị cụ thể của biểu đồ đang xem (FPS, Process Time, hoặc Tracks) áp dụng cho KLT.
4. **Giá trị FB (Động)**: Tương tự trên nhưng áp dụng cho Farneback (FB).
5. **KLT Proc**: Thời gian xử lý ròng (tính bằng ms) của thuật toán KLT cho riêng khung hình này.
6. **FB Proc**: Thời gian xử lý ròng (tính bằng ms) của thuật toán Farneback cho riêng khung hình này.
7. **KLT Trk (`Active/Total`)**: Viết tắt của "KLT Tracks" (Đường đi). Vì KLT hoạt động theo cơ chế nhắm vào từng điểm đặc trưng (Feature Point) để bám theo vết (Track), nên ô này hiển thị tỷ lệ số vector **thực sự đang chuyển động** / Tổng số điểm mà KLT đang theo dõi.
8. **FB Vec (`Active/Total`)**: Viết tắt của "Farneback Vectors". Farneback không bám theo điểm mà tính toán ra một "trường vector" (Vector Field) dày đặc phủ kín màn hình. Ô này hiển thị tỷ lệ số ô lưới **có dịch chuyển** / Tổng số lượng ô lưới.
9. **KLT dxdy (`X, Y`)**: Độ dịch chuyển trung bình (pixel) theo trục ngang X và dọc Y do KLT tính toán.
10. **FB dxdy (`X, Y`)**: Tương tự KLT nhưng tính từ mảng Dày đặc của Farneback.
11. **KLT Conf (%)**: Độ tin cậy (Confidence) của thuật toán KLT tại khung hình này.
12. **FB Conf (%)**: Độ tin cậy của thuật toán Farneback tại khung hình này.

---

## 7. Sensor Fusion - Kết hợp IMU

App tích hợp khả năng đọc cảm biến đo lường quán tính thông qua `IMUEstimator.kt`.

### 7.1 Linear Acceleration
Loại bỏ trọng lực (`Gravity`) khỏi Gia tốc kế (`Accelerometer`):
```kotlin
a_linear = a_measured - gravity
```

### 7.2 Tính Vận Tốc và Cắt Nhiễu (Low-pass Filter)
Vận tốc là tích phân của gia tốc. Để loại bỏ trôi dạt (drift), ứng dụng áp dụng Low-pass filter kết hợp Gyroscope:
```kotlin
v_t[0] = 0.8 * (v_{t-1}[0] + a_linear[0] * Δt) + 0.2 * v_gyro[0]
```

### 7.3 Phát Hiện Di Chuyển (Moving Mode Detection)
Phát hiện người dùng đang đi bộ hay đứng yên bằng cách kiểm tra Magnitude của `a_linear`. Nếu cường độ gia tốc vượt qua ngưỡng định sẵn (`0.25`), hệ thống bật chế độ "Moving Mode" để tinh chỉnh ngưỡng bắt dính tĩnh của Optical Flow (Tránh màn hình giật khi người dùng đang chạy).

---

## 8. Tổng hợp Công thức Quan trọng

**Chuyển Đổi Ellipsoid WGS84 ECEF -> LLA:**
```
p = √(X² + Y²)
θ = atan2(Z * a, p * b)
Lat = atan2(Z + e'^2 * b * sin³(θ), p - e^2 * a * cos³(θ))
Lon = atan2(Y, X)
Alt = (p / cos(Lat)) - N
```

**Quay Tọa Độ Trái Đất (TEME -> ECEF):**
```
X_ecef = X_teme * cos(GMST) + Y_teme * sin(GMST)
Y_ecef = -X_teme * sin(GMST) + Y_teme * cos(GMST)
```

**Vector KLT (Lucas-Kanade):**
```
v = (A^T * A)^(-1) * A^T * b
```

**Exponential Smoothing Flow:**
```
mv_current = 0.85 * mv_previous + 0.15 * mv_new
```
