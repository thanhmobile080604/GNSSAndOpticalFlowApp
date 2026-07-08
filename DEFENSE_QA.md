# DEFENSE_QA - Bộ câu hỏi và trả lời bảo vệ đồ án

> Tài liệu này được viết theo vai trò giảng viên phản biện trong phiên bảo vệ đồ án.
> Câu trả lời dùng ngôi "em", ngắn gọn nhưng giải thích đúng bản chất, bám sát nội dung đồ án.
> Trọng tâm cần ôn kỹ nhất là Chương 5: các giải pháp và đóng góp nổi bật.

---

## Mục lục

1. [Tổng quan đề tài](#1-tổng-quan-đề-tài)
2. [Lý do chọn đề tài](#2-lý-do-chọn-đề-tài)
3. [Kiến thức nền về GNSS](#3-kiến-thức-nền-về-gnss)
4. [Kiến thức nền về Optical Flow](#4-kiến-thức-nền-về-optical-flow)
5. [Cách hệ thống kết hợp GNSS và Optical Flow](#5-cách-hệ-thống-kết-hợp-gnss-và-optical-flow)
6. [Câu hỏi trọng tâm về Chương 5](#6-câu-hỏi-trọng-tâm-về-chương-5)
7. [Câu hỏi về thuật toán và xử lý ảnh](#7-câu-hỏi-về-thuật-toán-và-xử-lý-ảnh)
8. [Câu hỏi về dữ liệu, thực nghiệm và đánh giá](#8-câu-hỏi-về-dữ-liệu-thực-nghiệm-và-đánh-giá)
9. [Câu hỏi về sai số, giới hạn và rủi ro](#9-câu-hỏi-về-sai-số-giới-hạn-và-rủi-ro)
10. [Câu hỏi phản biện khó](#10-câu-hỏi-phản-biện-khó)
11. [Câu hỏi về hướng phát triển](#11-câu-hỏi-về-hướng-phát-triển)
12. [Bộ câu hỏi trả lời nhanh trước khi bảo vệ](#12-bộ-câu-hỏi-trả-lời-nhanh-trước-khi-bảo-vệ)

---

## 1. Tổng quan đề tài

### 1.1. Đề tài của em giải quyết bài toán gì?

**Trả lời:**  
Đề tài xây dựng một hệ thống Android để trực quan hóa dữ liệu GNSS và phân tích chuyển động camera bằng Optical Flow. Hệ thống không chỉ hiển thị vị trí người dùng trên bản đồ, mà còn cho phép quan sát vệ tinh, xem mô hình 3D/AR, chạy KLT/Farneback trên camera, xử lý video bằng RAFT trên backend và thử nghiệm hỗ trợ chỉ đường khi tín hiệu GNSS suy giảm.

### 1.2. Sản phẩm cuối cùng của đồ án gồm những thành phần nào?

**Trả lời:**  
Sản phẩm gồm ứng dụng Android và backend xử lý video. Ứng dụng Android phụ trách bản đồ, GNSS, camera, IMU, Optical Flow thời gian thực, lưu phiên đo và giao diện người dùng. Backend Python/FastAPI phụ trách xử lý video nặng bằng mô hình RAFT, quản lý job, upload/download theo chunk, hủy job và dọn tài nguyên.

### 1.3. Các nhóm chức năng chính của hệ thống là gì?

**Trả lời:**  
Trong đồ án, hệ thống có các nhóm chức năng chính:

- Quan sát vệ tinh GNSS trên bản đồ, mô hình 3D và AR.
- Xem bản đồ hai chiều, tìm kiếm địa điểm và tuyến đường.
- Chỉ đường trực tiếp có hỗ trợ camera/IMU khi GNSS yếu.
- Xem Optical Flow trên camera bằng KLT hoặc Farneback.
- Phân tích hiệu năng KLT và Farneback.
- Xử lý video Optical Flow bằng RAFT trên backend.
- Xem lại dữ liệu đã lưu như phiên phân tích, tuyến đường và media.

### 1.4. Đóng góp chính của đồ án là gì?

**Trả lời:**  
Đóng góp chính nằm ở việc tích hợp nhiều luồng kỹ thuật vào một hệ thống thử nghiệm thống nhất. Cụ thể, Chương 5 nêu sáu đóng góp: chuỗi ưu tiên phân giải vị trí vệ tinh GNSS, cơ chế hỗ trợ chỉ đường khi GNSS yếu, so sánh hiệu năng KLT/Farneback, pipeline RAFT bất đồng bộ trên server, bám đối tượng trong camera trực tiếp và hiệu ứng giao diện Liquid Glass theo khả năng thiết bị.

### 1.5. Đồ án này có phải là một ứng dụng bản đồ hoàn chỉnh như Google Maps không?

**Trả lời:**  
Không. Trong phạm vi đồ án, hệ thống không nhằm thay thế ứng dụng bản đồ thương mại. Mục tiêu chính là xây dựng nguyên mẫu kỹ thuật để quan sát GNSS, Optical Flow, thử nghiệm hỗ trợ định vị khi GNSS suy giảm và đánh giá các luồng xử lý trong cùng một ứng dụng.

### 1.6. Phạm vi nghiên cứu của đồ án dừng ở đâu?

**Trả lời:**  
Đồ án dừng ở mức nguyên mẫu kỹ thuật. Hệ thống đã chạy được trên thiết bị thật và backend cục bộ, nhưng chưa phải dịch vụ thương mại, chưa tối ưu cho nhiều người dùng đồng thời, chưa có benchmark quy mô lớn và chức năng LiveRouting vẫn đang ở mức thử nghiệm, chưa đạt độ ổn định như hệ định vị công nghiệp.

---

## 2. Lý do chọn đề tài

### 2.1. Vì sao em chọn đề tài kết hợp GNSS và Optical Flow?

**Trả lời:**  
Vì GNSS là nguồn định vị phổ biến trên điện thoại nhưng dễ bị suy giảm ở hầm, đô thị dày đặc hoặc nơi có vật che khuất. Trong khi đó, camera và IMU có thể cung cấp thông tin chuyển động tương đối của thiết bị. Đồ án chọn hướng kết hợp này để khảo sát khả năng hỗ trợ định vị ngắn hạn khi GNSS không còn đủ tin cậy.

### 2.2. Khoảng trống của các ứng dụng hiện có là gì?

**Trả lời:**  
Ứng dụng bản đồ phổ thông thường chỉ hiển thị kết quả định vị cuối cùng, ít cho thấy dữ liệu vệ tinh phía sau. Ứng dụng GNSS chuyên dụng có thể hiển thị vệ tinh nhưng thường tách rời bản đồ và xử lý ảnh. Các demo Optical Flow lại thường thiếu lưu phiên, so sánh thuật toán và backend xử lý video. Đồ án cố gắng gom các phần này vào một hệ thống quan sát và thử nghiệm thống nhất.

### 2.3. Vì sao đề tài không chỉ làm GNSS mà còn thêm Optical Flow?

**Trả lời:**  
Nếu chỉ làm GNSS thì hệ thống chủ yếu dừng ở trực quan hóa vệ tinh. Optical Flow giúp bổ sung một hướng dữ liệu khác là chuyển động biểu kiến từ camera. Nhờ đó đồ án có thêm khả năng so sánh thuật toán, bám đối tượng và thử nghiệm hỗ trợ LiveRouting khi GNSS yếu.

### 2.4. Vì sao cần backend RAFT, trong khi Android đã chạy KLT/Farneback?

**Trả lời:**  
KLT và Farneback phù hợp xử lý thời gian thực trên điện thoại vì nhẹ hơn. RAFT là mô hình học sâu cho dense optical flow, biểu diễn chuyển động chi tiết hơn nhưng nặng về tính toán. Vì vậy đồ án đưa RAFT lên backend để xử lý video ngoại tuyến, tránh làm quá tải thiết bị Android.

### 2.5. Ý nghĩa thực tiễn của đề tài là gì?

**Trả lời:**  
Đề tài có ý nghĩa như một công cụ học tập, kiểm thử và quan sát trực quan. Người dùng có thể xem dữ liệu vệ tinh, kiểm tra chất lượng GNSS, quan sát Optical Flow, so sánh thuật toán và thử nghiệm hành vi khi GNSS suy giảm. Đây là nền tảng để phát triển tiếp các cơ chế định vị lai sau này.

---

## 3. Kiến thức nền về GNSS

### 3.1. GNSS là gì?

**Trả lời:**  
GNSS là nhóm hệ thống vệ tinh dẫn đường toàn cầu như GPS, Galileo, BeiDou, GLONASS. Trên điện thoại Android, GNSS cung cấp vị trí người dùng và thông tin vệ tinh quan sát được như SVID, chòm vệ tinh, azimuth, elevation, C/N0 và trạng thái used-in-fix.

### 3.2. Trong Android, đồ án lấy dữ liệu GNSS từ đâu?

**Trả lời:**  
Đồ án dùng các API nền tảng của Android như `LocationManager`, `GnssStatus` và `GnssMeasurementsEvent`. `LocationManager` cung cấp vị trí người dùng, `GnssStatus` cung cấp trạng thái vệ tinh, còn `GnssMeasurementsEvent` cung cấp dữ liệu đo GNSS thô hơn nếu thiết bị hỗ trợ.

### 3.3. Azimuth và elevation của vệ tinh có ý nghĩa gì?

**Trả lời:**  
Azimuth là góc phương vị của vệ tinh quanh người quan sát, còn elevation là góc cao của vệ tinh so với đường chân trời. Hai thông tin này giúp biết vệ tinh đang nằm ở hướng nào trên bầu trời. Tuy nhiên, chỉ từ azimuth/elevation thì tọa độ vệ tinh trong không gian chỉ là xấp xỉ, không chính xác bằng PVT hoặc dữ liệu quỹ đạo.

### 3.4. Vì sao hệ thống cần phân giải vị trí vệ tinh từ nhiều nguồn?

**Trả lời:**  
Vì không phải thiết bị Android nào cũng cung cấp đầy đủ tọa độ vệ tinh thật. Để giao diện không bị trống và vẫn tận dụng nguồn tốt nhất khi có, đồ án dùng chuỗi ưu tiên: PVT thật từ thiết bị, IGS Broadcast Ephemeris, CelesTrak TLE/SGP4 và cuối cùng là xấp xỉ từ azimuth/elevation.

### 3.5. PVT, IGS, CelesTrak và Approximate khác nhau như thế nào?

**Trả lời:**  
PVT là nguồn tốt nhất nếu thiết bị trích xuất được tọa độ và vận tốc vệ tinh thật. IGS Broadcast Ephemeris là lịch vệ tinh công khai dùng để lan truyền quỹ đạo. CelesTrak TLE/SGP4 dùng phần tử quỹ đạo hai dòng, phù hợp trực quan hóa với kích thước dữ liệu nhỏ. Approximate là nguồn dự phòng, tính xấp xỉ từ azimuth, elevation và vị trí người quan sát.

### 3.6. Vì sao đồ án dùng hệ tọa độ WGS84 và ECEF?

**Trả lời:**  
WGS84 là hệ quy chiếu phổ biến cho vĩ độ, kinh độ và độ cao. ECEF biểu diễn điểm trong hệ tọa độ Descartes gắn với Trái Đất, thuận tiện cho tọa độ vệ tinh và phép dựng 3D. Đồ án chuyển đổi giữa ECEF và LLA để vừa tính toán không gian, vừa hiển thị trên bản đồ và mô hình Trái Đất.

### 3.7. C/N0 và used-in-fix dùng để làm gì?

**Trả lời:**  
C/N0 phản ánh cường độ tín hiệu vệ tinh, còn used-in-fix cho biết vệ tinh có được dùng trong nghiệm định vị hay không. Trong đồ án, các thông tin này giúp người dùng hiểu chất lượng quan sát GNSS thay vì chỉ nhìn một điểm vị trí cuối cùng trên bản đồ.

### 3.8. GNSS có sai số thì hệ thống xử lý như thế nào?

**Trả lời:**  
Trong phạm vi đồ án, hệ thống không tự sửa toàn bộ sai số GNSS như thiết bị chuyên dụng. Hệ thống đánh giá độ tin cậy của GNSS, dùng GNSS làm nguồn chính khi còn tốt và chuyển sang hỗ trợ bằng Optical Flow/IMU khi GNSS suy giảm. Với vệ tinh, hệ thống gắn nhãn nguồn dữ liệu để người dùng biết mức tin cậy tương đối.

---

## 4. Kiến thức nền về Optical Flow

### 4.1. Optical Flow là gì?

**Trả lời:**  
Optical Flow là trường vector mô tả chuyển động biểu kiến của điểm ảnh giữa hai khung hình liên tiếp. Nói đơn giản, nó ước lượng mỗi điểm ảnh hoặc mỗi điểm đặc trưng đã dịch chuyển bao nhiêu pixel theo trục ngang và dọc.

### 4.2. Giả thiết cơ bản của Optical Flow là gì?

**Trả lời:**  
Giả thiết cơ bản là độ sáng của cùng một điểm trong cảnh gần như không đổi trong khoảng thời gian ngắn. Từ giả thiết đó, ta có ràng buộc:

```text
Ix * u + Iy * v + It = 0
```

Trong đó `u` và `v` là vận tốc ảnh theo hai trục, còn `Ix`, `Iy`, `It` là gradient theo không gian và thời gian.

### 4.3. Vì sao một phương trình Optical Flow lại chưa đủ để tìm chuyển động?

**Trả lời:**  
Vì phương trình có hai ẩn `u` và `v` nhưng chỉ có một ràng buộc tại mỗi pixel. Do đó bài toán thiếu ràng buộc, thường gọi là aperture problem. Các thuật toán như Lucas-Kanade hoặc Farneback phải thêm giả thiết cục bộ để giải.

### 4.4. KLT hoạt động theo ý tưởng nào?

**Trả lời:**  
KLT trong đồ án kết hợp phát hiện điểm đặc trưng Shi-Tomasi và theo dõi Lucas-Kanade dạng kim tự tháp. Thuật toán chọn các điểm có gradient tốt, sau đó tìm vị trí tương ứng của các điểm đó ở frame tiếp theo. Vì chỉ theo dõi số điểm thưa nên KLT nhẹ và phù hợp thời gian thực.

### 4.5. Farneback khác KLT ở điểm nào?

**Trả lời:**  
KLT là sparse optical flow, chỉ theo dõi các điểm đặc trưng. Farneback là dense optical flow, ước lượng chuyển động dày đặc hơn trên ảnh bằng cách xấp xỉ vùng lân cận bằng đa thức bậc hai. Farneback cho cái nhìn tổng thể hơn nhưng chi phí xử lý cao hơn.

### 4.6. RAFT khác KLT và Farneback như thế nào?

**Trả lời:**  
RAFT là mô hình học sâu cho Optical Flow, xây dựng volume tương quan toàn cặp giữa hai ảnh và cập nhật flow lặp. RAFT có khả năng biểu diễn chuyển động dày đặc và phức tạp hơn, nhưng nặng hơn nên trong đồ án được đặt ở backend để xử lý video ngoại tuyến.

### 4.7. Optical Flow có cho ra vận tốc thật theo mét/giây không?

**Trả lời:**  
Không trực tiếp. Optical Flow chủ yếu cho chuyển động theo pixel giữa các frame. Muốn quy đổi sang mét/giây cần hiệu chỉnh tỷ lệ, ví dụ dựa vào GNSS khi tín hiệu còn tốt. Đồ án cũng trình bày rõ LiveRouting chỉ là visual odometry thực dụng, không phải hệ khôi phục pose và tỷ lệ tuyệt đối đầy đủ.

### 4.8. Khi camera rung hoặc thiếu texture thì Optical Flow bị ảnh hưởng ra sao?

**Trả lời:**  
Camera rung làm vector nhiễu và khó phân biệt chuyển động thật của thiết bị với rung cục bộ. Thiếu texture khiến KLT khó tìm điểm đặc trưng, còn Farneback cũng có thể cho trường chuyển động kém ổn định. Vì vậy hệ thống có các chỉ số chất lượng, độ tin cậy tương đối và trong nhiều trường hợp cần giảm trọng số camera.

### 4.9. Forward-Backward Error trong đồ án dùng để làm gì?

**Trả lời:**  
Forward-Backward Error kiểm tra tính nhất quán của vector: theo dõi điểm từ frame trước sang frame sau, rồi theo dõi ngược lại. Nếu điểm quay về gần vị trí ban đầu, vector được xem là inlier. Trong đồ án, ngưỡng 1.5 pixel được dùng để tính độ tin cậy tương đối, không phải độ chính xác tuyệt đối so với ground truth.

---

## 5. Cách hệ thống kết hợp GNSS và Optical Flow

### 5.1. Hệ thống kết hợp GNSS và Optical Flow trong chức năng nào?

**Trả lời:**  
Việc kết hợp rõ nhất nằm ở LiveRouting. Khi GNSS còn tin cậy, vị trí và tốc độ từ Android Location API là nguồn chính. Khi GNSS yếu hoặc mất, hệ thống chuyển sang dead reckoning dựa trên Optical Flow, IMU và con quay hồi chuyển để duy trì marker trong thời gian ngắn.

### 5.2. Khi GNSS còn tốt, Optical Flow có vai trò gì?

**Trả lời:**  
Khi GNSS còn tốt, hệ thống dùng GNSS làm chuẩn chính và tranh thủ hiệu chỉnh hệ số quy đổi từ pixel/giây của Optical Flow sang mét/giây. Nói cách khác, GNSS giúp hệ thống học tỷ lệ tương đối cho camera trước khi xảy ra mất tín hiệu.

### 5.3. Khi GNSS yếu hoặc mất, hệ thống làm gì?

**Trả lời:**  
Hệ thống tạo bộ đo chuyển động thị giác từ Optical Flow, kết hợp yaw rate từ con quay hồi chuyển và trạng thái chuyển động từ IMU. Sau đó hệ thống ước lượng tốc độ, hướng và vị trí mới bằng dead reckoning. Nếu chế độ SNAP bật và vị trí đủ tin cậy, marker được kéo một phần về tuyến đường hiện tại.

### 5.4. Vì sao không chỉ dùng tốc độ cuối cùng của GNSS để ngoại suy?

**Trả lời:**  
Nếu giữ nguyên tốc độ cuối cùng thì khi xe tăng tốc, giảm tốc hoặc dừng, sai số sẽ tăng rất nhanh. Đồ án dùng Optical Flow để nhận biết thay đổi chuyển động ảnh, rồi trộn với prior từ tốc độ GNSS cuối cùng; prior này giảm dần theo thời gian mất tín hiệu để tránh giả định xe luôn chạy đều.

### 5.5. Vì sao không chỉ dùng Optical Flow để định vị?

**Trả lời:**  
Vì camera đơn không tự cung cấp tỷ lệ tuyệt đối theo mét nếu không có hiệu chỉnh. Optical Flow cũng phụ thuộc ánh sáng, texture, rung camera, vật thể chuyển động trong cảnh và góc đặt điện thoại. Do đó trong đồ án, Optical Flow chỉ là nguồn hỗ trợ khi GNSS suy giảm, không thay thế hoàn toàn GNSS.

### 5.6. Chế độ SNAP và REAL khác nhau như thế nào?

**Trả lời:**  
REAL hiển thị thẳng vị trí dead reckoning để người dùng thấy quỹ đạo ước lượng thật đang trôi ra sao. SNAP kéo vị trí một phần về tuyến đã chọn nếu khoảng cách, hướng và tính liên tục đủ tin cậy. SNAP giúp hiển thị ổn định hơn, nhưng đồ án nhấn mạnh đây không phải map matching liên tục vào toàn bộ mạng đường.

### 5.7. ZUPT có vai trò gì trong hệ thống?

**Trả lời:**  
ZUPT là Zero-velocity Update, dùng để nhận biết trạng thái gần như đứng yên và đưa vận tốc ước lượng về gần 0. Vai trò của nó là giảm trôi vận tốc khi thiết bị dừng, vì nếu chỉ tích lũy IMU hoặc Optical Flow thì sai số có thể tăng theo thời gian.

### 5.8. Khi hệ thống phát hiện lệch tuyến thì xử lý thế nào?

**Trả lời:**  
Theo Chương 5, nếu quỹ đạo lệch tuyến qua nhiều mẫu liên tiếp và còn Internet, hệ thống có thể yêu cầu tuyến mới từ vị trí ước lượng tới đích. Cách này giúp không che giấu việc người dùng có thể đã đi sai đường.

---

## 6. Câu hỏi trọng tâm về Chương 5

### 6.1. Chương 5 trình bày những đóng góp nổi bật nào?

**Trả lời:**  
Chương 5 trình bày sáu đóng góp: phân giải vị trí vệ tinh GNSS theo thứ tự ưu tiên nguồn dữ liệu, hỗ trợ chỉ đường khi GNSS yếu bằng dead reckoning, phân tích hiệu năng KLT và Farneback, quy trình xử lý video RAFT bất đồng bộ trên máy chủ, bám đối tượng trong camera trực tiếp và dựng hiệu ứng Liquid Glass theo nhiều mức khả năng Android.

### 6.2. Đóng góp về GNSS ở Chương 5 là gì?

**Trả lời:**  
Đóng góp là cơ chế phân giải vị trí vệ tinh có thứ tự ưu tiên rõ ràng. Hệ thống lần lượt thử PVT thật, IGS Broadcast Ephemeris, CelesTrak TLE/SGP4 và cuối cùng là xấp xỉ từ azimuth/elevation. Mỗi kết quả được gắn nhãn nguồn để người dùng biết dữ liệu đang hiển thị có độ tin cậy ở mức nào.

### 6.3. Vì sao PVT được ưu tiên cao nhất?

**Trả lời:**  
Vì PVT là dữ liệu tọa độ và vận tốc vệ tinh thật nếu thiết bị trích xuất được. Đây là nguồn sát với trạng thái đo của thiết bị nhất. Tuy nhiên, không phải Android/chipset nào cũng cung cấp ổn định nên hệ thống vẫn cần các nguồn dự phòng.

### 6.4. Điều kiện hợp lệ của các nguồn GNSS trong Chương 5 là gì?

**Trả lời:**  
Theo Chương 5, PVT chỉ dùng khi chưa quá hạn 10 giây. IGS Broadcast Ephemeris chỉ dùng khi thời điểm bản tin gần thời điểm quan sát trong ngưỡng 12 giờ. CelesTrak dùng cho các chòm được hỗ trợ. Nếu các nguồn trên không khả dụng, hệ thống dùng vị trí xấp xỉ từ azimuth/elevation và vị trí người quan sát.

### 6.5. Việc gắn nhãn nguồn vị trí vệ tinh có ý nghĩa gì?

**Trả lời:**  
Nhãn nguồn giúp kết quả minh bạch. Cùng là một điểm vệ tinh trên giao diện, người dùng có thể biết nó đến từ PVT, IGS, CelesTrak hay chỉ là xấp xỉ. Điều này cũng hỗ trợ gỡ lỗi, vì khi hiển thị sai hoặc thiếu chính xác, ta biết nguồn dữ liệu nào đang được dùng.

### 6.6. Đóng góp về LiveRouting ở Chương 5 là gì?

**Trả lời:**  
Đóng góp là cơ chế hỗ trợ định vị ngắn hạn khi GNSS yếu hoặc mất. Hệ thống không chỉ ngoại suy từ tốc độ GNSS cuối cùng, mà dùng Optical Flow để ước lượng chuyển động, kết hợp yaw rate từ IMU, prior vận tốc GNSS suy giảm theo thời gian, giới hạn tăng tốc/phanh và bám tuyến có điều kiện.

### 6.7. Em đánh giá kết quả LiveRouting trong Chương 5 như thế nào?

**Trả lời:**  
Kết quả có ý nghĩa ở mức thử nghiệm: marker có thể duy trì tốt hơn trong các khoảng mất GNSS ngắn so với giữ nguyên tốc độ cuối cùng. Tuy nhiên, hệ thống chưa ổn định trong mọi điều kiện, vì camera đơn không có tỷ lệ tuyệt đối nếu thiếu GNSS, và Optical Flow còn phụ thuộc ánh sáng, texture, rung camera và cách đặt điện thoại.

### 6.8. Cơ chế so sánh KLT và Farneback trong Chương 5 có gì đáng chú ý?

**Trả lời:**  
Điểm đáng chú ý là hai thuật toán chạy trên cùng khung hình đầu vào, cùng điều kiện, và độ nhạy bị khóa ở mức 100 khi phân tích. Kết quả được ghép trái-phải để quan sát trực tiếp, đồng thời lưu sample vào Room để xem lại biểu đồ FPS, thời gian xử lý và số điểm/vector hoạt động.

### 6.9. Vì sao Chương 5 tách chỉ số chính và chỉ số hỗ trợ trong analytics?

**Trả lời:**  
Vì FPS, thời gian xử lý và số điểm/vector hoạt động là các đại lượng đo trực tiếp. Độ tin cậy lại phụ thuộc cách định nghĩa inlier, ví dụ kiểm tra tiến-lùi, nên chỉ nên dùng để hỗ trợ diễn giải. Tách như vậy tránh việc người dùng hiểu nhầm độ tin cậy là độ chính xác tuyệt đối.

### 6.10. Pipeline RAFT bất đồng bộ giải quyết vấn đề gì?

**Trả lời:**  
RAFT xử lý video nặng, nếu gửi một request đồng bộ dài thì dễ timeout và người dùng không biết tiến độ. Pipeline bất đồng bộ tạo job, upload video trực tiếp hoặc theo chunk, thăm dò trạng thái, cho phép hủy job, tải kết quả và dọn tài nguyên. Nhờ đó ứng dụng không bị khóa ở một màn hình khi xử lý video dài.

### 6.11. Vì sao video kết quả được ghi H.264 bằng ffmpeg?

**Trả lời:**  
Theo Chương 5, video kết quả được ghi theo chuẩn H.264 để Android phát ổn định. Đây là lựa chọn thực dụng vì H.264 được hỗ trợ rộng rãi trên thiết bị di động, còn việc chỉ dựa vào `VideoWriter` của OpenCV có thể không ổn định với container đầu ra.

### 6.12. Bám đối tượng trên camera trực tiếp khác bám đối tượng trên video ngoại tuyến thế nào?

**Trả lời:**  
Camera trực tiếp chỉ có frame quá khứ và hiện tại nên đồ án dùng CSRT kết hợp template matching để bám thời gian thực. Video ngoại tuyến có toàn bộ chuỗi khung hình nên backend có thể dùng Cutie để lan truyền mask tiến và lùi theo thời gian. Vì vậy hai hướng xử lý khác nhau về dữ liệu sẵn có và thuật toán phù hợp.

### 6.13. Vì sao cần ánh xạ tọa độ vùng chọn giữa preview và frame xử lý?

**Trả lời:**  
Vì khung preview camera trên màn hình có thể bị co giãn hoặc cắt khác với frame OpenCV đang xử lý. Nếu không tính đúng tỷ lệ, kích thước và độ lệch, hộp bám sẽ không khớp với đối tượng mà người dùng đã chọn, dẫn đến phân tích sai vùng.

### 6.14. Hiệu ứng Liquid Glass trong Chương 5 có liên quan gì tới đóng góp kỹ thuật?

**Trả lời:**  
Đây là đóng góp ở tầng giao diện. Hệ thống dựng lớp kính theo nhiều mức: Android 13 trở lên dùng AGSL shader trên GPU, Android 12 dùng làm mờ phần cứng kết hợp mô phỏng, thiết bị cũ hơn dùng phương án CPU trên ảnh thu nhỏ. Mục tiêu là giữ giao diện thống nhất mà vẫn tương thích nhiều phiên bản Android.

### 6.15. Nếu hội đồng hỏi Chương 5 có điểm nào chưa hoàn thiện, em trả lời sao?

**Trả lời:**  
Em sẽ trả lời trung thực rằng phần LiveRouting vẫn ở mức thử nghiệm và chưa có benchmark định lượng với ground truth. Phần đánh giá hiện chủ yếu là kiểm thử chức năng, quan sát log và chạy trên thiết bị thật. Hướng phát triển là bổ sung bộ dữ liệu chuẩn, đánh giá sai số quỹ đạo và dùng các bộ lọc như EKF, particle filter hoặc factor graph.

---

## 7. Câu hỏi về thuật toán và xử lý ảnh

### 7.1. Vì sao em chọn KLT và Farneback cho xử lý thời gian thực?

**Trả lời:**  
Vì đây là hai thuật toán Optical Flow cổ điển có sẵn trong OpenCV, đủ nhẹ để chạy trên thiết bị Android và đại diện cho hai hướng khác nhau: KLT là sparse, nhanh và dựa trên điểm đặc trưng; Farneback là dense, cho cái nhìn tổng thể hơn nhưng tốn tài nguyên hơn.

### 7.2. Khi nào KLT hoạt động tốt?

**Trả lời:**  
KLT hoạt động tốt khi cảnh có nhiều điểm đặc trưng rõ, ánh sáng tương đối ổn định và chuyển động giữa hai frame không quá lớn. Vì nó theo dõi điểm thưa nên tốc độ tốt, phù hợp cho hiển thị vector và tính toán chỉ số thời gian thực.

### 7.3. Khi nào Farneback phù hợp hơn KLT?

**Trả lời:**  
Farneback phù hợp khi muốn quan sát phân bố chuyển động tổng thể trên ảnh, đặc biệt khi dùng heatmap. Nó không chỉ dựa vào một số điểm đặc trưng rời rạc như KLT. Đổi lại, chi phí xử lý cao hơn nên đồ án có giảm kích thước ảnh và lấy mẫu theo lưới khi hiển thị.

### 7.4. Vì sao không chạy RAFT trực tiếp trên điện thoại?

**Trả lời:**  
Trong phạm vi đồ án, RAFT nặng về tính toán và bộ nhớ, trong khi ứng dụng Android còn phải xử lý camera, GNSS, IMU và giao diện thời gian thực. Vì vậy đồ án chọn kiến trúc client-server: điện thoại chạy KLT/Farneback cho real-time, còn RAFT chạy trên backend cho video ngoại tuyến.

### 7.5. ROI trong Optical Flow có tác dụng gì?

**Trả lời:**  
ROI giúp giới hạn phạm vi phân tích vào vùng người dùng quan tâm, ví dụ một xe hoặc một người. Điều này giảm nhiễu từ nền và có thể giảm chi phí xử lý vì thuật toán không cần xử lý toàn khung hình.

### 7.6. Vì sao cần bám đối tượng chứ không chỉ dùng ROI tĩnh?

**Trả lời:**  
Vì trong camera trực tiếp, cả đối tượng và thiết bị đều có thể di chuyển. Nếu ROI chỉ là hình chữ nhật tĩnh, nó sẽ nhanh chóng lệch khỏi đối tượng. Bám đối tượng giúp vùng phân tích đi theo mục tiêu sau khi người dùng chọn một lần.

### 7.7. Template matching trong bám đối tượng có vai trò gì?

**Trả lời:**  
Theo Chương 5, hệ thống dùng CSRT kết hợp template matching. CSRT là tracker chính, còn template matching đóng vai trò kiểm tra và hỗ trợ khôi phục khi tracker mất dấu ngắn hạn. Cách kết hợp này giúp vùng bám ổn định hơn so với chỉ giữ ROI cố định.

### 7.8. Vì sao dùng CameraX thay vì Camera2 trực tiếp?

**Trả lời:**  
CameraX gắn với lifecycle Android và cung cấp use case `Preview` cùng `ImageAnalysis`, phù hợp với xử lý frame bằng OpenCV. Camera2 linh hoạt hơn nhưng phức tạp hơn nhiều về session, surface và lifecycle. Với mục tiêu đồ án, CameraX giúp tập trung vào thuật toán và tích hợp hệ thống.

---

## 8. Câu hỏi về dữ liệu, thực nghiệm và đánh giá

### 8.1. Chương 4 và Chương 5 đánh giá hệ thống theo cách nào?

**Trả lời:**  
Đồ án chủ yếu đánh giá bằng kiểm thử chức năng, kiểm thử tích hợp trên thiết bị thật và quan sát log. Các luồng được kiểm thử gồm quyền GPS/camera, GNSS viewer, LiveRouting, Optical Flow camera, analytics, xử lý video và lỗi server/model.

### 8.2. Thiết bị thử nghiệm trong đồ án là gì?

**Trả lời:**  
Theo Chương 4, ứng dụng được thử nghiệm trên điện thoại Samsung Galaxy S20 Ultra để truy cập GNSS, camera và IMU. Trên thiết bị này, các luồng quan sát vệ tinh, 3D/AR, Optical Flow camera, analytics và xử lý video RAFT hoạt động mượt và ổn định; riêng LiveRouting chưa ổn định trong mọi điều kiện.

### 8.3. Các chỉ số analytics Optical Flow gồm những gì?

**Trả lời:**  
Các chỉ số chính gồm FPS, thời gian xử lý và số điểm/vector hoạt động. Ngoài ra hệ thống còn ghi độ dịch chuyển trung bình/trung vị và độ tin cậy tương đối theo kiểm tra tiến-lùi. Trong biểu đồ chính, đồ án ưu tiên các chỉ số đo trực tiếp để tránh hiểu nhầm.

### 8.4. FPS được hiểu như thế nào trong đồ án?

**Trả lời:**  
FPS là tốc độ xử lý tức thời của thuật toán trên frame hiện tại, thường tính từ thời gian xử lý. Nó phản ánh khả năng đáp ứng thời gian thực, nhưng không tự nói lên độ chính xác của vector Optical Flow.

### 8.5. Độ tin cậy trong analytics có phải độ chính xác tuyệt đối không?

**Trả lời:**  
Không. Độ tin cậy trong đồ án là chỉ số tương đối dựa trên tỷ lệ điểm/vector nhất quán theo kiểm tra tiến-lùi. Vì đồ án chưa có ground truth chuyển động camera, chỉ số này hỗ trợ đánh giá nội bộ giữa KLT và Farneback trong cùng điều kiện, chứ không phải sai số tuyệt đối.

### 8.6. Hệ thống lưu dữ liệu thực nghiệm ở đâu?

**Trả lời:**  
Ứng dụng dùng Room để lưu dữ liệu cục bộ. Các bảng được nêu trong Chương 4 gồm `AnalyticsSession`, `AnalyticsSample`, `RoutingSession`, `RoutePoint` và `MediaItem`. Nhờ vậy người dùng có thể xem lại phiên đo, tuyến đường và media kết quả.

### 8.7. Kết quả xử lý video được đánh giá như thế nào?

**Trả lời:**  
Trong đồ án, kết quả video được đánh giá ở mức chức năng: upload được, theo dõi tiến độ được, job hoàn tất hoặc lỗi rõ ràng, tải kết quả về và mở được video. Hệ thống cũng kiểm tra các trường hợp video nhỏ/lớn, upload theo chunk, hủy job và lỗi model/server.

### 8.8. Đồ án đã có benchmark định lượng sâu chưa?

**Trả lời:**  
Chưa. Chương 6 nêu rõ hạn chế là đánh giá mới tập trung vào chức năng và quan sát định tính, chưa có benchmark trên nhiều thiết bị, nhiều độ phân giải, nhiều điều kiện ánh sáng hoặc tập dữ liệu chuẩn.

### 8.9. Vì sao kiểm thử hệ thống chủ yếu là chức năng và tích hợp?

**Trả lời:**  
Vì hệ thống phụ thuộc nhiều vào phần cứng, cảm biến và server: GNSS, camera, IMU, ARCore, backend RAFT. Nhiều luồng khó kiểm chứng bằng unit test thuần, nên đồ án chọn kiểm thử trên thiết bị thật kết hợp quan sát log để xác nhận hành vi hệ thống.

---

## 9. Câu hỏi về sai số, giới hạn và rủi ro

### 9.1. Hạn chế lớn nhất của LiveRouting là gì?

**Trả lời:**  
Hạn chế lớn nhất là LiveRouting mới ở mức thử nghiệm. Cơ chế dead reckoning dựa trên Optical Flow, IMU và con quay có thể duy trì vị trí ngắn hạn, nhưng chưa ổn định trong mọi điều kiện và chưa có ground truth để đánh giá sai số quỹ đạo định lượng.

### 9.2. Vì sao camera đơn khó suy ra quãng đường thật?

**Trả lời:**  
Camera đơn nhìn thấy chuyển động theo pixel, nhưng thiếu thông tin độ sâu và tỷ lệ tuyệt đối. Cùng một độ dịch chuyển ảnh có thể tương ứng với quãng đường thật khác nhau tùy độ cao camera, góc đặt, vật thể trong cảnh và khoảng cách tới bề mặt. Vì vậy đồ án cần hiệu chỉnh bằng GNSS khi tín hiệu còn tốt.

### 9.3. GNSS trong đô thị dày đặc có rủi ro gì?

**Trả lời:**  
GNSS có thể bị che khuất, phản xạ đa đường, mất cập nhật hoặc giảm độ chính xác. Khi đó vị trí Android trả về có thể trễ hoặc lệch. Đồ án xử lý bằng cách đánh giá độ tin cậy GNSS và hỗ trợ ngắn hạn bằng camera/IMU, nhưng không khẳng định loại bỏ hoàn toàn sai số GNSS.

### 9.4. IMU có hạn chế gì?

**Trả lời:**  
IMU có tần số cao và không phụ thuộc tín hiệu ngoài, nhưng dễ trôi khi tích phân theo thời gian. Con quay có thể trôi góc, gia tốc kế nhiễu và bị ảnh hưởng bởi rung. Vì vậy trong đồ án IMU chỉ hỗ trợ ngắn hạn và cần ràng buộc bởi GNSS, Optical Flow hoặc tuyến đường.

### 9.5. Bám tuyến SNAP có thể gây hiểu nhầm không?

**Trả lời:**  
Có, nếu trình bày như vị trí thật tuyệt đối. Vì SNAP kéo marker về tuyến nên có thể làm quỹ đạo trông ổn định hơn thực tế. Đồ án phân biệt SNAP và REAL để người dùng có thể xem vị trí dead reckoning thật, đồng thời chỉ bám tuyến có điều kiện thay vì ép liên tục.

### 9.6. Backend RAFT có rủi ro gì khi triển khai thực tế?

**Trả lời:**  
Backend phụ thuộc tốc độ mạng, tài nguyên CPU/GPU, dung lượng video và môi trường triển khai. Nếu không có GPU, xử lý có thể lâu. Khi triển khai thực tế cần bổ sung xác thực API, giới hạn dung lượng upload, hàng đợi bền vững, timeout và giám sát tài nguyên.

### 9.7. Bám đối tượng có thể thất bại trong trường hợp nào?

**Trả lời:**  
Bám đối tượng có thể mất dấu khi vật thể bị che hoàn toàn, ra khỏi khung hình, biến dạng mạnh, thiếu texture, ánh sáng thay đổi hoặc camera rung nhiều. Trong các trường hợp đó, người dùng cần chọn lại vùng đối tượng.

### 9.8. Màn hình AR có đạt độ chính xác không gian như hệ AR thương mại không?

**Trả lời:**  
Không. Chương 6 nêu AR trong đồ án tập trung minh họa tương đối vị trí vệ tinh theo hướng nhìn, chưa đạt mức hiệu chỉnh không gian chính xác như các hệ AR thương mại chuyên sâu. Đây là hạn chế và có thể cải thiện bằng hiệu chỉnh hướng nhìn, bộ lọc orientation và cảnh báo độ tin cậy.

---

## 10. Câu hỏi phản biện khó

### 10.1. Điểm mới của đồ án là gì, khi KLT, Farneback, RAFT đều đã có sẵn?

**Trả lời:**  
Điểm mới của đồ án không nằm ở việc phát minh thuật toán Optical Flow mới, mà ở việc tích hợp và điều phối chúng trong một hệ thống Android hoàn chỉnh: GNSS đa nguồn, camera real-time, analytics lưu phiên, backend RAFT bất đồng bộ, bám đối tượng và thử nghiệm LiveRouting khi GNSS yếu. Đóng góp chính là thiết kế hệ thống và cơ chế kết hợp thực dụng.

### 10.2. Nếu chưa có ground truth, làm sao chứng minh hệ thống định vị tốt hơn?

**Trả lời:**  
Trong phạm vi đồ án, em chưa khẳng định hệ thống chính xác hơn theo nghĩa định lượng tuyệt đối. Em chứng minh ở mức chức năng và quan sát: hệ thống duy trì marker trong khoảng mất GNSS ngắn và phản ứng theo chuyển động camera/IMU thay vì giữ tốc độ cố định. Đây cũng là hạn chế đã nêu; hướng tiếp theo là bổ sung ground truth và benchmark sai số quỹ đạo.

### 10.3. Vì sao không dùng trực tiếp visual-inertial odometry hoặc ARCore để định vị?

**Trả lời:**  
Visual-inertial odometry đầy đủ yêu cầu hiệu chuẩn, ước lượng pose, xử lý outlier, tỷ lệ và nhiều điều kiện ổn định hơn. Phạm vi đồ án tập trung vào nguyên mẫu thử nghiệm dựa trên GNSS, Optical Flow và IMU, không đặt mục tiêu xây dựng VIO hoàn chỉnh. ARCore trong đồ án chủ yếu dùng cho quan sát vệ tinh bằng AR, chưa dùng làm lõi định vị.

### 10.4. Nếu Optical Flow bị ảnh hưởng bởi vật thể chuyển động ngoài đường, hệ thống có sai không?

**Trả lời:**  
Có thể sai. Vật thể chuyển động độc lập như xe khác hoặc người đi bộ có thể tạo vector không phản ánh chuyển động của camera. Đồ án giảm rủi ro bằng ROI, bám đối tượng và chỉ số chất lượng, nhưng chưa giải quyết triệt để. Hướng phát triển là thêm phân đoạn cảnh, loại outlier và sensor fusion chặt chẽ hơn.

### 10.5. Vì sao không dùng map matching liên tục để sửa vị trí?

**Trả lời:**  
Vì map matching liên tục có thể che giấu sai số thật và làm marker bám đường ngay cả khi người dùng đã lệch tuyến. Đồ án chọn SNAP có điều kiện và REAL để cân bằng: có thể giảm trôi hiển thị khi tin cậy, nhưng vẫn giữ khả năng phát hiện lệch tuyến và yêu cầu tính lại đường.

### 10.6. Độ nhạy bị khóa 100 trong analytics có làm kết quả thiếu thực tế không?

**Trả lời:**  
Khóa độ nhạy 100 là để so sánh công bằng giữa hai thuật toán trên cùng đầu vào, tránh việc thao tác người dùng làm lệch kết quả. Khi sử dụng thông thường, người dùng vẫn có thể điều chỉnh độ nhạy. Vì vậy analytics ưu tiên tính lặp lại, còn trải nghiệm thực tế được kiểm tra ở chế độ camera bình thường.

### 10.7. Nếu server không có mạng hoặc Cloudflare Tunnel lỗi thì chức năng video có dùng được không?

**Trả lời:**  
Chức năng xử lý video bằng RAFT cần backend và kết nối mạng. Nếu server hoặc tunnel lỗi, ứng dụng phải báo lỗi và không tạo kết quả sai. Các chức năng chạy trực tiếp trên thiết bị như camera KLT/Farneback, GNSS viewer và analytics vẫn có thể hoạt động tùy điều kiện quyền và cảm biến.

### 10.8. Đồ án có đảm bảo bảo mật khi upload video không?

**Trả lời:**  
Trong phạm vi thử nghiệm, ứng dụng giao tiếp qua HTTPS và backend có dọn tài nguyên tạm. Tuy nhiên Chương 4 cũng nêu khi triển khai thực tế cần bổ sung xác thực API, giới hạn dung lượng, hàng đợi bền vững và giám sát tài nguyên. Vì vậy em không khẳng định mức bảo mật thương mại, mà mới ở mức phù hợp demo và thử nghiệm.

### 10.9. Vì sao Liquid Glass được đưa vào Chương 5, có làm loãng trọng tâm không?

**Trả lời:**  
Liquid Glass không phải trọng tâm định vị, nhưng là một đóng góp kỹ thuật về giao diện thời gian thực trên Android. Nó giải quyết bài toán dựng lớp kính trên nền bản đồ thay đổi liên tục và tương thích nhiều phiên bản Android. Khi bảo vệ, em sẽ trình bày ngắn phần này và tập trung nhiều hơn vào GNSS, LiveRouting, analytics và RAFT.

---

## 11. Câu hỏi về hướng phát triển

### 11.1. Hướng phát triển quan trọng nhất của đồ án là gì?

**Trả lời:**  
Hướng quan trọng nhất là xây dựng đánh giá định lượng có kiểm soát. Cần ghi tập dữ liệu chuẩn, có ground truth hoặc quỹ đạo tham chiếu, rồi đo sai số vị trí, sai số hướng, FPS, thời gian xử lý và độ ổn định trong nhiều điều kiện ánh sáng, thiết bị và tốc độ di chuyển.

### 11.2. Làm sao cải thiện LiveRouting?

**Trả lời:**  
Có thể bổ sung sensor fusion định lượng hơn như Extended Kalman Filter, particle filter hoặc factor graph để mô hình hóa vị trí, vận tốc, hướng và độ bất định. Đồng thời cần thêm ground truth để hiệu chỉnh hệ số Optical Flow, đánh giá sai số quỹ đạo và tối ưu cơ chế SNAP/REAL.

### 11.3. Làm sao cải thiện phần Optical Flow?

**Trả lời:**  
Có thể bổ sung lọc outlier tốt hơn, phân đoạn vùng đường/nền, tách vật thể chuyển động độc lập, benchmark theo độ phân giải và điều kiện ánh sáng. Với RAFT, có thể chuẩn hóa backend, thêm health check, timeout, theo dõi GPU/CPU và tự dọn job cũ.

### 11.4. Làm sao cải thiện phần GNSS và AR?

**Trả lời:**  
Phần GNSS có thể bổ sung timeline quỹ đạo, phát lại dữ liệu đã ghi và phân loại vệ tinh theo chòm rõ hơn. Phần AR có thể thêm hiệu chỉnh hướng nhìn, bộ lọc làm mượt orientation và cảnh báo độ tin cậy dựa trên C/N0 hoặc số vệ tinh used-in-fix.

### 11.5. Nếu triển khai thực tế, hệ thống cần bổ sung gì?

**Trả lời:**  
Cần bổ sung xác thực API, giới hạn dung lượng upload, hàng đợi xử lý bền vững, giám sát tài nguyên server, chính sách timeout, chuẩn hóa mã lỗi backend và kiểm thử trên nhiều thiết bị. Với định vị, cần benchmark định lượng và cơ chế fusion chặt chẽ hơn trước khi dùng trong môi trường thực tế.

---

## 12. Bộ câu hỏi trả lời nhanh trước khi bảo vệ

### 12.1. Đồ án dùng kiến trúc gì?

**Trả lời nhanh:**  
Client-server. Android là client, Python/FastAPI là backend xử lý video RAFT.

### 12.2. Android app dùng mô hình tổ chức nào?

**Trả lời nhanh:**  
MVVM kết hợp phân tầng: Fragment/View, ViewModel, repository/business, data Room và native OpenCV.

### 12.3. Các thư viện Android chính là gì?

**Trả lời nhanh:**  
Kotlin, CameraX, OpenCV 4.12.0, osmdroid, ARCore, Orekit, WorkManager, Retrofit, OkHttp, Media3 và Room.

### 12.4. Backend dùng gì?

**Trả lời nhanh:**  
Python, FastAPI, Uvicorn, ONNX Runtime, OpenCV, ffmpeg; có thể dùng Cutie/PyTorch cho ROI video.

### 12.5. Thiết bị thử nghiệm là gì?

**Trả lời nhanh:**  
Samsung Galaxy S20 Ultra.

### 12.6. Bốn nguồn phân giải vị trí vệ tinh theo ưu tiên?

**Trả lời nhanh:**  
PVT thật, IGS Broadcast Ephemeris, CelesTrak TLE/SGP4, rồi xấp xỉ từ azimuth/elevation.

### 12.7. PVT hết hạn sau bao lâu theo Chương 5?

**Trả lời nhanh:**  
PVT chỉ dùng khi chưa quá hạn 10 giây.

### 12.8. Broadcast ephemeris hợp lệ trong ngưỡng nào?

**Trả lời nhanh:**  
Thời điểm bản tin gần thời điểm quan sát trong ngưỡng 12 giờ.

### 12.9. KLT là sparse hay dense?

**Trả lời nhanh:**  
Sparse, theo dõi các điểm đặc trưng.

### 12.10. Farneback là sparse hay dense?

**Trả lời nhanh:**  
Dense, ước lượng trường chuyển động dày đặc hơn trên ảnh.

### 12.11. RAFT chạy ở đâu?

**Trả lời nhanh:**  
Trên backend server, dùng ONNX Runtime để xử lý video ngoại tuyến.

### 12.12. Vì sao không chạy RAFT trực tiếp trên Android?

**Trả lời nhanh:**  
Vì RAFT nặng về tính toán và bộ nhớ; Android cần phản hồi thời gian thực nên chỉ chạy KLT/Farneback.

### 12.13. LiveRouting đã hoàn thiện chưa?

**Trả lời nhanh:**  
Chưa hoàn toàn. Đây là chức năng thử nghiệm, cơ bản đạt nhưng chưa ổn định trong mọi điều kiện.

### 12.14. Đồ án đã có ground truth định vị chưa?

**Trả lời nhanh:**  
Chưa. Đây là hạn chế lớn; hiện đánh giá chủ yếu là chức năng và quan sát định tính.

### 12.15. Chỉ số nào được vẽ chính trong analytics?

**Trả lời nhanh:**  
FPS, thời gian xử lý và số điểm/vector hoạt động.

### 12.16. Độ tin cậy analytics có phải độ chính xác tuyệt đối không?

**Trả lời nhanh:**  
Không. Đó là độ tin cậy tương đối theo kiểm tra tiến-lùi.

### 12.17. SNAP khác REAL thế nào?

**Trả lời nhanh:**  
SNAP kéo vị trí về tuyến có điều kiện; REAL hiển thị vị trí dead reckoning thật.

### 12.18. Bám đối tượng camera dùng gì?

**Trả lời nhanh:**  
CSRT của OpenCV kết hợp template matching.

### 12.19. Bám đối tượng video ngoại tuyến dùng gì?

**Trả lời nhanh:**  
Backend có thể dùng Cutie để lan truyền mask qua video.

### 12.20. Hạn chế quan trọng nhất cần nói trung thực?

**Trả lời nhanh:**  
LiveRouting chưa ổn định định lượng, chưa có benchmark lớn và chưa có ground truth sai số quỹ đạo.

### 12.21. Nếu bị hỏi "đóng góp thuật toán mới đâu?", trả lời thế nào?

**Trả lời nhanh:**  
Đồ án không tuyên bố phát minh thuật toán mới; đóng góp là tích hợp hệ thống, cơ chế điều phối dữ liệu và thực nghiệm trên Android/backend.

### 12.22. Hướng phát triển chính?

**Trả lời nhanh:**  
Bổ sung benchmark định lượng, ground truth, sensor fusion bằng EKF/particle filter/factor graph và chuẩn hóa backend.

---

## Ghi chú ôn bảo vệ

- Khi nói về Chương 5, ưu tiên trình bày theo cấu trúc: bài toán, giải pháp, kết quả, hạn chế.
- Không khẳng định hệ thống đạt độ chính xác định vị công nghiệp.
- Với các phần chưa có số liệu định lượng, trả lời: "Trong phạm vi đồ án, em mới đánh giá ở mức chức năng và quan sát định tính; đây là hướng cần mở rộng".
- Với LiveRouting, nhấn mạnh đây là hỗ trợ ngắn hạn khi GNSS suy giảm, không phải thay thế GNSS hay VIO đầy đủ.
- Với RAFT, nhấn mạnh lý do đưa lên backend là chi phí tính toán cao và nhu cầu xử lý video ngoại tuyến.
