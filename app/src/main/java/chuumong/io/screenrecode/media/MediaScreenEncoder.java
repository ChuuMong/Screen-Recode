package chuumong.io.screenrecode.media;

import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;

import chuumong.io.glutils.EglTask;
import chuumong.io.glutils.FullFrameRect;
import chuumong.io.glutils.Texture2dProgram;
import chuumong.io.glutils.WindowSurface;

/**
 * Created by LeeJongHun on 2016-05-04.
 */
public final class MediaScreenEncoder extends MediaEncoder {

    private static final String TAG = MediaScreenEncoder.class.getSimpleName();

    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25;
    private static final float BPP = 0.25f;

    private MediaProjection mediaProjection;
    private final int width;
    private final int height;
    private final int density;

    private boolean requestDraw;

    private final Handler handler;

    private Surface surface;

    public MediaScreenEncoder(@NonNull MediaMuxerWrapper muxer, @NonNull MediaEncoderListener listener, MediaProjection projection,
                              int width, int height, int density) {
        super(muxer, listener);

        this.mediaProjection = projection;
        this.width = width;
        this.height = height;
        this.density = density;

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();

        handler = new Handler(thread.getLooper());
    }

    @Override
    protected void prepare() throws IOException {
        Log.d(TAG, "prepare");

        surface = prepareSurfaceEncoder();
        mediaCodec.start();
        isCapturing = true;

        new Thread(new DrawTask(null, 0), "ScreenCaptureThread").start();

        Log.d(TAG, "prepare finishing");

        listener.onPrepared(this);
    }

    @Override
    protected void release() {
        handler.getLooper().quit();
        super.release();
    }

    @Override
    protected void stopRecording() {
        Log.d(TAG, "stopRecording");

        synchronized (sync) {
            isCapturing = false;
            sync.notifyAll();
        }

        super.stopRecording();
    }

    @Override
    protected void signalEndOfInputStream() {
        Log.d(TAG, "signalEndOfInputStream");

        mediaCodec.signalEndOfInputStream();
        isEOS = true;
    }

