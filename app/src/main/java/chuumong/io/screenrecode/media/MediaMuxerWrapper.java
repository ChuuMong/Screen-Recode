package chuumong.io.screenrecode.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Created by LeeJongHun on 2016-05-04.
 */
public final class MediaMuxerWrapper {

    private static final String TAG = MediaMuxerWrapper.class.getSimpleName();

    private static final String DIR_NAME = "ScreenRecode";

    private final File outputFile;
    private final String outputFilePath;
    private final MediaMuxer mediaMuxer;
    private boolean isStarted;
    private int encoderCount, startedCount;
    private MediaScreenEncoder screenEncoder;
    private MediaAudioEncoder audioEncoder;

    public MediaMuxerWrapper() throws IOException {
        this(".mp4");
    }

    public MediaMuxerWrapper(@Nullable String ext) throws IOException {
        if (TextUtils.isEmpty(ext)) {
            ext = ".mp4";
        }

        outputFile = getCaptureFile(ext);

        if (outputFile != null) {
            outputFilePath = outputFile.toString();
        }
        else {
            throw new RuntimeException("Out put File Error");
        }

        mediaMuxer = new MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        encoderCount = startedCount = 0;
        isStarted = false;
    }

    public synchronized boolean isStarted() {
        return isStarted;
    }

    protected void addEncoder(MediaEncoder encoder) {
        if (encoder instanceof MediaScreenEncoder) {
            if (screenEncoder != null) {
                throw new IllegalArgumentException("Screen encoder added.");
            }

            screenEncoder = (MediaScreenEncoder) encoder;
        }
        else if (encoder instanceof MediaAudioEncoder) {
            if (audioEncoder != null) {
                throw new IllegalArgumentException("Audio encoder added.");
            }

            audioEncoder = (MediaAudioEncoder) encoder;
        }
        else {
            throw new IllegalArgumentException("unsupported encoder");
        }

        encoderCount = (screenEncoder != null ? 1 : 0) + (audioEncoder != null ? 1 : 0);
    }

    public void prepare() throws IOException {
        if (screenEncoder != null) {
            screenEncoder.prepare();
        }

        if (audioEncoder != null) {
            audioEncoder.prepare();
        }
    }

    public void startRecording() {
        if (screenEncoder != null) {
            screenEncoder.startRecording();
        }

        if (audioEncoder != null) {
            audioEncoder.startRecording();
        }
    }

    public void stopRecording() {
        if (screenEncoder != null) {
            screenEncoder.stopRecording();
        }

        if (audioEncoder != null) {
            audioEncoder.stopRecording();
        }
    }

    public synchronized void pauseRecording() {
        if (screenEncoder != null) {
            screenEncoder.pauseRecording();
        }

        if (audioEncoder != null) {
            audioEncoder.pauseRecording();
        }
    }

    public synchronized void resumeRecording() {
        if (screenEncoder != null) {
            screenEncoder.resumeRecording();
        }

        if (audioEncoder != null) {
            audioEncoder.resumeRecording();
        }
    }

    protected synchronized boolean start() {
        startedCount++;
        Log.d(TAG, "start startedCount : " + startedCount);

        if (encoderCount > 0 && startedCount == encoderCount) {
            mediaMuxer.start();
            isStarted = true;
            notifyAll();

            Log.d(TAG, "MediaMuxer start");
        }

        return isStarted;
    }

    protected synchronized void stop() {
        Log.d(TAG, "stop startedCount : " + startedCount);

        startedCount--;

        if (encoderCount > 0 && startedCount <= 0) {
            mediaMuxer.stop();
            mediaMuxer.release();
            isStarted = false;

            Log.d(TAG, "MediaMuxer Stop");
        }
    }

    protected synchronized int addTrack(MediaFormat format) {
        if (isStarted) {
            throw new IllegalStateException("muxer already started");
        }

        final int trackIndex = mediaMuxer.addTrack(format);

        Log.d(TAG, "addTrack encoderCount : " + encoderCount + ", trackIndex : " + trackIndex + ", format : " + format);

        return trackIndex;
    }

    protected void writeData(int trackIndex, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        if (startedCount > 0) {
            mediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo);
        }
    }

    private static File getCaptureFile(String ext) {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), DIR_NAME);
        Log.d(TAG, "getCaptureFile Output File path : " + dir.toString());

        dir.mkdirs();

        if (dir.canWrite()) {
            return new File(dir, getDataTimeString() + ext);
        }
        else {
            return null;
        }
    }

    private static String getDataTimeString() {
        return new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(new GregorianCalendar().getTime());
    }
}
