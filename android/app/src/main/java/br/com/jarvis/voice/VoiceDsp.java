package br.com.jarvis.voice;

import java.util.Random;

/** Streaming PCM processor. Local effects are not AI voice clones. */
public final class VoiceDsp {
  public static final int RATE = 16000;
  public volatile int preset = 0, environment = 0, configVersion = 0;
  public volatile float voiceGain = 1f, environmentGain = .12f;
  public volatile short[] imported = null;
  private final float[] ring = new float[4096];
  private final Random random = new Random(7941);
  private long clock = 0;
  private double phase = 0;
  private float low = 0, previous = 0;
  private int importedPosition = 0;

  public void process(short[] input, short[] output, int count) {
    int mode = preset, env = environment;
    double ratio = mode == 1 ? .78 : mode == 2 ? 1.25 : 1;
    short[] ambience = imported;
    for (int i = 0; i < count; i++, clock++) {
      float sample = input[i] / 32768f;
      ring[(int) (clock & 4095)] = sample;
      float voice = sample;
      if (mode == 1 || mode == 2) {
        // Two crossfaded variable delay taps: constant output rate and duration.
        phase += (1.0 - ratio) / 640.0;
        phase -= Math.floor(phase);
        double p2 = (phase + .5) % 1.0;
        double a = .5 - .5 * Math.cos(2 * Math.PI * phase);
        voice = (float) (read(96 + phase * 640) * a + read(96 + p2 * 640) * (1 - a));
      } else if (mode == 3) {
        voice = sample * (float) Math.sin(2 * Math.PI * clock * 55 / RATE);
      } else if (mode == 4) {
        low += .2f * (sample - low);
        float high = low - previous;
        previous = low * .9f;
        voice = (float) Math.tanh(high * 5) * .65f;
      }
      float noise = (random.nextFloat() * 2 - 1);
      float bg = 0;
      if (env == 1) bg = noise * .45f; // synthetic rain
      if (env == 2) bg = noise * .2f + .2f * (float) Math.sin(2 * Math.PI * clock * 65 / RATE);
      if (env == 3) bg = noise * (.18f + .15f * (float) Math.sin(2 * Math.PI * clock * .18 / RATE));
      if (env == 4 && ambience != null && ambience.length > 0) {
        if (importedPosition >= ambience.length) importedPosition = 0;
        bg = ambience[importedPosition++] / 32768f;
      }
      // Headroom and saturation prevent wrap-around.
      float mix = voice * voiceGain + bg * environmentGain;
      output[i] = (short) Math.round(Math.max(-1, Math.min(1, mix)) * 32767);
    }
  }

  private float read(double delay) {
    double position = clock - delay;
    long base = (long) Math.floor(position);
    float fraction = (float) (position - base);
    return ring[(int) (base & 4095)] * (1 - fraction) + ring[(int) ((base + 1) & 4095)] * fraction;
  }
}
