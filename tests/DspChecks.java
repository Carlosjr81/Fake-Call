import br.com.jarvis.voice.VoiceDsp;
import br.com.jarvis.voice.Wav;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DspChecks {
  static int checks = 0;

  static void check(boolean ok, String label) {
    checks++;
    if (!ok) throw new AssertionError(label);
    System.out.println("PASS " + label);
  }

  static double peak(short[] samples, int lo, int hi) {
    double best = 0;
    int at = 0;
    for (int f = lo; f <= hi; f++) {
      double a = 0, b = 0;
      for (int i = 8000; i < samples.length; i++) {
        double theta = 2 * Math.PI * f * i / 16000;
        a += samples[i] * Math.cos(theta);
        b += samples[i] * Math.sin(theta);
      }
      double e = a * a + b * b;
      if (e > best) {
        best = e;
        at = f;
      }
    }
    return at;
  }

  public static void main(String[] args) throws Exception {
    short[] s = new short[32000];
    for (int i = 0; i < s.length; i++)
      s[i] = (short) (Math.sin(i * 2 * Math.PI * 220 / 16000) * 12000);
    short[] plain = new short[s.length];
    new VoiceDsp().process(s, plain, s.length);
    long error = 0;
    for (int i = 0; i < s.length; i++) error = Math.max(error, Math.abs(s[i] - plain[i]));
    check(error <= 1, "original preserves PCM within 1 LSB");
    for (int p = 1; p <= 2; p++) {
      VoiceDsp d = new VoiceDsp();
      d.preset = p;
      short[] out = new short[s.length];
      short[] block = new short[320], mixed = new short[320];
      for (int n = 0; n < s.length; n += 320) {
        System.arraycopy(s, n, block, 0, 320);
        d.process(block, mixed, 320);
        System.arraycopy(mixed, 0, out, n, 320);
      }
      double frequency = peak(out, 130, 320);
      check(
          Math.abs(frequency - (p == 1 ? 172 : 275)) < 8,
          "streaming pitch preset " + p + " peak=" + frequency);
    }
    VoiceDsp bg = new VoiceDsp();
    bg.environment = 4;
    bg.imported = new short[] {1000, -1000};
    bg.environmentGain = .5f;
    short[] out = new short[1000];
    bg.process(new short[1000], out, 1000);
    check(
        Math.abs(out[800] - 500) <= 1 && Math.abs(out[801] + 500) <= 1,
        "imported ambience loops with gain");
    VoiceDsp loud = new VoiceDsp();
    loud.voiceGain = 10;
    loud.process(s, out, 1000);
    boolean clipped = false;
    for (short v : out) clipped |= v == 32767 || v == -32767;
    check(clipped, "saturation prevents sample wrap-around");
    byte[] encoded = Wav.encode(s, 16000);
    short[] decoded = Wav.decode(encoded);
    check(java.util.Arrays.equals(s, decoded), "WAV PCM16 round trip");
    byte[] broken = encoded.clone();
    ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Integer.MAX_VALUE);
    boolean rejected = false;
    try {
      Wav.decode(broken);
    } catch (IOException e) {
      rejected = true;
    }
    check(rejected, "malformed WAV chunk rejected");
    byte[] stereo = new byte[44 + 48000 * 4];
    System.arraycopy(encoded, 0, stereo, 0, 44);
    ByteBuffer b = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(4, stereo.length - 8);
    b.putShort(22, (short) 2);
    b.putInt(24, 48000);
    b.putInt(28, 192000);
    b.putShort(32, (short) 4);
    b.putInt(40, 48000 * 4);
    for (int i = 44; i < stereo.length; i += 4) {
      b.putShort(i, (short) 2000);
      b.putShort(i + 2, (short) 4000);
    }
    short[] normalized = Wav.decode(stereo);
    check(
        normalized.length == 16000 && normalized[500] == 3000, "stereo 48k downmix and resampling");
    System.out.println(checks + " meaningful audio checks passed");
  }
}
