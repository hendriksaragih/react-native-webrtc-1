package org.webrtc;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by hendriksaragih on 6/26/20.
 */
class Camera1NewSession implements CameraSession {
    private static final String TAG = "Camera1NewSession";
    private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;

    private static final Histogram camera1StartTimeMsHistogram =
        Histogram.createCounts("WebRTC.Android.Camera1.StartTimeMs", 1, 10000, 50);
    private static final Histogram camera1StopTimeMsHistogram =
        Histogram.createCounts("WebRTC.Android.Camera1.StopTimeMs", 1, 10000, 50);
    private static final Histogram camera1ResolutionHistogram = Histogram.createEnumeration(
        "WebRTC.Android.Camera1.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());

    private static enum SessionState { RUNNING, STOPPED }

    private final Handler                                cameraThreadHandler;
    private final Events                                 events;
    private final boolean                                captureToTexture;
    private final Context                                applicationContext;
    private final SurfaceTextureHelper                   surfaceTextureHelper;
    private final int cameraId;
    private final android.hardware.Camera camera;
    private final android.hardware.Camera.CameraInfo     info;
    private final CameraEnumerationAndroid.CaptureFormat captureFormat;
    // Used only for stats. Only used on the camera thread.
    private final long                                   constructionTimeNs; // Construction time of this class.

    private SessionState state;
    private boolean firstFrameReported;

    // TODO(titovartem) make correct fix during webrtc:9175
    @SuppressWarnings("ByteBufferBackingArray")
    public static void create(final CreateSessionCallback callback, final Events events,
                              final boolean captureToTexture, final Context applicationContext,
                              final SurfaceTextureHelper surfaceTextureHelper, final int cameraId, final int width,
                              final int height, final int framerate) {
        final long constructionTimeNs = System.nanoTime();
        Logging.d(TAG, "Open camera " + cameraId);
        events.onCameraOpening();

        final android.hardware.Camera camera;
        try {
            camera = android.hardware.Camera.open(cameraId);
        } catch (RuntimeException e) {
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }

        if (camera == null) {
            callback.onFailure(FailureType.ERROR,
                "android.hardware.Camera.open returned null for camera id = " + cameraId);
            return;
        }

        try {
            camera.setPreviewTexture(surfaceTextureHelper.getSurfaceTexture());
        } catch (IOException | RuntimeException e) {
            camera.release();
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }

        final android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);

        final CameraEnumerationAndroid.CaptureFormat captureFormat;
        try {
            final android.hardware.Camera.Parameters parameters = camera.getParameters();
            captureFormat = findClosestCaptureFormat(parameters, width, height, framerate);
            final Size pictureSize = findClosestPictureSize(parameters, width, height);
            updateCameraParameters(camera, parameters, captureFormat, pictureSize, captureToTexture);
        } catch (RuntimeException e) {
            camera.release();
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }

        if (!captureToTexture) {
            final int frameSize = captureFormat.frameSize();
            for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
                final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
                camera.addCallbackBuffer(buffer.array());
            }
        }

        // Calculate orientation manually and send it as CVO insted.
        camera.setDisplayOrientation(0 /* degrees */);

