package it.example.registraschermo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cattura lo schermo con MediaProjection e SOLO l'audio di playback tramite
 * AudioPlaybackCaptureConfiguration (NON usa MediaRecorder.AudioSource.MIC).
 * Codifica video AVC/H.264 e audio AAC in un singolo MP4 con MediaMuxer.
 */
final class AvRecorder {
    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int FPS = 30;
    private static final int AUDIO_RATE = 44100;
    private static final int AUDIO_BYTES_PER_FRAME = 2; // mono PCM16

    private final Context context;
    private final MediaProjection projection;
    private final Runnable projectionEnded;
    private final Object muxerLock = new Object();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private AudioRecord playbackRecord;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private ParcelFileDescriptor fileDescriptor;
    private Uri outputUri;
    private MediaProjection.Callback callback;
    private Thread videoDrainThread;
    private Thread audioInputThread;
    private Thread audioDrainThread;

    private volatile Throwable asyncError;
    private volatile boolean started;
    private boolean muxerStarted;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private long videoFirstPts = -1;
    private long audioFirstPts = -1;
    private boolean finished;
    private volatile boolean paused;
    private long pauseStartedNs;
    private long pausedTotalUs;

    AvRecorder(Context context, MediaProjection projection, Runnable projectionEnded) {
        this.context = context.getApplicationContext();
        this.projection = projection;
        this.projectionEnded = projectionEnded;
    }

