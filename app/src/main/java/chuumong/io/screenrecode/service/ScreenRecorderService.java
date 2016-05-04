package chuumong.io.screenrecode.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.IOException;

import chuumong.io.screenrecode.media.MediaAudioEncoder;
import chuumong.io.screenrecode.media.MediaEncoder;
import chuumong.io.screenrecode.media.MediaMuxerWrapper;
import chuumong.io.screenrecode.media.MediaScreenEncoder;

/**
 * Created by LeeJongHun on 2016-05-02.
 * <br/>
 * IntentService는 비동기로 실행되는 서비스
 * <br/>
 * 여러 번 실행되었을 경우에는 Queue로 처리
 */
public class ScreenRecorderService extends IntentService {

    private static final String TAG = ScreenRecorderService.class.getSimpleName();
    private static final String BASE = ScreenRecorderService.class.getPackage().getName() + "." + TAG + ".";
    public static final String EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE";

    private static Object sync = new Object();

    private MediaProjectionManager mediaProjectionManager;
    private static MediaMuxerWrapper muxer;

    public ScreenRecorderService() {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "onHandleIntent intent : " + intent);

        final String action = intent.getAction();

        if (action.equals(RecodeType.ACTION_START)) {
            startScreenRecode(intent);
        }
        else if (action.equals(RecodeType.ACTION_STOP)) {
            stopScreenRecord();
        }
        else if (action.equals(RecodeType.ACTION_PAUSE)) {
            pauseScreenRecord();
        }
        else if (action.equals(RecodeType.ACTION_RESUME)) {
            resumeScreenRecord();
        }
    }

    private void startScreenRecode(Intent intent) {
        Log.d(TAG, "startScreenRecord muxer : " + muxer);

        synchronized (sync) {
            final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            final MediaProjection projection = mediaProjectionManager.getMediaProjection(resultCode, intent);

            if (projection != null) {
                final DisplayMetrics metrics = getResources().getDisplayMetrics();
                final int density = metrics.densityDpi;

                Log.d(TAG, "startRecording");

                try {
                    muxer = new MediaMuxerWrapper(".mp4");

                    new MediaScreenEncoder(muxer, mediaEncoderListener, projection, metrics.widthPixels, metrics.heightPixels, density);
                    new MediaAudioEncoder(muxer, mediaEncoderListener);

                    muxer.prepare();
                    muxer.startRecording();
                }
                catch (IOException e) {
                    Log.e(TAG, "startScreenRecode error", e);
                }
            }
        }
    }

    private void stopScreenRecord() {
        Log.d(TAG, "stopScreenRecord muxer : " + muxer);

        synchronized (sync) {
            if (muxer != null) {
                muxer.stopRecording();
                muxer = null;
            }
        }
    }

    private void pauseScreenRecord() {
        synchronized (sync) {
            if (muxer != null) {
                muxer.pauseRecording();
            }
        }
    }

    private void resumeScreenRecord() {
        synchronized (sync) {
            if (muxer != null) {
                muxer.resumeRecording();
            }
        }
    }

    private static final MediaEncoder.MediaEncoderListener mediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
        @Override
        public void onPrepared(MediaEncoder encoder) {
            Log.d(TAG, "onPrepared encoder : " + encoder);
        }

        @Override
        public void onStopped(MediaEncoder encoder) {
            Log.d(TAG, "onStopped encoder : " + encoder);
        }
    };

    public interface RecodeType {

        String ACTION_START = BASE + "ACTION_START";
        String ACTION_STOP = BASE + "ACTION_STOP";
        String ACTION_PAUSE = BASE + "ACTION_PAUSE";
        String ACTION_RESUME = BASE + "ACTION_RESUME";
    }
}
