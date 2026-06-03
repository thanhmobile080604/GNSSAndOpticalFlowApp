# Task: Upload Video To Backend via SeaweedFS

## Goal
Implement a simple video upload test flow in `BlankFragment`.

When user clicks the Send button:
1. Open device video picker.
2. User selects a video from local device.
3. App requests a presigned upload URL from Backend.
4. App uploads the selected video file directly to SeaweedFS using HTTP PUT.
5. App calls Backend confirm-upload API after upload success.
6. Show upload status/result in `replyFromServer` TextView.

## Context
Read the backend PDF/document first.

The backend document describes the SeaweedFS / S3 presigned URL upload flow.

The app should NOT send video bytes or Base64 video through MQTT.

Correct upload flow:
1. Request upload URL:
   POST /api/media-logs/request-upload-url

2. Upload raw binary file:
   PUT <uploadUrl>

3. Confirm uploaded file:
   POST /api/media-logs/confirm-upload

## Files to modify
- BlankFragment.kt
- fragment_blank.xml
- build.gradle if dependencies are missing
- AndroidManifest.xml if permissions are missing
- Any API/network helper file only if the project already has one

## Requirements

### UI
- Reuse current Send button.
- When clicking Send, open Android video picker.
- After video is selected, show upload progress/status text in `replyFromServer`.
- Show clear status:
    - Selecting video
    - Requesting upload URL
    - Uploading video
    - Confirming upload
    - Upload success
    - Upload failed with reason

### Video Picker
- Use Android Activity Result API.
- Only allow video selection.
- Get video Uri safely.
- Resolve file extension from Uri or MIME type.
- Support at least mp4.
- Do not require broad storage permission if system picker can handle it.

### Request Upload URL
Call:

POST /api/media-logs/request-upload-url

Payload:
{
"deviceId": "<deviceId>",
"fileExtension": "mp4",
"filename": "<generated-video-name>"
}

Expected response:
{
"uploadUrl": "...",
"s3Key": "...",
"mimeType": "video/mp4",
"expiresIn": 3600
}

### Upload To SeaweedFS
Use HTTP PUT to upload selected video file to `uploadUrl`.

Rules:
- Upload raw binary from InputStream.
- Do not convert video to Base64.
- Set Content-Type exactly from backend response `mimeType`.
- Treat HTTP 200, 201, or 204 as upload success.
- Handle large file safely, avoid loading full video into memory if possible.

### Confirm Upload
After upload success, call:

POST /api/media-logs/confirm-upload

Payload:
{
"deviceId": "<deviceId>",
"s3Key": "<s3Key from request-upload-url response>",
"mediaType": "video",
"snapshotId": "<optional-generated-id>"
}

## Constraints
- Do not commit real backend token, API URL, MQTT password, or secret.
- Keep code simple for testing.
- Do not refactor unrelated code.
- Do not change BaseFragment.
- Do not send video through MQTT.
- Do not use Base64 for video upload.
- Do not load full video file into RAM.
- If the project already uses Retrofit/OkHttp, follow the existing style.
- If no network layer exists, implement the simplest safe OkHttp-based test code inside or near this screen.

## Acceptance Criteria
- Clicking Send opens video picker.
- Selecting video starts upload flow.
- App successfully requests `uploadUrl`.
- App uploads video to SeaweedFS using PUT raw binary.
- App calls confirm-upload after PUT success.
- `replyFromServer` displays clear result.
- Errors are visible on UI and Logcat.
- App does not crash when user cancels picker.
- App does not upload via MQTT/Base64.

## Before Coding
1. Read the backend PDF/document.
2. Summarize the required upload flow.
3. List exact files you will modify.
4. Confirm whether Retrofit/OkHttp already exists in the project.
5. Propose implementation plan.
6. Wait for confirmation before editing code.