    void start() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        String filename = "Schermo_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY)
                .format(new Date()) + ".mp4";
        values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/RegistraSchermo/");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) throw new IllegalStateException("Impossibile creare il file video");
        fileDescriptor = resolver.openFileDescriptor(outputUri, "rw");
        if (fileDescriptor == null) throw new IllegalStateException("Impossibile aprire il video");
        muxer = new MediaMuxer(fileDescriptor.getFileDescriptor(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        DisplayMetrics display = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(display);
        int screenW = display.widthPixels;
        int screenH = display.heightPixels;
        // Limita a Full HD, mantenendo le proporzioni e dimensioni pari.
        double maxSide = Math.max(screenW, screenH);
        double minSide = Math.min(screenW, screenH);
        double scale = Math.min(1.0, Math.min(1920.0 / maxSide, 1080.0 / minSide));
        int width = Math.max(2, ((int) Math.round(screenW * scale)) & ~1);
        int height = Math.max(2, ((int) Math.round(screenH * scale)) & ~1);

        MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();

        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_RATE, 1);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        AudioPlaybackCaptureConfiguration playbackConfig =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        AudioFormat captureFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minimum = AudioRecord.getMinBufferSize(AUDIO_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("Formato audio non supportato");
        playbackRecord = new AudioRecord.Builder()
                .setAudioFormat(captureFormat)
                .setBufferSizeInBytes(Math.max(minimum * 2, 16384))
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build();
        if (playbackRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("Acquisizione audio interno non disponibile");
        }

        callback = new MediaProjection.Callback() {
            @Override public void onStop() {
                projectionEnded.run();
            }
        };
        projection.registerCallback(callback, new Handler(Looper.getMainLooper()));

        videoEncoder.start();
        audioEncoder.start();
        playbackRecord.startRecording();
        if (playbackRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Impossibile iniziare la cattura audio");
        }

        virtualDisplay = projection.createVirtualDisplay("RegistraSchermo",
                screenW, screenH, display.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, new Handler(Looper.getMainLooper()));
        if (virtualDisplay == null) throw new IllegalStateException("Display virtuale non disponibile");
        // Il display virtuale usa le dimensioni effettive, il codec quelle Full HD.
        // Android adatta la superficie; la rotazione dello schermo va evitata nella v1.
        started = true;
        videoDrainThread = new Thread(() -> drainEncoder(videoEncoder, false), "video-drain");
        audioDrainThread = new Thread(() -> drainEncoder(audioEncoder, true), "audio-drain");
        audioInputThread = new Thread(this::pumpPlaybackAudio, "playback-capture");
        videoDrainThread.start();
        audioDrainThread.start();
        audioInputThread.start();
    }

    boolean isPaused() { return paused; }

    /** Sospende il video e scarta l'audio interno durante la pausa. */
    synchronized void setPaused(boolean shouldPause) {
        if (!started || stopRequested.get() || paused == shouldPause) return;
        long now = System.nanoTime();
        Bundle params = new Bundle();
        if (shouldPause) {
            pauseStartedNs = now;
            params.putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1);
        } else {
            pausedTotalUs += (now - pauseStartedNs) / 1000L;
            params.putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0);
            params.putLong(MediaCodec.PARAMETER_KEY_OFFSET_TIME, -pausedTotalUs);
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        }
        videoEncoder.setParameters(params);
        paused = shouldPause;
    }

    private void pumpPlaybackAudio() {
        long capturedBytes = 0;
        byte[] discardBuffer = new byte[4096];
        try {
            while (true) {
                // La cattura resta attiva ma i campioni sono scartati in pausa.
                if (paused && !stopRequested.get()) {
                    int dropped = playbackRecord.read(discardBuffer, 0, discardBuffer.length);
                    if (dropped < 0 && !stopRequested.get())
                        throw new IllegalStateException("Audio non disponibile durante la pausa: " + dropped);
                    continue;
                }
                int index = audioEncoder.dequeueInputBuffer(10_000);
                if (index < 0) continue;
                ByteBuffer input = audioEncoder.getInputBuffer(index);
                if (input == null) throw new IllegalStateException("Buffer audio nullo");
                input.clear();
                long ptsUs = (capturedBytes * 1_000_000L) /
                        (AUDIO_RATE * AUDIO_BYTES_PER_FRAME);
                if (stopRequested.get()) {
                    audioEncoder.queueInputBuffer(index, 0, 0, ptsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                if (paused) {
                    audioEncoder.queueInputBuffer(index, 0, 0, ptsUs, 0);
                    continue;
                }
                int amount = playbackRecord.read(input,
                        Math.min(input.capacity(), 8192), AudioRecord.READ_BLOCKING);
                if (paused && !stopRequested.get()) {
                    audioEncoder.queueInputBuffer(index, 0, 0, ptsUs, 0);
                } else if (amount > 0) {
                    audioEncoder.queueInputBuffer(index, 0, amount, ptsUs, 0);
                    capturedBytes += amount;
                } else if (stopRequested.get()) {
                    audioEncoder.queueInputBuffer(index, 0, 0, ptsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                } else {
                    // Consegna il buffer al codec anche in caso di lettura vuota.
                    audioEncoder.queueInputBuffer(index, 0, 0, ptsUs, 0);
                    if (amount < 0) throw new IllegalStateException("Lettura audio non riuscita: " + amount);
                }
            }
        } catch (Throwable error) {
            signalError(error);
        }
    }

    private void drainEncoder(MediaCodec codec, boolean audio) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (asyncError != null) break;
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (muxerLock) {
                        int track = muxer.addTrack(codec.getOutputFormat());
                        if (audio) audioTrack = track;
                        else videoTrack = track;
                        if (audioTrack >= 0 && videoTrack >= 0 && !muxerStarted) {
                            muxer.start();
                            muxerStarted = true;
                            muxerLock.notifyAll();
                        }
                    }
                    continue;
                }
                if (index < 0) continue;
                ByteBuffer encoded = codec.getOutputBuffer(index);
                if (encoded != null && info.size > 0 &&
                        (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    synchronized (muxerLock) {
                        while (!muxerStarted && asyncError == null) {
                            muxerLock.wait(100);
                        }
                        if (muxerStarted) {
                            long pts = info.presentationTimeUs;
                            if (audio) {
                                if (audioFirstPts < 0) audioFirstPts = pts;
                                pts = Math.max(0, pts - audioFirstPts);
                            } else {
                                if (videoFirstPts < 0) videoFirstPts = pts;
                                pts = Math.max(0, pts - videoFirstPts);
                            }
                            MediaCodec.BufferInfo normalized = new MediaCodec.BufferInfo();
                            normalized.set(info.offset, info.size, pts, info.flags);
                            encoded.position(info.offset);
                            encoded.limit(info.offset + info.size);
                            muxer.writeSampleData(audio ? audioTrack : videoTrack,
                                    encoded, normalized);
                        }
                    }
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(index, false);
                if (eos) break;
            }
        } catch (Throwable error) {
            signalError(error);
        }
    }

    private void signalError(Throwable error) {
        if (asyncError == null) asyncError = error;
        stopRequested.set(true);
        synchronized (muxerLock) { muxerLock.notifyAll(); }
        projectionEnded.run();
    }

    /** Chiude gli encoder e rende visibile il video nella galleria. */
    synchronized Uri finishAndSave() throws Exception {
        if (finished) return outputUri;
        if (paused) setPaused(false);
        finished = true;
        stopRequested.set(true);
        if (started) {
            try { playbackRecord.stop(); } catch (Exception ignored) { }
            try { videoEncoder.signalEndOfInputStream(); } catch (Exception ignored) { }
            waitThread(audioInputThread);
            waitThread(audioDrainThread);
            waitThread(videoDrainThread);
        }
        synchronized (muxerLock) { muxerLock.notifyAll(); }
        boolean valid = muxerStarted && asyncError == null;
        cleanup(valid);
        if (!valid) {
            throw new IllegalStateException(asyncError != null
                    ? "Registrazione interrotta: " + asyncError.getMessage()
                    : "Non è stato possibile completare il file MP4");
        }
        return outputUri;
    }

    void abort() {
        if (finished) return;
        finished = true;
        stopRequested.set(true);
        cleanup(false);
    }

    private void waitThread(Thread thread) throws InterruptedException {
        if (thread == null) return;
        thread.join(5000);
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(1000);
            if (thread.isAlive()) {
                asyncError = new IllegalStateException("Timeout durante la codifica");
            }
        }
    }

    private void cleanup(boolean save) {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) { }
        try { if (callback != null) projection.unregisterCallback(callback); } catch (Exception ignored) { }
        try { projection.stop(); } catch (Exception ignored) { }
        try { if (playbackRecord != null) playbackRecord.release(); } catch (Exception ignored) { }
        try { if (videoEncoder != null) videoEncoder.stop(); } catch (Exception ignored) { }
        try { if (audioEncoder != null) audioEncoder.stop(); } catch (Exception ignored) { }
        try { if (videoEncoder != null) videoEncoder.release(); } catch (Exception ignored) { }
        try { if (audioEncoder != null) audioEncoder.release(); } catch (Exception ignored) { }
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) { }
        try { if (muxer != null && muxerStarted) muxer.stop(); } catch (Exception ignored) { save = false; }
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        try { if (fileDescriptor != null) fileDescriptor.close(); } catch (Exception ignored) { }
        if (outputUri != null) {
            try {
                if (save) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    context.getContentResolver().update(outputUri, values, null, null);
                } else {
                    context.getContentResolver().delete(outputUri, null, null);
                }
            } catch (Exception ignored) { }
        }
    }
}