    private Surface prepareSurfaceEncoder() throws IOException {
        trackIndex = -1;
        muxerStarted = isEOS = false;

        final MediaCodecInfo codecInfo = selectScreenCodec();
        if (codecInfo == null) {
            throw new RuntimeException("Not Select Screen Codec");
        }

        Log.d(TAG, "prepareSurfaceEncoder Select Codec : " + codecInfo.getName());

        final MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);    // API >= 18
        format.setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);

        Log.d(TAG, "prepareSurfaceEncoder format : " + format);

        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        return mediaCodec.createInputSurface();
    }

    private int calcBitRate() {
        final int bitrate = (int) (BPP * FRAME_RATE * width * height);
        Log.d(TAG, String.format("bitrate : %5.2fMbps", bitrate / 1024f / 1024f));
        return bitrate;
    }

    @SuppressWarnings("deprecation")
    private static MediaCodecInfo selectScreenCodec() {
        Log.d(TAG, "selectScreenCodec");

        final int numCodecs = MediaCodecList.getCodecCount();

        for (int i = 0; i < numCodecs; i++) {
            final MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            final String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(MIME_TYPE)) {
                    final int format = selectColorFormat(codecInfo);

                    if (format > 0) {
                        return codecInfo;
                    }
                }
            }
        }

        return null;
    }

    private static int selectColorFormat(MediaCodecInfo codecInfo) {
        Log.d(TAG, "selectColorFormat");

        int result = 0;

        final MediaCodecInfo.CodecCapabilities caps;

        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            caps = codecInfo.getCapabilitiesForType(MIME_TYPE);
        }
        finally {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }

        int colorFormat;

        for (int i = 0; i < caps.colorFormats.length; i++) {
            colorFormat = caps.colorFormats[i];

            if (isRecognizedViewoFormat(colorFormat)) {
                if (result == 0) {
                    result = colorFormat;
                }
                break;
            }
        }

        return result;
    }

    protected static int[] recognizedFormats = new int[]{MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface};

    private static boolean isRecognizedViewoFormat(int colorFormat) {
        Log.d(TAG, "isRecognizedViewoFormat colorFormat : " + colorFormat);

        final int n = recognizedFormats != null ? recognizedFormats.length : 0;

        for (int i = 0; i < n; i++) {
            if (recognizedFormats[i] == colorFormat) {
                return true;
            }
        }

        return false;
    }

    private class DrawTask extends EglTask {

        private FullFrameRect drawer;

        private int texId;
        private long intervals;

        private SurfaceTexture sourceTexture;
        private WindowSurface encoderSurface;
        private Surface sourceSurface;
        private VirtualDisplay display;

        private final float[] texMatrix = new float[16];

        public DrawTask(EGLContext context, int flags) {
            super(context, flags);
        }

        @Override
        protected void onStart() {
            Log.d(TAG, "DrawTask#onStart");

            drawer = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
            texId = drawer.createTextureObject();

            sourceTexture = new SurfaceTexture(texId);
            sourceTexture.setDefaultBufferSize(width, height);
            sourceTexture.setOnFrameAvailableListener(onFrameAvailableListener, handler);

            sourceSurface = new Surface(sourceTexture);

            encoderSurface = new WindowSurface(getEglCore(), surface);

            Log.d(TAG, "DrawTask#onStart setup virtualDisplay");
            intervals = (long) (1000f / FRAME_RATE);
            display = mediaProjection.createVirtualDisplay("Capturing Display",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    sourceSurface,
                    null,
                    null);

            Log.d(TAG, "DrawTask#onStart screen capture loop display : " + display);
            queueEvent(drawTask);
        }

        @Override
        protected void onStop() {
            if (drawer != null) {
                drawer.release();
                drawer = null;
            }

            if (sourceSurface != null) {
                sourceSurface.release();
                sourceSurface = null;
            }

            if (sourceTexture != null) {
                sourceTexture.release();
                sourceTexture = null;
            }

            if (encoderSurface != null) {
                encoderSurface.release();
                encoderSurface = null;
            }

            makeCurrent();

            Log.d(TAG, "DrawTask#onStop");

            if (display != null) {
                Log.d(TAG, "DrawTask#onStop release virtual display");

                display.release();
            }

            Log.d(TAG, "DrawTask#onStop tear down MediaProjection");

            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
        }

        @Override
        protected boolean onError(Exception e) {
            Log.e(TAG, "DrawTesk#onError : ", e);
            return false;
        }

        @Override
        protected boolean processRequest(int request, int arg1, Object arg2) {
            return false;
        }

        private final SurfaceTexture.OnFrameAvailableListener onFrameAvailableListener = new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                synchronized (sync) {
                    requestDraw = true;
                    sync.notifyAll();
                }
            }
        };

        private final Runnable drawTask = new Runnable() {
            @Override
            public void run() {
                boolean localReuqestPause;
                boolean localRequestDraw;

                synchronized (sync) {
                    localReuqestPause = requestPause;
                    localRequestDraw = requestDraw;

                    if (!requestDraw) {
                        try {
                            sync.wait(intervals);
                            localReuqestPause = requestPause;
                            localRequestDraw = requestDraw;
                            requestDraw = false;
                        }
                        catch (InterruptedException e) {
                            return;
                        }
                    }
                }

                if (isCapturing) {
                    if (localRequestDraw) {
                        sourceTexture.updateTexImage();
                        sourceTexture.getTransformMatrix(texMatrix);
                    }

                    if (!localReuqestPause) {
                        encoderSurface.makeCurrent();
                        drawer.drawFrame(texId, texMatrix);
                        encoderSurface.swapBuffers();
                    }

                    makeCurrent();
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glFlush();

                    frameAvailableSoon();
                    queueEvent(this);
                }
                else {
                    releaseSelf();
                }
            }
        };
    }
}
