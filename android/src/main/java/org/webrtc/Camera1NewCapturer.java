package org.webrtc;

import android.content.Context;

/**
 * Created by hendriksaragih on 6/26/20.
 */
public class Camera1NewCapturer extends CameraCapturer {
    private final boolean captureToTexture;

    public Camera1NewCapturer(
        String cameraName, CameraEventsHandler eventsHandler, boolean captureToTexture) {
        super(cameraName, eventsHandler, new Camera1Enumerator(captureToTexture));

        this.captureToTexture = captureToTexture;
    }

    @Override
    protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
                                       CameraSession.Events events, Context applicationContext,
                                       SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height,
                                       int framerate) {
        Camera1NewSession.create(createSessionCallback, events, captureToTexture, applicationContext,
            surfaceTextureHelper, Camera1Enumerator.getCameraIndex(cameraName), width, height,
            framerate);
    }
}
