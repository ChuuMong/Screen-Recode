package chuumong.io.screenrecode.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Created by LeeJongHun on 2016-05-04.
 */
public abstract class MediaEncoder implements Runnable {

    private static final String TAG = MediaEncoder.class.getSimpleName();

    private static final long TIMEOUT_USEC = 10000;


    protected final WeakReference<MediaMuxerWrapper> weakMuxer;
    protected final MediaEncoderListener listener;

    protected final Object sync = new Object();
    private MediaCodec.BufferInfo bufferInfo;

    protected MediaCodec mediaCodec;

    protected int trackIndex;
    protected boolean isCapturing;
    protected boolean muxerStarted;

    protected boolean isEOS;

    private int requestDrain = 0;
    protected volatile boolean requestPause;
    protected volatile boolean requestStop;
    private long lastPausedTimeUs;
    private long prevOutputPTSUs;
    private long offsetPTUs;

    public MediaEncoder(@NonNull MediaMuxerWrapper muxer, @NonNull MediaEncoderListener listener) {
        this.weakMuxer = new WeakReference<>(muxer);
        this.listener = listener;

        muxer.addEncoder(this);

        synchronized (sync) {
            bufferInfo = new MediaCodec.BufferInfo();

            new Thread(this, getClass().getSimpleName()).start();

            try {
                sync.wait();
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    protected boolean frameAvailableSoon() {
        synchronized (sync) {
            if (!isCapturing || requestStop) {
                return false;
            }

            requestDrain++;
            sync.notifyAll();
        }

        return true;
    }

    protected abstract void prepare() throws IOException;

    protected void startRecording() {
        Log.d(TAG, "startRecording");

        synchronized (sync) {
            isCapturing = true;
            requestStop = false;
            requestPause = false;
            sync.notifyAll();
        }
    }

    protected void stopRecording() {
        Log.d(TAG, "stopRecording");

        synchronized (sync) {
            if (!isCapturing || requestStop) {
                return;
            }

            requestStop = true;
            sync.notifyAll();
        }
    }

    public void pauseRecording() {
        Log.d(TAG, "pauseRecording");

        synchronized (sync) {
            if (!isCapturing || requestStop) {
                return;
            }

            requestPause = true;
            lastPausedTimeUs = System.nanoTime() / 1000;
            sync.notifyAll();
        }
    }

    public void resumeRecording() {
        Log.d(TAG, "resumeRecording");

        synchronized (sync) {
            if (!isCapturing || requestStop) {
                return;
            }

            offsetPTUs = System.nanoTime() / 1000 - lastPausedTimeUs;
            requestPause = false;
            sync.notifyAll();
        }
    }

    protected void release() {
        Log.d(TAG, "release");

        listener.onStopped(this);

        isCapturing = false;

        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }

        if (muxerStarted) {
            final MediaMuxerWrapper muxer = weakMuxer != null ? weakMuxer.get() : null;

            if (muxer != null) {
                muxer.stop();
            }
        }

        bufferInfo = null;
    }

    @Override
    public void run() {
        synchronized (sync) {
            requestStop = false;
            requestDrain = 0;
            sync.notify();
        }

        boolean localRequestStop;
        boolean localRequestDrain;

        while (true) {
            synchronized (sync) {
                localRequestStop = requestStop;
                localRequestDrain = requestDrain > 0;

                if (localRequestDrain) {
                    requestDrain--;
                }
            }

            if (localRequestStop) {
                drain();
                signalEndOfInputStream();
                drain();
                release();

                break;
            }

            if (localRequestDrain) {
                drain();
            }
            else {
                synchronized (sync) {
                    try {
                        sync.wait();
                    }
                    catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }

        Log.d(TAG, "run Encoder Thread Exting");

        synchronized (sync) {
            requestStop = true;
            isCapturing = false;
        }
    }

    protected void signalEndOfInputStream() {
        Log.d(TAG, "signalEndOfInputStream");

        encode(null, 0, getPTSUs());
    }

    @SuppressWarnings("deprecation")
    protected void drain() {
        if (mediaCodec == null) {
            return;
        }

        ByteBuffer[] encoderOutputBuffers = mediaCodec.getOutputBuffers();
        int encoderStatus, count = 0;

        final MediaMuxerWrapper muxer = weakMuxer.get();
        if (muxer == null) {
            Log.d(TAG, "drain muxer is unexpectedly null");
            return;
        }

        LOOP:
        while (isCapturing) {
            encoderStatus = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

            switch (encoderStatus) {
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    if (!isEOS) {
                        if (++count > 5) {
                            break LOOP;
                        }
                    }
                    break;
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                    encoderOutputBuffers = mediaCodec.getOutputBuffers();
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED");

                    if (muxerStarted) {
                        throw new RuntimeException("format changed twice");
                    }

                    final MediaFormat format = mediaCodec.getOutputFormat();
                    trackIndex = muxer.addTrack(format);
                    muxerStarted = true;

                    if (!muxer.start()) {
                        synchronized (muxer) {
                            while (!muxer.isStarted()) {
                                try {
                                    muxer.wait(100);
                                }
                                catch (InterruptedException e) {
                                    break LOOP;
                                }
                            }
                        }
                    }
                    break;
                default:
                    final ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        Log.d(TAG, "BUFFER_FLAG_CODEC_CONFIG");
                        bufferInfo.size = 0;
                    }

                    if (bufferInfo.size != 0) {
                        count = 0;

                        if (!muxerStarted) {
                            throw new RuntimeException("drain muxer hasn't started");
                        }

                        bufferInfo.presentationTimeUs = getPTSUs();
                        muxer.writeData(trackIndex, encodedData, bufferInfo);
                        prevOutputPTSUs = bufferInfo.presentationTimeUs;
                    }

                    mediaCodec.releaseOutputBuffer(encoderStatus, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isCapturing = false;
                        break;
                    }
            }
        }
    }

    @SuppressWarnings("deprecation")
    protected void encode(final ByteBuffer buffer, int length, long time) {
        if (!isCapturing) {
            return;
        }

        final ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();

        while (isCapturing) {
            final int inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_USEC);

            if (inputBufferIndex > 0) {
                final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();

                if (buffer != null) {
                    // 데이터 저장
                    inputBuffer.put(buffer);
                }

                if (length <= 0) {
                    isEOS = true;
                    Log.d(TAG, "encode BUFFER_FLAG_END_OF_STREAM");

                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                else {
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, length, time, 0);
                }
                break;
            }
            else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

            }
        }
    }

    protected long getPTSUs() {
        long result;

        synchronized (sync) {
            result = System.nanoTime() / 1000L - offsetPTUs;
        }

        if (result < prevOutputPTSUs) {
            result = (prevOutputPTSUs - result) + result;
        }

        return result;
    }

    public interface MediaEncoderListener {

        void onPrepared(MediaEncoder encoder);

        void onStopped(MediaEncoder encoder);
    }

}

