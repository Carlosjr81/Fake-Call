package br.com.jarvis.voice;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class Wav {
  public static void write(File f, short[] samples, int rate) throws IOException {
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(encode(samples, rate));
    }
  }

  public static byte[] encode(short[] samples, int rate) {
    ByteBuffer b = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    b.putInt(36 + samples.length * 2);
    b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
    b.putInt(16);
    b.putShort((short) 1);
    b.putShort((short) 1);
    b.putInt(rate);
    b.putInt(rate * 2);
    b.putShort((short) 2);
    b.putShort((short) 16);
    b.put("data".getBytes(StandardCharsets.US_ASCII));
    b.putInt(samples.length * 2);
    for (short s : samples) b.putShort(s);
    return b.array();
  }

  /** Decode RIFF PCM16 mono/stereo, resample to 16k. Strict bounds, no fixed 44-byte assumption. */
  public static short[] decode(byte[] bytes) throws IOException {
    if (bytes.length < 44) throw new IOException("WAV incompleto");
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157)
      throw new IOException("Selecione WAV PCM de 16 bits");
    int channels = 0, rate = 0, start = -1, size = 0;
    for (int p = 12; p + 8 <= bytes.length; ) {
      int id = b.getInt(p), n = b.getInt(p + 4);
      if (n < 0 || (long) p + 8 + n > bytes.length) throw new IOException("Bloco WAV inválido");
      if (id == 0x20746d66 && n >= 16) {
        if (b.getShort(p + 8) != 1 || b.getShort(p + 22) != 16)
          throw new IOException("Use WAV PCM de 16 bits");
        channels = b.getShort(p + 10);
        rate = b.getInt(p + 12);
      }
      if (id == 0x61746164) {
        start = p + 8;
        size = n;
      }
      p += 8 + n + (n & 1);
    }
    if ((channels != 1 && channels != 2)
        || rate < 8000
        || rate > 192000
        || start < 0
        || size % (channels * 2) != 0) throw new IOException("Formato WAV não suportado");
    int frames = size / (2 * channels);
    if (frames == 0) throw new IOException("Áudio vazio");
    int length = (int) ((long) frames * VoiceDsp.RATE / rate);
    if (length < 1 || length > VoiceDsp.RATE * 120) throw new IOException("Limite: 2 minutos");
    short[] out = new short[length];
    for (int i = 0; i < length; i++) {
      double pos = i * (double) rate / VoiceDsp.RATE;
      int a = Math.min(frames - 1, (int) pos), c = Math.min(frames - 1, a + 1);
      float va = 0, vc = 0;
      for (int ch = 0; ch < channels; ch++) {
        va += b.getShort(start + (a * channels + ch) * 2);
        vc += b.getShort(start + (c * channels + ch) * 2);
      }
      out[i] = (short) ((va + (vc - va) * (pos - a)) / channels);
    }
    return out;
  }
}
