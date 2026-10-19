package br.com.jarvis.voice;

import android.media.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

public final class AudioEngine {
  public interface Events {
    void meter(float value);

    void frame(byte[] pcm, int version);

    void error(String message);
  }

  public final VoiceDsp dsp = new VoiceDsp();
  private volatile boolean running = false, monitor = false, capture = false;
  private Thread worker;
  private AudioRecord recorder;
  private AudioTrack player;
  private final ArrayBlockingQueue<short[]> remote = new ArrayBlockingQueue<>(12);
  private final Object recordLock = new Object();
  private ByteArrayOutputStream raw = new ByteArrayOutputStream(),
      mixed = new ByteArrayOutputStream();
  private volatile long micMutedUntil = 0;
  private final Events events;

  public AudioEngine(Events e) {
    events = e;
  }

  public void suppressMicrophone(int milliseconds) {
    micMutedUntil = System.nanoTime() + milliseconds * 1000000L;
  }

  public int configure(int preset, int environment, float voiceGain, float ambienceGain) {
    synchronized (dsp) {
      dsp.preset = preset;
      dsp.environment = environment;
      dsp.voiceGain = voiceGain;
      dsp.environmentGain = ambienceGain;
      return ++dsp.configVersion;
    }
  }

  public boolean isRunning() {
    return running;
  }

  public void setMonitor(boolean enabled) {
    monitor = enabled;
  }

  public void beginRecording() {
    synchronized (recordLock) {
      raw.reset();
      mixed.reset();
      capture = true;
    }
  }

  public short[][] endRecording() {
    synchronized (recordLock) {
      capture = false;
      return new short[][] {fromBytes(raw.toByteArray()), fromBytes(mixed.toByteArray())};
    }
  }

  public synchronized void start() {
    if (running) return;
    int size =
        Math.max(
            3200,
            AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
    try {
      recorder =
          new AudioRecord(
              MediaRecorder.AudioSource.VOICE_COMMUNICATION,
              16000,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              size);
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IOException("Microfone indisponível");
      player =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(16000)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build())
              .setBufferSizeInBytes(size)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build();
      recorder.startRecording();
      player.play();
      running = true;
      worker = new Thread(this::loop, "jarvis-audio");
      worker.start();
    } catch (SecurityException e) {
      release();
      events.error("Permissão do microfone revogada.");
    } catch (Exception e) {
      release();
      events.error("Não foi possível abrir o áudio: " + e.getMessage());
    }
  }

  private void loop() {
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
    short[] input = new short[320], output = new short[320], silence = new short[320];
    try {
      while (running) {
        int n = recorder.read(input, 0, 320, AudioRecord.READ_BLOCKING);
        if (n < 0) {
          if (running) throw new IOException("Falha no microfone: " + n);
          break;
        }
        if (n == 0) continue;
        if (System.nanoTime() < micMutedUntil) Arrays.fill(input, 0, n, (short) 0);
        int version;
        synchronized (dsp) {
          dsp.process(input, output, n);
          version = dsp.configVersion;
        }
        float peak = 0;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(output[i] / 32768f));
        events.meter(peak);
        byte[] frame = toBytes(output, n);
        synchronized (recordLock) {
          if (capture) {
            if (raw.size() + n * 2 > 16000 * 2 * 120) {
              capture = false;
              events.error("Limite de 2 minutos atingido; pare para salvar.");
            } else {
              raw.write(toBytes(input, n));
              mixed.write(frame);
            }
          }
        }
        events.frame(frame, version);
        short[] received = remote.poll();
        if (received != null) {
          player.write(received, 0, received.length);
        } else if (monitor) player.write(output, 0, n);
        else {
          player.write(silence, 0, n);
        }
      }
    } catch (Exception e) {
      if (running) events.error("Áudio interrompido: " + e.getMessage());
    } finally {
      running = false;
    }
  }

  public void remoteFrame(byte[] bytes) {
    short[] f = fromBytes(bytes);
    if (!remote.offer(f)) {
      remote.poll();
      remote.offer(f);
    }
  }

  public synchronized void stop() {
    running = false;
    if (recorder != null)
      try {
        recorder.stop();
      } catch (Exception ignored) {
      }
    if (worker != null)
      try {
        worker.join(1200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    release();
    remote.clear();
  }

  private void release() {
    if (recorder != null) {
      recorder.release();
      recorder = null;
    }
    if (player != null) {
      player.release();
      player = null;
    }
  }

  public static byte[] toBytes(short[] s, int n) {
    byte[] b = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      b[i * 2] = (byte) s[i];
      b[i * 2 + 1] = (byte) (s[i] >> 8);
    }
    return b;
  }

  public static short[] fromBytes(byte[] b) {
    short[] s = new short[b.length / 2];
    for (int i = 0; i < s.length; i++) s[i] = (short) ((b[i * 2] & 255) | (b[i * 2 + 1] << 8));
    return s;
  }
}