        callback.onDone(new Camera1NewSession(events, captureToTexture, applicationContext,
            surfaceTextureHelper, cameraId, camera, info, captureFormat, constructionTimeNs));
    }

    private static void updateCameraParameters(android.hardware.Camera camera,
                                               android.hardware.Camera.Parameters parameters, CameraEnumerationAndroid.CaptureFormat captureFormat, Size pictureSize,
                                               boolean captureToTexture) {
        final List<String> focusModes = parameters.getSupportedFocusModes();

        parameters.setPreviewFpsRange(captureFormat.framerate.min, captureFormat.framerate.max);
        parameters.setPreviewSize(captureFormat.width, captureFormat.height);
        parameters.setPictureSize(pictureSize.width, pictureSize.height);
        if (!captureToTexture) {
            parameters.setPreviewFormat(captureFormat.imageFormat);
        }

        if (parameters.isVideoStabilizationSupported()) {
            parameters.setVideoStabilization(true);
        }
        if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        camera.setParameters(parameters);
    }

    private static CameraEnumerationAndroid.CaptureFormat findClosestCaptureFormat(
        android.hardware.Camera.Parameters parameters, int width, int height, int framerate) {
        // Find closest supported format for |width| x |height| @ |framerate|.
        final List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> supportedFramerates =
            Camera1NewEnumerator.convertFramerates(parameters.getSupportedPreviewFpsRange());
        Logging.d(TAG, "Available fps ranges: " + supportedFramerates);

        int min = 99999;
        for (CameraEnumerationAndroid.CaptureFormat.FramerateRange x : supportedFramerates){
            if (min > x.min){
                min = x.min;
            }
        }

        final CameraEnumerationAndroid.CaptureFormat.FramerateRange fpsRange =
            CameraEnumerationAndroid.getClosestSupportedFramerateRange(supportedFramerates, framerate);

        final Size previewSize = CameraEnumerationAndroid.getClosestSupportedSize(
            Camera1NewEnumerator.convertSizes(parameters.getSupportedPreviewSizes()), width, height);
        CameraEnumerationAndroid.reportCameraResolution(camera1ResolutionHistogram, previewSize);
        int fpsMin = 5000;
        int fpsMax = 12000;
//        fpsRange.max = 12000;
//        fpsRange.min = 7000;
        fpsRange.min = Math.max(min, fpsMin);
        fpsRange.max = Math.max(fpsMax, min);
        Log.e("tjal2", "min ==>"+fpsRange.min+", max ==> "+fpsRange.max+", available min ==> "+min);
        return new CameraEnumerationAndroid.CaptureFormat(previewSize.width, previewSize.height, fpsRange);
    }

    private static Size findClosestPictureSize(
        android.hardware.Camera.Parameters parameters, int width, int height) {
        return CameraEnumerationAndroid.getClosestSupportedSize(
            Camera1NewEnumerator.convertSizes(parameters.getSupportedPictureSizes()), width, height);
    }

    private Camera1NewSession(Events events, boolean captureToTexture, Context applicationContext,
                           SurfaceTextureHelper surfaceTextureHelper, int cameraId, android.hardware.Camera camera,
                           android.hardware.Camera.CameraInfo info, CameraEnumerationAndroid.CaptureFormat captureFormat,
                           long constructionTimeNs) {
        Logging.d(TAG, "Create new camera1 session on camera " + cameraId);

        this.cameraThreadHandler = new Handler();
        this.events = events;
        this.captureToTexture = captureToTexture;
        this.applicationContext = applicationContext;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.cameraId = cameraId;
        this.camera = camera;
        this.info = info;
        this.captureFormat = captureFormat;
        this.constructionTimeNs = constructionTimeNs;

        surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);

        startCapturing();
    }

    @Override
    public void stop() {
        Logging.d(TAG, "Stop camera1 session on camera " + cameraId);
        checkIsOnCameraThread();
        if (state != SessionState.STOPPED) {
            final long stopStartTime = System.nanoTime();
            stopInternal();
            final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
            camera1StopTimeMsHistogram.addSample(stopTimeMs);
        }
    }

    private void startCapturing() {
        Logging.d(TAG, "Start capturing");
        checkIsOnCameraThread();

        state = SessionState.RUNNING;

        camera.setErrorCallback(new android.hardware.Camera.ErrorCallback() {
            @Override
            public void onError(int error, android.hardware.Camera camera) {
                String errorMessage;
                if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
                    errorMessage = "Camera server died!";
                } else {
                    errorMessage = "Camera error: " + error;
                }
                Logging.e(TAG, errorMessage);
                stopInternal();
                if (error == android.hardware.Camera.CAMERA_ERROR_EVICTED) {
                    events.onCameraDisconnected(Camera1NewSession.this);
                } else {
                    events.onCameraError(Camera1NewSession.this, errorMessage);
                }
            }
        });

        if (captureToTexture) {
            listenForTextureFrames();
        } else {
            listenForBytebufferFrames();
        }
        try {
            camera.startPreview();
        } catch (RuntimeException e) {
            stopInternal();
            events.onCameraError(this, e.getMessage());
        }
    }

    private void stopInternal() {
        Logging.d(TAG, "Stop internal");
        checkIsOnCameraThread();
        if (state == SessionState.STOPPED) {
            Logging.d(TAG, "Camera is already stopped");
            return;
        }

        state = SessionState.STOPPED;
        surfaceTextureHelper.stopListening();
        // Note: stopPreview or other driver code might deadlock. Deadlock in
        // android.hardware.Camera._stopPreview(Native Method) has been observed on
        // Nexus 5 (hammerhead), OS version LMY48I.
        camera.stopPreview();
        camera.release();
        events.onCameraClosed(this);
        Logging.d(TAG, "Stop done");
    }

    private void listenForTextureFrames() {
        surfaceTextureHelper.startListening((VideoFrame frame) -> {
            checkIsOnCameraThread();

            if (state != SessionState.RUNNING) {
                Logging.d(TAG, "Texture frame captured but camera is no longer running.");
                return;
            }

            if (!firstFrameReported) {
                final int startTimeMs =
                    (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                camera1StartTimeMsHistogram.addSample(startTimeMs);
                firstFrameReported = true;
            }

            // Undo the mirror that the OS "helps" us with.
            // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
            final VideoFrame modifiedFrame = new VideoFrame(
                CameraSession.createTextureBufferWithModifiedTransformMatrix(
                    (TextureBufferImpl) frame.getBuffer(),
                    /* mirror= */ info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT,
                    /* rotation= */ 0),
                /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
            events.onFrameCaptured(Camera1NewSession.this, modifiedFrame);
            modifiedFrame.release();
        });
    }

    private void listenForBytebufferFrames() {
        camera.setPreviewCallbackWithBuffer(new android.hardware.Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(final byte[] data, android.hardware.Camera callbackCamera) {
                checkIsOnCameraThread();

                if (callbackCamera != camera) {
                    Logging.e(TAG, "Callback from a different camera. This should never happen.");
                    return;
                }

                if (state != SessionState.RUNNING) {
                    Logging.d(TAG, "Bytebuffer frame captured but camera is no longer running.");
                    return;
                }

                final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

                if (!firstFrameReported) {
                    final int startTimeMs =
                        (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                    camera1StartTimeMsHistogram.addSample(startTimeMs);
                    firstFrameReported = true;
                }

                VideoFrame.Buffer frameBuffer = new NV21Buffer(
                    data, captureFormat.width, captureFormat.height, () -> cameraThreadHandler.post(() -> {
                    if (state == SessionState.RUNNING) {
                        camera.addCallbackBuffer(data);
                    }
                }));
                final VideoFrame frame = new VideoFrame(frameBuffer, getFrameOrientation(), captureTimeNs);
                events.onFrameCaptured(Camera1NewSession.this, frame);
                frame.release();
            }
        });
    }

    private int getFrameOrientation() {
        int rotation = CameraSession.getDeviceOrientation(applicationContext);
        if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
            rotation = 360 - rotation;
        }
        return (info.orientation + rotation) % 360;
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }
}