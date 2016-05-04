package chuumong.io.screenrecode.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Process;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by LeeJongHun on 2016-05-04.
 */
public class MediaAudioEncoder extends MediaEncoder {

    private static final String TAG = MediaAudioEncoder.class.getSimpleName();
    private static final String MIME_TYPE = "audio/mp4a-latm";

    private static final int SAMPLE_RATE = 44100;
    private static final int BIT_RATE = 64000;
    private static final int SAMPLES_PER_FRAME = 1024;
    private static final int FRAMES_PER_BUFFER = 25;

    private AudioThread audioThread;

    public MediaAudioEncoder(@NonNull MediaMuxerWrapper muxer, @NonNull MediaEncoderListener listener) {
        super(muxer, listener);
    }

    @Override
    protected void prepare() throws IOException {
        Log.d(TAG, "prepare");

        trackIndex = -1;
        muxerStarted = isEOS = false;

        final MediaCodecInfo codecInfo = selectAudioCodec();
        if (codecInfo == null) {
            Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
            return;
        }

        Log.d(TAG, "prepare select codec : " + codecInfo.getName());

        final MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);

        Log.d(TAG, "prepare format : " + format);

        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaCodec.start();

        Log.d(TAG, "prepare finishing");

        listener.onPrepared(this);
    }

    @Override
    protected void startRecording() {
        Log.d(TAG, "startRecording");
        super.startRecording();

        if (audioThread == null) {
            audioThread = new AudioThread();
            audioThread.start();
        }
    }

    @Override
    protected void release() {
        audioThread = null;
        super.release();
    }

    /**
     * 특정 MIME 타입과 일치하는 코덱을 선택
     */
    @SuppressWarnings("deprecation")
    private static MediaCodecInfo selectAudioCodec() {
        Log.d(TAG, "selectAudioCodec");

        final int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            final MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);

            if (!info.isEncoder()) {
                continue;
            }

            final String[] types = info.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(MIME_TYPE)) {
                    return info;
                }
            }
        }

        return null;
    }

    private static final int[] AUDIO_SOURCES = new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT,
                                                         MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                                         MediaRecorder.AudioSource.VOICE_RECOGNITION,};

    /**
     * 압축되지 않은 16 비트 PCM 데이터로 내장 마이크의 오디오 데이터를 캡처하고 MediaCodec 인코더에 기록
     */
    private class AudioThread extends Thread {

        @Override
        public void run() {

            Log.d(TAG, "AudioThread#run");

            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            final int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;
            if (bufferSize < minBufferSize) {
                bufferSize = ((minBufferSize / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;
            }

            AudioRecord audioRecord = null;

            for (int source : AUDIO_SOURCES) {
                audioRecord = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = null;
                    continue;
                }
                else {
                    break;
                }
            }

            if (audioRecord != null) {
                for (; isCapturing; ) {
                    synchronized (sync) {
                        if (isCapturing && !requestStop && requestPause) {
                            try {
                                sync.wait();
                            }
                            catch (InterruptedException e) {
                                break;
                            }
                            continue;
                        }
                    }

                    if (isCapturing && !requestStop && !requestPause) {
                        Log.d(TAG, "AudioThread#run start audio recording");

                        final ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);
                        int readBytes;

                        audioRecord.startRecording();

                        for (; isCapturing && !requestStop && !requestPause && !isEOS; ) {
                            buf.clear();
                            readBytes = audioRecord.read(buf, SAMPLES_PER_FRAME);

                            if (readBytes > 0) {
                                buf.position(readBytes);
                                buf.flip();
                                // 데이터 저장
                                encode(buf, readBytes, getPTSUs());
                                frameAvailableSoon();
                            }
                        }
                        frameAvailableSoon();
                        audioRecord.stop();
                    }
                }

                audioRecord.release();
            }
            else {
                Log.e(TAG, "AudioThread#run failed to initialize AudioRecord");
            }

            Log.d(TAG, "AudioThread#run finished");
        }
    }
}
