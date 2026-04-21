# Tài liệu Kỹ thuật - GNSSAndOpticalFlowApp

## Mục lục
1. [Cách tính vị trí vệ tinh, Moon, Sun trong 3D OpenGL](#1-cách-tính-vị-trị-vệ-tinh-moon-sun-trong-3d-opengl)
2. [Cách hoạt động và công thức của KLT (Sparse Optical Flow)](#2-cách-hoạt-động-và-công-thức-của-klt-sparse-optical-flow)
3. [Cách hoạt động và công thức của Farneback (Dense Optical Flow)](#3-cách-hoạt-động-và-công-thức-của-farneback-dense-optical-flow)
4. [Sensor Fusion - Kết hợp IMU và Optical Flow](#4-sensor-fusion---kết-hợp-imu-và-optical-flow)

---

## 1. Cách tính vị trí vệ tinh, Moon, Sun trong 3D OpenGL

### 1.1 Tổng quan hệ tọa độ trong OpenGL

Dự án sử dụng hệ tọa độ 3D OpenGL với các thành phần:
- **Earth**: Tại gốc tọa độ (0, 0, 0) với bán kính 0.1 (đơn vị OpenGL)
- **Camera**: Di chuyển quanh Earth theo tọa độ hình cầu (theta, phi, radius)
- **Vệ tinh**: Được vẽ tại vị trí tính toán từ azimuth, elevation
- **Moon & Sun**: Được tính toán từ các orbital elements thiên văn

### 1.2 Tính vị trí Camera (Tọa độ hình cầu)

Camera được định vị bằng tọa độ hình cầu (theta, phi, radius):

```kotlin
val radius = scaleFactor
val camX = (radius * cos(toRadians(phi.toDouble())) * sin(toRadians(theta.toDouble()))).toFloat()
val camY = (radius * sin(toRadians(phi.toDouble()))).toFloat()
val camZ = (radius * cos(toRadians(phi.toDouble())) * cos(toRadians(theta.toDouble()))).toFloat()
```

**Công thức chuyển đổi từ tọa độ hình cầu sang Cartesian:**
- `x = r * cos(φ) * sin(θ)`
- `y = r * sin(φ)`
- `z = r * cos(φ) * cos(θ)`

Trong đó:
- `r`: Khoảng cách từ Earth (scaleFactor)
- `θ` (theta): Góc azimuth (kinh độ) theo phương ngang
- `φ` (phi): Góc elevation (vĩ độ) theo phương dọc

### 1.3 Tính vị trí Vệ tinh (SatelliteCalculator.kt)

#### Bước 1: Xác định bán kính quỹ đạo theo loại vệ tinh

```kotlin
val radius: Double = when (constellationType) {
    GnssStatus.CONSTELLATION_GPS -> EARTH_RADIUS_M + 20200000.0 // MEO ~20,200 km
    GnssStatus.CONSTELLATION_GLONASS -> EARTH_RADIUS_M + 19100000.0 // MEO ~19,100 km
    GnssStatus.CONSTELLATION_GALILEO -> EARTH_RADIUS_M + 23222000.0 // MEO ~23,222 km
    GnssStatus.CONSTELLATION_BEIDOU -> {
        // GEO/IGSO ~35,786 km hoặc MEO ~21,528 km
    }
    // ...
}
```

**Công thức vận tốc quỹ đạo:**
```
v = √(μ / r)
```
Trong đó:
- `μ = 3.986004418e14` (Hằng số hấp dẫn Trái Đất, m³/s²)
- `r`: Bán kính quỹ đạo từ tâm Trái Đất (m)

#### Bước 2: Tính khoảng cách từ observer đến vệ tinh

Sử dụng định lý cosine trên hình cầu:

```kotlin
val sinEl = sin(elRad)
val a = 1.0
val b = 2.0 * EARTH_RADIUS_M * sinEl
val c = EARTH_RADIUS_M * EARTH_RADIUS_M - orbitRadius * orbitRadius

val discriminant = b * b - 4 * a * c
val range = if (discriminant >= 0) {
    (-b + sqrt(discriminant)) / (2.0 * a)
} else {
    orbitRadius - EARTH_RADIUS_M
}
```

**Phương trình bậc 2:**
```
r² + 2 * R_earth * sin(El) * r + R_earth² - R_sat² = 0
```
Trong đó:
- `r`: Khoảng cách từ observer đến vệ tinh
- `R_earth`: Bán kính Trái Đất (6,378,137 m)
- `R_sat`: Bán kính quỹ đạo vệ tinh
- `El`: Góc elevation

#### Bước 3: Chuyển sang tọa độ ENU (East-North-Up)

```kotlin
val e = range * cos(elRad) * sin(azRad)
val n = range * cos(elRad) * cos(azRad)
val u = range * sinEl
```

**Công thức ENU:**
- `East = range * cos(El) * sin(Az)`
- `North = range * cos(El) * cos(Az)`
- `Up = range * sin(El)`

#### Bước 4: Tính tọa độ ECEF của observer

```kotlin
val userX = EARTH_RADIUS_M * cos(latRad) * cos(lonRad)
val userY = EARTH_RADIUS_M * cos(latRad) * sin(lonRad)
val userZ = EARTH_RADIUS_M * sin(latRad)
```

#### Bước 5: Chuyển ENU sang ECEF cho vệ tinh

```kotlin
val ecefX = userX - sinLon * e - sinLat * cosLon * n + cosLat * cosLon * u
val ecefY = userY + cosLon * e - sinLat * sinLon * n + cosLat * sinLon * u
val ecefZ = userZ + cosLat * n + sinLat * u
```

**Ma trận chuyển đổi ENU → ECEF:**
```
[X_ecef]   [-sin(λ)   -sin(φ)cos(λ)   cos(φ)cos(λ)] [E]
[Y_ecef] = [ cos(λ)   -sin(φ)sin(λ)   cos(φ)sin(λ)] [N]
[Z_ecef]   [   0         cos(φ)        sin(φ)   ] [U]
```

Trong đó `λ` là kinh độ, `φ` là vĩ độ.

#### Bước 6: Chuyển ECEF sang LLA (Latitude, Longitude, Altitude)

```kotlin
val p = sqrt(ecefX * ecefX + ecefY * ecefY)
val satLat = toDegrees(atan2(ecefZ, p))
val satLon = toDegrees(atan2(ecefY, ecefX))
val satAlt = sqrt(ecefX * ecefX + ecefY * ecefY + ecefZ * ecefZ) - EARTH_RADIUS_M
```

#### Bước 7: Chuyển sang tọa độ OpenGL cho rendering

Trong EarthRenderer, vệ tinh được vẽ gần Earth để dễ quan sát:

```kotlin
val normalizedAlt = (sat.altitude / 35786000.0).coerceIn(0.0, 1.0)
val rSat = 0.15f + (0.02f * normalizedAlt).toFloat()
val latRad = toRadians(sat.latitude)
val lonRad = toRadians(sat.longitude)

val tx = (rSat * cos(latRad) * sin(lonRad)).toFloat()
val ty = (rSat * sin(latRad)).toFloat()
val tz = (rSat * cos(latRad) * cos(lonRad)).toFloat()
```

### 1.4 Tính vị trí Moon (EarthRenderer.kt)

#### Bước 1: Tính Julian Date

```kotlin
val utcCalendarMoon = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
val utcTimeMillisMoon = utcCalendarMoon.timeInMillis
val jdMoon = utcTimeMillisMoon / 86400000.0 + 2440587.5
val dJDMoon = jdMoon - 2451545.0
```

**Công thức Julian Date:**
```
JD = (milliseconds / 86400000.0) + 2440587.5
dJD = JD - 2451545.0 (số ngày kể từ J2000.0)
```

#### Bước 2: Tính Orbital Elements của Moon

```kotlin
var lMoon = 218.32 + 13.176396 * dJDMoon  // Mean longitude
var mMoon = 134.96 + 13.064993 * dJDMoon  // Mean anomaly
var fMoon = 93.27 + 13.229350 * dJDMoon    // Argument of latitude
var omegaMoon = 125.08 - 0.0529539 * dJDMoon  // Ascending node
var wMoon = 318.06 + 0.1643573 * dJDMoon     // Perigee

// Chuẩn hóa về [0, 360)
lMoon %= 360.0; if (lMoon < 0) lMoon += 360.0
// ... tương tự cho các biến khác
```

**Các Orbital Elements:**
- `L`: Mean Longitude (Kinh độ trung bình)
- `M`: Mean Anomaly (Cận điểm trung bình)
- `F`: Argument of Latitude (Góc vĩ độ)
- `Ω`: Ascending Node (Nút lên)
- `ω`: Perigee (Cận điểm)

#### Bước 3: Tính Ecliptic Coordinates (Kinh độ, Vĩ độ hoàng đạo)

```kotlin
val lambdaMoon = toRadians(lMoon + 6.289 * sin(toRadians(mMoon)) + 
                                 0.214 * sin(toRadians(2 * mMoon)) +
                                 0.658 * sin(toRadians(2 * fMoon)))
val betaMoon = toRadians(5.128 * sin(toRadians(fMoon)) +
                               0.281 * sin(toRadians(mMoon + fMoon)) +
                               0.278 * sin(toRadians(mMoon - fMoon)))
```

**Công thức Ecliptic:**
- `λ = L + 6.289*sin(M) + 0.214*sin(2M) + 0.658*sin(2F)`
- `β = 5.128*sin(F) + 0.281*sin(M+F) + 0.278*sin(M-F)`

#### Bước 4: Chuyển Ecliptic sang Equatorial (Xích đạo)

```kotlin
val epsMoon = toRadians(23.439 - 0.0000004 * dJDMoon)  // Obliquity
val sinDeltaMoon = sin(betaMoon) * cos(epsMoon) + cos(betaMoon) * sin(epsMoon) * sin(lambdaMoon)
val deltaMoon = asin(sinDeltaMoon)
val alphaMoon = atan2(sin(lambdaMoon) * cos(epsMoon) - tan(betaMoon) * sin(epsMoon), cos(lambdaMoon))
```

**Công thức chuyển đổi:**
```
sin(δ) = sin(β) * cos(ε) + cos(β) * sin(ε) * sin(λ)
δ = asin(sin(δ))
tan(α) = (sin(λ) * cos(ε) - tan(β) * sin(ε)) / cos(λ)
```
Trong đó:
- `λ`: Kinh độ hoàng đạo
- `β`: Vĩ độ hoàng đạo
- `ε`: Độ nghiêng trục (23.439°)
- `α`: Right Ascension
- `δ`: Declination

#### Bước 5: Tính Greenwich Mean Sidereal Time (GMST)

```kotlin
val t = dJDMoon / 36525.0
val gmst = 280.46061837 + 360.98564736629 * dJDMoon + 0.000387933 * t * t - t * t * t / 38710000.0
val gmstDeg = gmst % 360.0
val gmstRad = toRadians(gmstDeg)
```

**Công thức GMST:**
```
GMST = 280.46061837 + 360.98564736629 * dJD + 0.000387933 * T² - T³/38710000
T = dJD / 36525.0
```

#### Bước 6: Tính tọa độ 3D của Moon

```kotlin
val moonLonRad = alphaMoon - gmstRad
val rMoonDist = 0.1f * 3.0f // Khoảng cách từ Earth (đã thu nhỏ)
val mX = (rMoonDist * cos(deltaMoon) * sin(moonLonRad)).toFloat()
val mY = (rMoonDist * sin(deltaMoon)).toFloat()
val mZ = (rMoonDist * cos(deltaMoon) * cos(moonLonRad)).toFloat()
```

**Công thức chuyển đổi Equatorial sang Cartesian:**
```
x = r * cos(δ) * sin(α - GMST)
y = r * sin(δ)
z = r * cos(δ) * cos(α - GMST)
```

### 1.5 Tính vị trí Sun (EarthRenderer.kt)

Sun được tính toán tương tự Moon nhưng đơn giản hơn vì Sun nằm trên ecliptic.

#### Bước 1: Tính Julian Date (tương tự Moon)

```kotlin
val utcCalendarSun = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
val utcTimeMillisSun = utcCalendarSun.timeInMillis
val jdSun = utcTimeMillisSun / 86400000.0 + 2440587.5
val dJDSun = jdSun - 2451545.0
```

#### Bước 2: Tính Orbital Elements của Sun

```kotlin
var lSun = 280.466 + 0.9856474 * dJDSun  // Mean longitude
var mSun = 357.529 + 0.9856003 * dJDSun  // Mean anomaly
var wSun = 282.940 + 4.70935e-5 * dJDSun  // Perihelion
```

#### Bước 3: Tính Ecliptic Longitude với Equation of Center

```kotlin
val cSun = (1.915 * sin(toRadians(mSun)) + 
            0.020 * sin(toRadians(2 * mSun))) * (1 - 0.003 * toRadians(mSun))
val lambdaSun = toRadians(lSun + cSun)
val betaSun = 0.0 // Sun nằm trên ecliptic
```

**Equation of Center:**
```
C = (1.915 * sin(M) + 0.020 * sin(2M)) * (1 - 0.003 * M)
λ = L + C
```

#### Bước 4-6: Chuyển sang Equatorial và tính tọa độ 3D (tương tự Moon)

```kotlin
val eps = toRadians(23.439 - 0.0000004 * dJDSun)
val sinDeltaSun = sin(betaSun) * cos(eps) + cos(betaSun) * sin(eps) * sin(lambdaSun)
val deltaSun = asin(sinDeltaSun)
val alphaSun = atan2(sin(lambdaSun) * cos(eps) - tan(betaSun) * sin(eps), cos(lambdaSun))

val tSun = dJDSun / 36525.0
val gmstSun = 280.46061837 + 360.98564736629 * dJDSun + 0.000387933 * tSun * tSun - tSun * tSun * tSun / 38710000.0
val gmstRadSun = toRadians(gmstSun % 360.0)
val sunLonRad = alphaSun - gmstRadSun

val lightX = (10.0 * cos(deltaSun) * sin(sunLonRad)).toFloat()
val lightY = (10.0 * sin(deltaSun)).toFloat()
val lightZ = (10.0 * cos(deltaSun) * cos(sunLonRad)).toFloat()
```

---

## 2. Cách hoạt động và công thức của KLT (Sparse Optical Flow)

### 2.1 Tổng quan

KLT (Kanade-Lucas-Tomasi) là thuật toán **Sparse Optical Flow** - tính toán vector chuyển động chỉ tại các điểm đặc trưng (corners, edges) thay vì toàn bộ pixel.

### 2.2 Các bước thực hiện

#### Bước 1: Phát hiện đặc trưng (Feature Detection)

```kotlin
Imgproc.goodFeaturesToTrack(prevGray, corners, maxCorners, 0.01, 3.0)
```

**Thuật toán Shi-Tomasi:**
```
Tìm các điểm có eigenvalues lớn của ma trận gradient M:
M = Σ [I_x², I_x*I_y; I_x*I_y, I_y²]
R = min(λ₁, λ₂)

Chọn điểm nếu R > threshold
```

**Tham số:**
- `maxCorners`: Số điểm tối đa (50)
- `0.01`: Quality level (ngưỡng chất lượng)
- `3.0`: Min distance (khoảng cách tối thiểu giữa các điểm)

#### Bước 2: Theo dõi điểm (Tracking)

```kotlin
Video.calcOpticalFlowPyrLK(
    prevGray, currGray, prevPts, currPts, 
    status, err, 
    lkWinSize, lkMaxLevel, lkCriteria, 0, 0.001
)
```

**Thuật toán Lucas-Kanade dạng Pyramid:**

**Tại mỗi level của pyramid:**
```
1. Giảm resolution của ảnh
2. Tính optical flow ở level thô
3. Upscale kết quả lên level chi tiết hơn
4. Tinh chỉnh bằng iterative Lucas-Kanade
```

**Lucas-Kanade cơ bản (giả định brightness constancy):**
```
I(x, y, t) = I(x+dx, y+dy, t+dt)
Linear Taylor expansion:
I_x * u + I_y * v + I_t = 0

Giải hệ phương trình cho N điểm:
A * v = -b
v = (A^T * A)^(-1) * A^T * b

Trong đó:
A = [I_x1, I_y1; I_x2, I_y2; ...; I_xN, I_yN]
b = [I_t1; I_t2; ...; I_tN]
v = [u; v] (vector chuyển động)
```

**Tham số:**
- `lkWinSize = Size(21, 21)`: Kích thước window tìm kiếm
- `lkMaxLevel = 3`: Số level pyramid
- `lkCriteria = TermCriteria(COUNT + EPS, 30, 0.01)`: Tiêu chí dừng (30 iterations hoặc accuracy 0.01)
- `0.001`: Epsilon cho backward-forward check

#### Bước 3: Lọc kết quả

```kotlin
for (i in statusArray.indices) {
    if (statusArray[i].toInt() == 1) {
        val e = errArray[i]
        if (e < 50.0) { // Lọc lỗi lớn
            val dx = currPtsArray[i].x - prevPtsArray[i].x
            val dy = currPtsArray[i].y - prevPtsArray[i].y
            dxList.add(dx)
            dyList.add(dy)
        }
    }
}
```

**Tiêu chí lọc:**
- `status == 1`: Point được track thành công
- `error < 50.0`: Lỗi tracking nhỏ

#### Bước 4: Tính median và smoothing

```kotlin
val medDx = median(dxList)
val medDy = median(dyList)

val newMv = Point(medDx / 5.0, medDy / 5.0)
if (prevMv == null) {
    currMv = newMv
} else {
    // Exponential smoothing
    currMv = Point(prevMv!!.x * 0.85 + newMv.x * 0.15, 
                   prevMv!!.y * 0.85 + newMv.y * 0.15)
}
```

**Công thức Exponential Smoothing:**
```
mv_t = α * mv_{t-1} + (1-α) * mv_new
α = 0.85 (smoothing factor)
```

**Tại sao dùng Median thay vì Mean?**
- Median ít bị ảnh hưởng bởi outliers (điểm track sai)
- Median robust hơn với noise

### 2.3 Ưu điểm và nhược điểm

**Ưu điểm:**
- Nhanh (chỉ xử lý ~50 điểm)
- Chính xác cho tracking object
- Ít tốn tài nguyên

**Nhược điểm:**
- Không hoạt động tốt nếu ít đặc trưng
- Bị mất track khi object bị che khuất

---

## 3. Cách hoạt động và công thức của Farneback (Dense Optical Flow)

### 3.1 Tổng quan

Farneback là thuật toán **Dense Optical Flow** - tính toán vector chuyển động cho **mọi pixel** trên ảnh.

### 3.2 Các bước thực hiện

#### Bước 1: Tính Optical Flow

```kotlin
Video.calcOpticalFlowFarneback(
    prevGray, currGray, flowGray,
    pyrScale = 0.5,
    levels = 3,
    winSize = 15,
    iterations = 3,
    polyN = 5,
    polySigma = 1.2,
    flags = 0
)
```

**Thuật toán Farneback:**

**Bước 1: Polynomial Expansion**
```
Tại mỗi pixel, xấp xỉ vùng lân cận bằng đa thức bậc 2:
f1(x) ≈ x^T * A1 * x + b1^T * x + c1
f2(x) ≈ x^T * A2 * x + b2^T * x + c2

Trong đó:
A: Ma trận 2x2 (gradient thông tin)
b: Vector 2x1 (shift)
c: Scalar (brightness offset)
```

**Bước 2: Tính displacement**
```
Giả định: f2(x) ≈ f1(x + d)
x^T * A2 * x + b2^T * x + c2 ≈ (x+d)^T * A1 * (x+d) + b1^T * (x+d) + c1

Giải cho d (displacement):
d = (G^T * G)^(-1) * G^T * h

Trong đó:
G = [A2 + A2^T; b2 - b1]
h = [b1 - b2; c2 - c1]
```

**Bước 3: Pyramid Processing**
```
Tương tự KLT, sử dụng multi-scale:
1. Downsample ảnh tạo pyramid
2. Tính flow ở level thô
3. Upscale và refine ở level chi tiết
```

**Tham số:**
- `pyrScale = 0.5`: Scale factor giữa các level pyramid
- `levels = 3`: Số level pyramid
- `winSize = 15`: Kích thước window tính flow
- `iterations = 3`: Số iterations tại mỗi level
- `polyN = 5`: Bậc đa thức (5 = bậc 2)
- `polySigma = 1.2`: Sigma cho Gaussian smoothing
- `flags = 0`: Không dùng initialization

#### Bước 2: Visualize Flow

```kotlin
private fun drawOptFlowMap(flow: Mat, flowmap: Mat, step: Int, color: Scalar) {
    val lineThickness = 4
    val circleRadius = 5

    for (y in halfStep until maxRows step step) {
        for (x in halfStep until maxCols step step) {
            val f = flow.get(y, x)
            val fx = f[0] // Flow vector x
            val fy = f[1] // Flow vector y

            val start = Point(x.toDouble(), y.toDouble())
            val end = Point(x + fx, y + fy)

            Imgproc.line(flowmap, start, end, color, lineThickness)
            Imgproc.circle(flowmap, start, circleRadius, color, -1)
        }
    }
}
```

**Công thức vẽ:**
- `start`: Vị trí pixel gốc
- `end`: Vị trí pixel sau khi dịch chuyển
- `step = 32`: Khoảng cách giữa các vector được vẽ (để tránh quá dày)

### 3.3 Ưu điểm và nhược điểm

**Ưu điểm:**
- Cung cấp thông tin chi tiết cho toàn bộ ảnh
- Phát hiện được motion ở vùng không có đặc trưng rõ
- Tốt cho segmentation và analysis

**Nhược điểm:**
- Chậm (tính toán cho mọi pixel)
- Tốn nhiều tài nguyên CPU/GPU
- Có thể có noise ở vùng texture thấp

---

## 4. Sensor Fusion - Kết hợp IMU và Optical Flow

### 4.1 Tổng quan

Sensor Fusion kết hợp dữ liệu từ IMU (Inertial Measurement Unit) và Optical Flow để ước tính vị trí chính xác hơn.

### 4.2 IMU Estimator

#### Bước 1: Đọc dữ liệu sensor

```kotlin
override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
        Sensor.TYPE_GYROSCOPE -> {
            rotationVector = event.values.clone()
            angularVelocity = event.values.clone()
        }
        Sensor.TYPE_MAGNETIC_FIELD -> magnitude = event.values.clone()
    }
}
```

#### Bước 2: Tính linear acceleration

```kotlin
linearAcceleration[0] = event.values[0] - gravity[0]
linearAcceleration[1] = event.values[1] - gravity[1]
linearAcceleration[2] = event.values[2] - gravity[2]
```

**Công thức:**
```
a_linear = a_measured - g
```
Trong đó `g` là vector trọng lực (9.8 m/s² hướng xuống).

#### Bước 3: Tích phân để ước tính velocity

```kotlin
velocity[0] += linearAcceleration[0] * deltaTime
velocity[1] += linearAcceleration[1] * deltaTime
velocity[2] += linearAcceleration[2] * deltaTime
```

**Công thức tích phân:**
```
v(t) = ∫ a(t) dt ≈ v(t-1) + a(t) * Δt
```

#### Bước 4: Low-pass filter kết hợp gyroscope

```kotlin
velocity[0] = 0.8f * velocity[0] + 0.2f * angularVelocity[0]
velocity[1] = 0.8f * velocity[1] + 0.2f * angularVelocity[1]
velocity[2] = 0.8f * velocity[2] + 0.2f * angularVelocity[2]
```

**Công thức Low-pass Filter:**
```
v_filtered = α * v_accel + (1-α) * v_gyro
α = 0.8
```

#### Bước 5: Tính orientation

```kotlin
val rotationMatrix = FloatArray(9)
SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnitude)

val orientationAngles = FloatArray(3)
SensorManager.getOrientation(rotationMatrix, orientationAngles)
```

**Công thức Rotation Matrix:**
```
Sử dụng accelerometer và magnetometer để tính rotation matrix
R = f(g, m)
```

**Orientation Angles:**
- `azimuth`: Góc quanh trục Z
- `pitch`: Góc quanh trục X
- `roll`: Góc quanh trục Y

#### Bước 6: Tích phân để ước tính position

```kotlin
position[0] += velocity[0] * deltaTime
position[1] += velocity[1] * deltaTime
position[2] += velocity[2] * deltaTime
```

**Công thức:**
```
p(t) = ∫ v(t) dt ≈ p(t-1) + v(t) * Δt
```

### 4.3 Sensor Fusion (BasicFusion)

Hiện tại BasicFusion là placeholder:

```kotlin
class BasicFusion : SensorFusion {
    override fun getPosition(
        imuVelocity: FloatArray,
        imuPosition: FloatArray,
        ofPosition: Point
    ): FloatArray {
        return FloatArray(3) // Placeholder
    }
}
```

**Công thức fusion lý tưởng (để triển khai sau):**

**Complementary Filter:**
```
p_fused = α * p_imu + (1-α) * p_of
```

**Kalman Filter:**
```
Predict:
x̂_k = A * x̂_{k-1} + B * u_k
P_k = A * P_{k-1} * A^T + Q

Update:
K = P_k * H^T * (H * P_k * H^T + R)^(-1)
x̂_k = x̂_k + K * (z_k - H * x̂_k)
P_k = (I - K * H) * P_k
```

Trong đó:
- `x̂`: State estimate (position, velocity)
- `P`: Covariance matrix
- `Q`: Process noise covariance
- `R`: Measurement noise covariance
- `z`: Measurement (from OF and IMU)

---

## 5. Tóm tắt các công thức quan trọng

### 5.1 Tọa độ OpenGL
```
x = r * cos(φ) * sin(θ)
y = r * sin(φ)
z = r * cos(φ) * cos(θ)
```

### 5.2 Vệ tinh
```
v = √(μ / r)
range = (-b + √(b² - 4ac)) / 2a
```

### 5.3 Moon/Sun (Astronomical)
```
JD = milliseconds / 86400000.0 + 2440587.5
λ = L + corrections
δ = asin(sin(β) * cos(ε) + cos(β) * sin(ε) * sin(λ))
α = atan2(sin(λ) * cos(ε) - tan(β) * sin(ε), cos(λ))
GMST = 280.46061837 + 360.98564736629 * dJD + ...
```

### 5.4 KLT
```
A * v = -b
v = (A^T * A)^(-1) * A^T * b
mv_t = 0.85 * mv_{t-1} + 0.15 * mv_new
```

### 5.5 Farneback
```
f(x) ≈ x^T * A * x + b^T * x + c
d = (G^T * G)^(-1) * G^T * h
```

### 5.6 IMU
```
a_linear = a_measured - g
v(t) = v(t-1) + a(t) * Δt
p(t) = p(t-1) + v(t) * Δt
```

---

## Tài liệu tham khảo

1. **Lucas-Kanade Optical Flow**: Lucas, B. D., & Kanade, T. (1981)
2. **Shi-Tomasi Corner Detection**: Shi, J., & Tomasi, C. (1994)
3. **Farneback Optical Flow**: Farnebäck, G. (2003)
4. **Astronomical Algorithms**: Meeus, J. (1998)
5. **GNSS Satellite Positioning**: Kaplan, E. D., & Hegarty, C. J. (2005)
4. **Sensor Fusion**: Maybeck, P. S. (1979) - Kalman Filtering

---

## 6. Cap nhat GNSS SatellitePvt runtime

### 6.1 Muc tieu

Phan GNSS da duoc mo rong de uu tien lay vi tri ve tinh that tu GNSS raw measurements, thay vi chi suy ra tu `observerLat`, `observerLon`, `azimuthDegrees`, `elevationDegrees` va ban kinh quy dao xap xi.

He thong hien tai hoat dong theo nguyen tac:
- Prefer `SatellitePvt` neu chipset tra ve du lieu that
- Fallback ve nhanh approximate cu neu thiet bi khong ho tro

### 6.2 Cac file da thay doi

- `app/src/main/java/com/example/gnssandopticalflowapp/gnss/GnssSatellitePvtResolver.kt`
- `app/src/main/java/com/example/gnssandopticalflowapp/gnss/SatelliteCalculator.kt`
- `app/src/main/java/com/example/gnssandopticalflowapp/screen/fragment/GNSSViewerFragment.kt`
- `app/src/main/java/com/example/gnssandopticalflowapp/model/SatelliteInfo.kt`
- `app/src/main/java/com/example/gnssandopticalflowapp/screen/dialog/Map3DInformationDialog.kt`

### 6.3 Luong xu ly moi

1. `GNSSViewerFragment` dang ky dong thoi `GnssStatus.Callback` va `GnssMeasurementsEvent.Callback`.
2. `GnssMeasurementsEvent` duoc dung de doc tung `GnssMeasurement`.
3. Moi measurement duoc thu resolve `SatellitePvt`.
4. Du lieu PVT duoc cache theo khoa `constellationType + svid`.
5. Khi `GnssStatus` cap nhat danh sach ve tinh, app ghep metadata hien thi voi PVT cache neu co.

### 6.4 Resolver qua reflection

`GnssSatellitePvtResolver.kt` dung reflection de truy cap:
- `hasSatellitePvt()`
- `getSatellitePvt()`
- `getPositionEcef()`
- `getVelocityEcef()`
- `getEphemerisSource()`

Huong nay giup:
- Khong hard-bind compile-time vao hidden/system API
- Van chay duoc tren may khong expose `SatellitePvt`

Neu reflection fail, resolver tra `null` va luong xu ly quay ve nhanh approximate.

### 6.5 Chuyen ECEF sang LLA

`SatelliteCalculator.kt` bo sung:

```kotlin
fun calculateSatellitePositionFromEcef(
    ecefX: Double,
    ecefY: Double,
    ecefZ: Double
): SatellitePositionResult
```

Ham nay dung tham so WGS84 de doi tu ECEF sang:
- `latitude`
- `longitude`
- `altitude`

### 6.6 Tinh toc do ve tinh that

Khi `SatellitePvt` co velocity ECEF, toc do duoc tinh bang:

```text
speed = sqrt(vx^2 + vy^2 + vz^2)
```

Ham bo sung:

```kotlin
fun calculateSpeedFromEcefVelocity(
    velocityX: Double?,
    velocityY: Double?,
    velocityZ: Double?
): Double?
```

### 6.7 Co che fallback

App hien tai co 2 nhanh:

`Real GNSS PVT`
- Dung khi `GnssMeasurement` tra `SatellitePvt`
- Vi tri ve tinh di truc tiep tu PVT that
- Toc do co the lay tu velocity ECEF

`Approximate`
- Dung khi khong co `SatellitePvt`
- Van tinh tu `observerLat`, `observerLon`, `azimuthDegrees`, `elevationDegrees`, `orbitRadius`
- Giu tuong thich voi thiet bi cu

### 6.8 Cache va timeout

Du lieu `SatellitePvt` duoc cache tam trong fragment theo `SatelliteKey`.

Timeout hien tai la 10 giay:
- Ban ghi qua cu se bi loai bo
- Tranh render vi tri ve tinh loi thoi

### 6.9 Tac dong len UI

`SatelliteInfo` bo sung:
- `positionSource`
- `ephemerisSource`

`Map3DInformationDialog` hien thi them:
- `Source: Real GNSS PVT` hoac `Source: Approximate`
- `Ephemeris: ...` neu co

### 6.10 Ghi chu runtime

- Khong phai moi thiet bi Android deu tra `SatellitePvt`
- `GnssCapabilities.hasSatellitePvt()` chi cho biet chipset co kha nang, khong dam bao moi measurement deu co PVT
- Logic cuoi cung van la prefer real PVT, fallback approximate

## 7. Cap nhat CelesTrak GP fallback ngoai thiet bi

### 7.1 Muc tieu

Mot so may nhu `S20 Ultra` co:
- `hasMeasurements=true`
- `hasSatellitePvt=false`
- `hasNavigationMessages=false`

Trong truong hop nay app khong the lay vi tri ve tinh that chi tu API GNSS local.

Nhanh moi dung du lieu ngoai tu `CelesTrak GP` de nang cap vi tri ve tinh, nhung van giu fallback approximate cu khi:
- fetch that bai
- khong map duoc `SVID`
- propagate orbit that bai

### 7.2 File moi va file chinh sua

- `app/src/main/java/com/example/gnssandopticalflowapp/gnss/CelesTrakSatelliteRepository.kt`
- `app/src/main/java/com/example/gnssandopticalflowapp/gnss/SatelliteCalculator.kt`
- `app/src/main/java/com/example/gnssandopticalflowapp/screen/fragment/GNSSViewerFragment.kt`

### 7.3 Nguon du lieu

Repository moi goi endpoint:

```text
https://celestrak.org/NORAD/elements/gp.php?GROUP=...&FORMAT=JSON
```

Hien tai app fetch 3 group:
- `GPS-OPS`
- `GALILEO`
- `BEIDOU`

Ly do:
- day la cac group map `SVID` kha chac tu ten ve tinh
- `GLONASS`, `QZSS`, `IRNSS`, `SBAS` hien chua co heuristic map on dinh tu ten object trong nhanh nay

### 7.4 Cache va tan suat refresh

`CelesTrakSatelliteRepository` cache snapshot toi thieu `2 gio`.

Dieu nay giup:
- giam so lan goi mang
- tranh vi pham khuyen nghi khong poll qua thuong xuyen cua CelesTrak
- tranh lag khi `GnssStatus` callback ban ra lien tuc

### 7.5 Map SVID

App map `SVID` tu `OBJECT_NAME` bang regex:
- GPS: `PRN xx`
- Galileo: `GALILEO xx`
- BeiDou: `(Cxx)`

Neu khong tach duoc `SVID`, record do bi bo qua va ve tinh se tiep tuc dung nhanh approximate.

### 7.6 Propagate orbit

`SatelliteCalculator.kt` bo sung:

```kotlin
fun calculateSatellitePositionFromMeanElements(...): OrbitStateResult
```

Ham nay:
- doc `MEAN_MOTION`
- `ECCENTRICITY`
- `INCLINATION`
- `RA_OF_ASC_NODE`
- `ARG_OF_PERICENTER`
- `MEAN_ANOMALY`
- `EPOCH`

Sau do:
- giai Kepler equation
- tinh vi tri orbital plane
- quay sang ECI
- doi sang ECEF bang Greenwich sidereal angle
- doi tiep sang `latitude`, `longitude`, `altitude`

Toc do duoc tinh bang cong thuc `vis-viva`.

### 7.7 Thu tu uu tien nguon du lieu

Trong `GNSSViewerFragment`, thu tu xu ly hien tai la:

1. `Real GNSS PVT`
2. `CelesTrak GP`
3. `Approximate`

Nghia la:
- neu may tra `SatellitePvt` thi van uu tien PVT that
- neu may khong tra PVT nhung co CelesTrak map duoc thi dung orbit ngoai
- neu ca hai deu khong co thi quay ve calculator cu

### 7.8 Logging runtime

Bo sung them cac nhom log:
- `GNSS_CAP`
- `GNSS_PVT_DEBUG`
- `GNSS_CELESTRAK`

Muc dich:
- phan biet may co support PVT hay khong
- biet ly do `PVT null`
- biet da nap duoc bao nhieu ve tinh map tu CelesTrak

### 7.9 Ghi chu do chinh xac

Nhanh `CelesTrak GP` tot hon approximate local vi dung orbital elements that tu ngoai, nhung no van:
- khong phai broadcast ephemeris GNSS native tren may
- khong phai full SGP4 implementation
- duoc toi uu cho visualization va fallback thuc dung

Neu muon do chinh xac GNSS cao hon nua, huong sau se la:
- parse `RINEX nav`
- hoac dung `broadcast ephemeris / precise ephemeris` tu IGS/BKG
