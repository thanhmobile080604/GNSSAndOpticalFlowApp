# PRODUCT.md

## 1. Boi Canh San Pham

`GNSSAndOpticalFlowApp` la ung dung Android phuc vu hai nhom trai nghiem chinh:

- Quan sat GNSS: ban do 2D, mo phong Trai Dat 3D, AR ve tinh, tim kiem dia diem va tuyen duong.
- Optical Flow: phan tich chuyen dong tu camera/video bang KLT, Farneback va AI RAFT, kem luu phien phan tich hieu nang.

AI phai lam viec nhu mot product-engineering teammate: hieu gia tri nguoi dung, rang buoc ky thuat, quyen rieng tu va kha nang ship tren Android truoc khi de xuat hoac sua gi.

Khong ap dung gia dinh cua app khac, monetization hoac backend auth neu code hien tai khong co.

---

## 2. Nguoi Dung Va Gia Tri Chinh

### 2.1 Nhom nguoi dung

- Nguoi hoc, demo hoac nghien cuu GNSS muon xem ve tinh theo nguon du lieu khac nhau.
- Nguoi can minh hoa AR/ban do cho ve tinh va vi tri nguoi dung.
- Nguoi thu nghiem computer vision muon so sanh KLT, Farneback va AI optical flow tren camera/video.
- Developer hoac evaluator can xem chi so FPS, thoi gian xu ly, so vector va confidence.

### 2.2 Gia tri san pham

- Bien du lieu GNSS kho hieu thanh ban do, mo hinh 3D va AR truc quan.
- Cho phep so sanh thuat toan optical flow tren du lieu that.
- Ho tro xu ly video dai bang background job, notification va lua chon online/offline.
- Luu analytics noi bo cua phien camera optical flow de phan tich lai.

---

## 3. Luong Nghiep Vu Chinh

### 3.1 Onboarding va Home

- App bat dau tai `IntroFragment`.
- `HomeFragment` dung ViewPager/tabs de vao mang GNSS va Optical Flow.
- Khong bien onboarding thanh marketing dai; muc tieu la dua nguoi dung vao chuc nang that nhanh.

### 3.2 GNSS Viewer

- Xin quyen vi tri va xu ly trang thai GPS.
- Hien thi vi tri hien tai tren osmdroid 2D map.
- Tim kiem dia diem, luu recent search, chon diem dich va xem route preview.
- Chuyen 2D/3D de xem Trai Dat, ve tinh, nguon du lieu vi tri va chi tiet ve tinh.
- Nguon vi tri ve tinh phai duoc hieu theo thu tu uu tien:
  1. Real GNSS PVT
  2. IGS Broadcast Ephemeris
  3. CelesTrak SGP4 hoac GP
  4. Approximate fallback

### 3.3 GNSS AR

- Can camera va fine location.
- ARCore la optional o manifest; app phai xu ly thiet bi khong ho tro ARCore.
- Hien thi ve tinh theo vi tri nguoi dung, nguon du lieu va huong camera.
- Co capture/record frame; moi thay doi phai giu vong doi AR session, camera va location an toan.

### 3.4 Camera Optical Flow

- Can quyen camera.
- Chay optical flow realtime bang CameraX.
- So sanh KLT va Farneback, cau hinh sensitivity, ROI va moving/still mode.
- IMU ho tro phat hien chuyen dong thiet bi; khong gia dinh sensor luon on dinh.
- Co record video va luu `AnalyticsSession` noi bo duoi dang JSON.

### 3.5 Video Optical Flow

- Nguoi dung chon video, mo `VideoProcessOptionsDialog`, chon thuat toan va mode.
- Thuat toan hien co:
  - KLT
  - Farneback
  - AI RAFT
