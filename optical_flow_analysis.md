# Phân tích Cơ chế Optical Flow trong Dự án

Dự án **GNSSAndOpticalFlowApp** sử dụng kỹ thuật **Optical Flow** (Dòng quang học) để ước tính chuyển động của camera dựa trên sự thay đổi giữa các khung hình kề nhau. Dữ liệu này sau đó được kết hợp với cảm biến IMU (Gia tốc kế, Con quay hồi chuyển) thông qua **Sensor Fusion** để tăng độ chính xác của việc đo vận tốc và vị trí.

Trong ứng dụng, có **2 chế độ (mode)** tính toán vector chuyển động chính:

---

## 1. Chế độ KLT (Kanade-Lucas-Tomasi) - Sparse Optical Flow
Đây là chế độ **Dòng quang học thưa**.

### Cơ chế hoạt động:
*   **Phát hiện đặc trưng (Feature Detection):** Thuật toán sử dụng bộ lọc Shi-Tomasi (`goodFeaturesToTrack`) để tìm các "điểm tốt" (thường là các góc cạnh, điểm có độ tương phản cao) trong khung hình.
*   **Theo dõi (Tracking):** Sử dụng thuật toán Lucas-Kanade dạng kim tự tháp (`calcOpticalFlowPyrLK`) để tìm kiếm vị trí mới của *chỉ những điểm đó* trong khung hình tiếp theo.

### Những gì nó phát hiện:
*   Phát hiện sự dịch chuyển của các điểm đặc trưng cụ thể.
*   Nó tính toán vector dịch chuyển trung bình của các điểm này để suy ra hướng di chuyển tổng thể của camera.

### Đặc điểm:
*   **Tốc độ:** Rất nhanh vì chỉ xử lý một số lượng điểm nhất định (ví dụ: tối đa 50 điểm).
*   **Ứng dụng:** Thích hợp khi môi trường có nhiều góc cạnh rõ nét.

---

## 2. Chế độ Farneback (FraneBack) - Dense Optical Flow
Đây là chế độ **Dòng quang học dày đặc**.

### Cơ chế hoạt động:
*   **Toàn cảnh:** Thay vì tìm các điểm đặc trưng, thuật toán Farneback (`calcOpticalFlowFarneback`) tính toán vector chuyển động cho **mọi pixel** (hoặc một lưới pixel dày đặc) trên toàn bộ khung hình.
*   **Đa phân giải:** Nó xấp xỉ vùng lân cận của mỗi pixel bằng một đa thức bậc hai để theo dõi sự thay đổi.

### Những gì nó phát hiện:
*   Phát hiện một "trường chuyển động" (motion field) bao trùm toàn bộ hình ảnh.
*   Có thể thấy được các chuyển động phức tạp ở những vùng không có góc cạnh rõ ràng.

### Đặc điểm:
*   **Độ chi tiết:** Cung cấp thông tin cực kỳ chi tiết về sự thay đổi của toàn bộ khung cảnh.
*   **Tốc độ:** Chậm hơn KLT vì khối lượng tính toán khổng lồ cho từng pixel.
*   **Hiển thị:** Trong code, nó vẽ các đường xanh nhỏ trên lưới `64x64` để mô tả hướng chuyển động của từng vùng.

---

## Ý nghĩa của "Motion Vector" trong Project
*   **Ước tính vị trí:** Cả hai chế độ đều trả về một `Point` mô tả sự thay đổi vị trí trung bình. 
*   **Visualization (`MotionVectorViz`):** Lớp này nhận các điểm vị trí mới, nối chúng lại thành một đường liên tục (trailers) trên một bản đồ đen để bạn xem được lộ trình di chuyển của camera (giống như vẽ đường đi).
*   **Kết hợp IMU:** Chế độ KLT thường được ưu tiên để kết hợp với `IMUEstimator` vì nó cung cấp dữ liệu ổn định về các "mốc" (landmarkers) trong không gian.

---

## Tóm tắt so sánh

| Đặc điểm | KLT (Sparse) | Farneback (Dense) |
| :--- | :--- | :--- |
| **Đối tượng detect** | Các góc, điểm đặc trưng mạnh. | Mọi vùng/pixel trên ảnh. |
| **Số lượng Vector** | Ít (chỉ tại các điểm đặc trưng). | Rất nhiều (toàn bộ khung hình). |
| **Độ phức tạp** | Thấp, tiết kiệm pin/CPU hơn. | Cao, yêu cầu xử lý mạnh hơn. |
| **Mục đích chính** | Theo dõi vật thể, đo vận tốc camera. | Phân tích dòng chảy hình ảnh, phân đoạn chuyển động. |