- Display mode cho Farneback/AI gom vector va heatmap.
- ROI co the gioi han vung xu ly.
- `ProcessingMode.OFFLINE` xu ly on-device qua OpenCV/AI model.
- `ProcessingMode.ONLINE` upload video len server HTTPS cau hinh bang `OPTICAL_FLOW_SERVER_BASE_URL`.
- Background job dung WorkManager, progress bus, top-bar/bubble notification va system notification.
- Gioi han tai hien tai:
  - On-device hard limit: 2 job.
  - Online warning: 3 job.
  - Online hard limit: 5 job.
  - Global hard limit: 6 job.

### 3.6 Analytics Optical Flow

- Analytics hien la local analysis session, khong phai Firebase/GA4.
- Du lieu gom FPS, process time, feature/sample count, active vector count, vector trung binh, magnitude, threshold va confidence.
- Co list, delete, overview chart va sample detail.
- Khi de xuat "analytics events", phai noi ro do la de xuat tracking moi hay dang noi ve analytics local hien huu.

---

## 4. Nguyen Tac Product

### 4.1 Chinh xac hon la hao nhoang

GNSS va optical flow co sai so tu nhien. UI/copy khong duoc hua rang vi tri ve tinh, vector chuyen dong hoac ket qua AI tuyet doi chinh xac.

Luon giu hoac hien thi ngu canh can thiet:

- Nguon du lieu ve tinh.
- Trang thai quyen/GPS/camera/network.
- Mode xu ly video.
- Thuat toan, sensitivity, ROI va heatmap/vector mode.
- Progress, cancel, failed va completed state cho job dai.

### 4.2 MVP truoc

Khi them feature, uu tien thay doi nho, do duoc va gan voi flow hien tai. Khong them:

- IAP/subscription/paywall.
- Compose neu man hien tai la XML/ViewBinding.
- Hilt/DataStore/Firebase neu chua co yeu cau ro.
- Man hinh phu hoac animation moi neu khong can cho nghiep vu.
- Backend/auth moi neu server contract hien tai chua yeu cau.

### 4.3 Quyen rieng tu va niem tin

App xu ly camera, video, vi tri va upload server o mode online. Product decision phai kiem tra:

- Nguoi dung co biet khi nao video duoc upload khong?
- Server URL co HTTPS va khong dung placeholder production khong?
- Co cancel/retry/failure ro rang khong?
- Co tranh log du lieu nhay cam nhu vi tri chinh xac, file path rieng tu, token khong?
- Co thong bao quyen Android dung ngu canh khong?

---

## 5. Yeu Cau Khi Lam Product Task

Moi feature hoac thay doi nghiep vu nen tra loi:

1. User problem la gi?
2. Flow hien tai nam o man nao?
3. MVP can thay doi gi?
4. Nhung gi khong lam o scope nay?
5. Du lieu/permission/network nao bi anh huong?
6. Trang thai loading/empty/error/disabled/completed ra sao?
7. Can analytics local hay tracking event moi?
8. Rui ro ve hieu nang, quyen rieng tu hoac Play review la gi?
9. Cach test thu cong toi thieu la gi?

---

## 6. Copywriting

Copy phai ngan, ro va khong do loi nguoi dung.

Uu tien:

- "Camera permission is required to run optical flow."
- "Server processing is busy. Try again later or use on-device mode."
- "Satellite position source: IGS Broadcast."

Tranh:

- Hua "100% accurate".
- Goi AI la chac chan dung.
- Che giau upload online.
- Dung thong bao loi ky thuat khong giup nguoi dung biet buoc tiep theo.

---

## 7. Done Definition Cho Product

Mot product task chi duoc coi la xong khi:

- Flow nguoi dung ro rang.
- Scope MVP va out-of-scope ro.
- Cac quyen, du lieu va trang thai loi duoc xet.
- Rui ro performance/privacy/review duoc neu ro neu co.
- Acceptance criteria va test checklist co the chay duoc.
- Neu chi duoc yeu cau sua tai lieu `.ai`, tuyet doi khong sua code.
