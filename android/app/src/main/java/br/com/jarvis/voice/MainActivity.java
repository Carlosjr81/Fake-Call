package br.com.jarvis.voice;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

public class MainActivity extends Activity {
  private static final int BG = 0xff080f1c,
      CARD = 0xff131f31,
      INK = 0xffedf4ff,
      MUTED = 0xff9eacc4,
      GREEN = 0xff55dfbd;
  private LinearLayout page, content, statusRow;
  private TextView status, recLabel, cloneStatus;
  private Button recordButton, stopButton;
  private AudioEngine audio;
  private MediaPlayer playback;
  private CallClient call;
  private boolean recording = false, inCall = false;
  private File resultFile, sourceFile;
  private String server = "", token = "", room = "", voiceId = "", githubToken = "";
  private EditText serverInput, tokenInput, roomInput, voiceName, githubInput, phoneInput;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(180, TimeUnit.SECONDS)
          .callTimeout(190, TimeUnit.SECONDS)
          .followRedirects(false)
          .build();
  private final ArrayList<String> voiceIds = new ArrayList<>(), voiceNames = new ArrayList<>();
  private WaveView wave;
  private long recordingStarted = 0, callStarted = 0;
  private String lastCallState = "";
  private int tab = 0, selectedVersion = 0, beepedVersion = 0;
  private boolean menuOpen = false, outgoingPhone = false;
  private ToneGenerator readyTone;
  private AudioManager manager;
  private AudioFocusRequest focus;
  private final Handler timer = new Handler(Looper.getMainLooper());

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    manager = (AudioManager) getSystemService(AUDIO_SERVICE);
    setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
    server = getPreferences(0).getString("server", "");
    room = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    audio =
        new AudioEngine(
            new AudioEngine.Events() {
              public void meter(float v) {
                if (wave != null) wave.level = v;
              }

              public void frame(byte[] b, int version) {
                CallClient c = call;
                if (c != null) c.send(b, version);
              }

              public void error(String s) {
                runOnUiThread(
                    () -> {
                      message(s);
                    });
              }
            });
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build())
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change < 0) stopAll();
                })
            .build();
    readyTone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65);
    render(0);
    timer.post(
        new Runnable() {
          public void run() {
            if (wave != null) wave.invalidate();
            if (recording && recLabel != null)
              recLabel.setText(
                  String.format(
                      Locale.ROOT,
                      "GRAVANDO  %02d:%02d",
                      (System.currentTimeMillis() - recordingStarted) / 60000,
                      ((System.currentTimeMillis() - recordingStarted) / 1000) % 60));
            timer.postDelayed(this, 80);
          }
        });
  }

  private int dp(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  private GradientDrawable shape(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(18));
    return d;
  }

  private TextView text(String s, int sp, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setPadding(0, dp(8), 0, dp(8));
    return t;
  }

  private void title(String s) {
    content.addView(text(s, 21, INK));
  }

  private void note(String s) {
    content.addView(text(s, 14, MUTED));
  }

  private LinearLayout box() {
    LinearLayout b = new LinearLayout(this);
    b.setOrientation(LinearLayout.VERTICAL);
    b.setPadding(dp(18), dp(14), dp(18), dp(14));
    b.setBackground(shape(CARD));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(8), 0, dp(10));
    content.addView(b, p);
    return b;
  }

  private Button button(LinearLayout target, String s, Runnable r, boolean main) {
    Button b = new Button(this);
    b.setText(s);
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setTextColor(main ? BG : INK);
    b.setBackground(shape(main ? GREEN : 0xff26364e));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
    p.setMargins(0, dp(6), 0, dp(6));
    target.addView(b, p);
    b.setOnClickListener(v -> r.run());
    return b;
  }

  private EditText field(LinearLayout b, String hint, String value, boolean secret) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setTextSize(15);
    e.setHint(hint);
    e.setText(value);
    e.setInputType(
        secret
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    e.setPadding(dp(8), dp(12), dp(8), dp(12));
    b.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private void slider(
      LinearLayout b, String label, int max, int initial, java.util.function.IntConsumer fn) {
    TextView l = text(label + " · " + initial + "%", 14, MUTED);
    b.addView(l);
    SeekBar bar = new SeekBar(this);
    bar.setMax(max);
    bar.setProgress(initial);
    b.addView(bar);
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            l.setText(label + " · " + p + "%");
            fn.accept(p);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
  }

  private void spinner(
      LinearLayout b, String[] choices, int selected, java.util.function.IntConsumer fn) {
    Spinner sp = new Spinner(this);
    ArrayAdapter<String> a =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices) {
          @Override
          public View getView(int p, View v, android.view.ViewGroup g) {
            TextView t = (TextView) super.getView(p, v, g);
            t.setTextColor(INK);
            t.setPadding(dp(5), dp(12), dp(5), dp(12));
            return t;
          }
        };
    sp.setAdapter(a);
    sp.setSelection(Math.max(0, selected));
    b.addView(sp);
    sp.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
            fn.accept(pos);
          }

          public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
  }

  private void render(int index) {
    saveFields();
    tab = index;
    serverInput = tokenInput = roomInput = voiceName = githubInput = null;
    page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(20), dp(12), dp(20), 0);
    page.setBackgroundColor(BG);
    page.setOnApplyWindowInsetsListener(
        (v, w) -> {
          android.graphics.Insets i = w.getInsets(WindowInsets.Type.systemBars());
          page.setPadding(dp(20), i.top + dp(8), dp(20), i.bottom);
          return w;
        });
    TextView brand = text("JARVIS  /  VOICE", 13, GREEN);
    brand.setLetterSpacing(.16f);
    page.addView(brand);
    LinearLayout nav = new LinearLayout(this);
    String[] names = {"Estúdio", "Vozes IA", "Chamadas"};
    for (int i = 0; i < 3; i++) {
      final int n = i;
      Button b = new Button(this);
      b.setText(names[i]);
      b.setTextSize(12);
      b.setAllCaps(false);
      b.setTextColor(index == i ? GREEN : MUTED);
      b.setBackgroundColor(BG);
      nav.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
      b.setOnClickListener(
          v -> {
            if (recording) {
              message("Pare a gravação antes de mudar de tela.");
              return;
            }
            if (inCall && n != 2) {
              message("Use Voz e ambiente na tela da chamada.");
              return;
            }
            render(n);
          });
    }
    page.addView(nav);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(0, 0, 0, dp(20));
    scroll.addView(content);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(page);
    page.requestApplyInsets();
    if (index == 0) studio();
    else if (index == 1) voices();
    else calls();
  }

  private void studio() {
    title("Sua voz. Outro cenário.");
    note("Efeitos locais e mistura de áudio. Sem créditos de geração.");
    LinearLayout b = box();
    wave = new WaveView();
    b.addView(wave, new LinearLayout.LayoutParams(-1, dp(94)));
    recLabel = text("PRONTO PARA GRAVAR", 12, GREEN);
    b.addView(recLabel);
    spinner(
        b,
        new String[] {
          "Original",
          "Mais grave · efeito",
          "Mais aguda · efeito",
          "Robô · efeito",
          "Rádio · efeito"
        },
        audio.dsp.preset,
        p -> audio.dsp.preset = p);
    spinner(
        b,
        new String[] {
          "Sem ambiente",
          "Chuva · sintetizada",
          "Trânsito · sintetizado",
          "Ondas · sintetizadas",
          "Meu ambiente WAV"
        },
        audio.dsp.environment,
        p -> audio.dsp.environment = p);
    slider(
        b,
        "Volume da voz",
        150,
        (int) (audio.dsp.voiceGain * 100),
        p -> audio.dsp.voiceGain = p / 100f);
    slider(
        b,
        "Volume do ambiente",
        70,
        (int) (audio.dsp.environmentGain * 100),
        p -> audio.dsp.environmentGain = p / 100f);
    button(b, "Importar ambiente real · WAV", () -> pick(11), false);
    CheckBox monitor = new CheckBox(this);
    monitor.setText("Ouvir ao vivo · use fones");
    monitor.setTextColor(MUTED);
    b.addView(monitor);
    monitor.setOnCheckedChangeListener(
        (v, checked) -> {
          audio.setMonitor(checked);
          if (checked && !recording && !inCall) startAudio();
          if (!checked && !recording && !inCall) {
            audio.stop();
            abandonAudio();
          }
        });
    recordButton = button(content, "●  Gravar", this::startRecording, true);
    stopButton = button(content, "■  Parar e salvar", this::stopRecording, false);
    stopButton.setEnabled(false);
    button(content, "▶  Ouvir resultado", this::playResult, false);
    button(content, "Exportar WAV", this::exportResult, false);
    button(content, "Importar fala · WAV", () -> pick(12), false);
    note(
        "Os efeitos grave/agudo não imitam uma pessoa real. Para converter timbre por amostra, use"
            + " Vozes IA.");
  }

  private void voices() {
    title("Timbres por amostra");
    note(
        "Integração OpenVoice para gravações. Requer servidor e modelos instalados. Não é conversão"
            + " ao vivo nas chamadas desta versão.");
    LinearLayout b = box();
    b.addView(text("Servidor de voz", 16, INK));
    serverInput = field(b, "https://voz.seudominio.com", server, false);
    tokenInput = field(b, "Chave de acesso do Jarvis", token, true);
    githubInput = field(b, "Token Codespaces · opcional, porta privada", githubToken, true);
    cloneStatus = text("Motor ainda não verificado", 13, MUTED);
    b.addView(cloneStatus);
    button(b, "Verificar motor e listar vozes", this::refreshVoices, true);
    if (!voiceNames.isEmpty())
      spinner(
          b,
          voiceNames.toArray(new String[0]),
          Math.max(0, voiceIds.indexOf(voiceId)),
          p -> voiceId = voiceIds.get(p));
    voiceName = field(b, "Nome da voz", "Minha voz", false);
    button(b, "Cadastrar amostra da fala importada / gravada", this::registerVoice, false);
    button(b, "Converter minha gravação", this::convertVoice, true);
    button(b, "Excluir referência selecionada", this::deleteVoice, false);
    note(
        "Grave ou importe fala limpa no Estúdio. O cadastro usa essa fala como referência. A"
            + " conversão usa a voz selecionada e aplica o ambiente depois.");
    button(content, "Ouvir resultado convertido", this::playResult, false);
    button(content, "Exportar resultado", this::exportResult, false);
  }

  private void calls() {
    title("Chamada pelo Jarvis");
    note(
        "Áudio entre duas pessoas com o app aberto. Efeitos e ambiente do Estúdio são enviados à"
            + " outra pessoa.");
    LinearLayout b = box();
    serverInput = field(b, "https://voz.seudominio.com", server, false);
    tokenInput = field(b, "Chave de acesso do Jarvis", token, true);
    githubInput = field(b, "Token Codespaces · opcional, porta privada", githubToken, true);
    roomInput = field(b, "Código da sala", room, false);
    status = text(inCall ? lastCallState : "DESCONECTADO", 14, GREEN);
    b.addView(status);
    button(b, "Entrar / aguardar na sala", this::joinCall, true);
    button(
        b,
        "Aceitar e escolher voz / ambiente",
        () -> {
          if (call != null) callMenu(() -> call.accept());
          else message("Entre em uma sala primeiro.");
        },
        false);
    button(b, "Voz e ambiente · alterar agora", () -> callMenu(null), false);
    button(b, "Encerrar", this::endCall, false);
    LinearLayout phoneBox = box();
    phoneBox.addView(text("Telefonar para um número", 16, INK));
    phoneInput = field(phoneBox, "+55 DDD número", "", false);
    phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
    button(phoneBox, "Ligar pela central configurada", this::dialPhone, false);
    note(
        "Central opcional: requer conta de telefonia e pode cobrar por minuto. Entre na sala e"
            + " aceite antes de ligar. A clonagem IA ao vivo ainda não está implementada; a ligação"
            + " envia os efeitos locais.");
    note(
        "A outra pessoa usa o mesmo servidor, chave e código da sala. Ambos precisam aceitar. Esta"
            + " versão encerra a sessão ao sair do app.");
    note(
        "Chamadas do chip, WhatsApp e histórico da operadora: não conectados. Usar o app como"
            + " discador padrão não libera a troca de áudio.");
    title("Histórico do Jarvis");
    note(getPreferences(0).getString("history", "Nenhuma chamada registrada neste app."));
  }

  private void saveFields() {
    if (serverInput != null) {
      server = serverInput.getText().toString().trim().replaceAll("/+$", "");
      token = tokenInput.getText().toString().trim();
      getPreferences(0).edit().putString("server", server).apply();
    }
    if (githubInput != null) githubToken = githubInput.getText().toString().trim();
    if (roomInput != null) room = roomInput.getText().toString().trim();
  }

  private boolean endpoint() {
    saveFields();
    try {
      HttpUrl u = HttpUrl.get(server);
      if (!u.isHttps()
          || !u.encodedPath().equals("/")
          || u.query() != null
          || u.username().length() > 0
          || token.length() < 24) throw new Exception();
      return true;
    } catch (Exception e) {
      message("Informe a origem HTTPS (sem caminho) e uma chave de pelo menos 24 caracteres.");
      return false;
    }
  }

  private boolean permission() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 7);
      return false;
    }
    return true;
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] result) {
    super.onRequestPermissionsResult(r, p, result);
    message(
        result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED
            ? "Microfone liberado. Toque novamente para começar."
            : "Permissão de microfone necessária para gravar e chamar.");
  }

  private void startAudio() {
    if (!permission()) return;
    if (playback != null) {
      playback.release();
      playback = null;
    }
    if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      message("Áudio ocupado por outro app.");
      return;
    }
    manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    audio.start();
  }

  private void abandonAudio() {
    manager.setMode(AudioManager.MODE_NORMAL);
    manager.abandonAudioFocusRequest(focus);
  }

  private void startRecording() {
    if (inCall) {
      message("Encerre a chamada antes de gravar.");
      return;
    }
    if (!permission()) return;
    startAudio();
    if (!audio.isRunning()) return;
    audio.beginRecording();
    recording = true;
    recordingStarted = System.currentTimeMillis();
    recordButton.setEnabled(false);
    stopButton.setEnabled(true);
  }

  private void stopRecording() {
    if (!recording) return;
    short[][] samples = audio.endRecording();
    recording = false;
    audio.stop();
    abandonAudio();
    try {
      sourceFile = new File(getFilesDir(), "fala-original.wav");
      resultFile = new File(getFilesDir(), "resultado.wav");
      Wav.write(sourceFile, samples[0], 16000);
      Wav.write(resultFile, samples[1], 16000);
      if (recLabel != null) recLabel.setText("SALVO NO APP · WAV");
    } catch (Exception e) {
      message(e.getMessage());
    }
    if (recordButton != null) recordButton.setEnabled(true);
    if (stopButton != null) stopButton.setEnabled(false);
  }

  private void playResult() {
    if (inCall || recording) {
      message("Pare a sessão antes de ouvir.");
      return;
    }
    if (resultFile == null) {
      message("Grave, importe ou converta uma fala primeiro.");
      return;
    }
    audio.stop();
    abandonAudio();
    try {
      if (playback != null) playback.release();
      playback = new MediaPlayer();
      playback.setDataSource(resultFile.getAbsolutePath());
      playback.prepare();
      playback.start();
    } catch (Exception e) {
      message("Falha ao reproduzir: " + e.getMessage());
    }
  }

  private void exportResult() {
    if (resultFile == null) {
      message("Nenhum resultado para exportar.");
      return;
    }
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.setType("audio/wav");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_TITLE, "Jarvis-Voice-" + System.currentTimeMillis() + ".wav");
    startActivityForResult(i, 13);
  }

  private void pick(int code) {
    if (recording || inCall) {
      message("Pare a sessão antes de importar.");
      return;
    }
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.setType("audio/*");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, code);
  }

  private byte[] readBounded(InputStream stream, int max) throws IOException {
    try (InputStream s = stream;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = s.read(buf)) != -1) {
        if (out.size() + n > max) throw new IOException("Arquivo muito grande (máximo 24 MB)");
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    io.execute(
        () -> {
          try {
            if (request == 13) {
              try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Destino indisponível");
                Files.copy(resultFile.toPath(), out);
              }
              message("WAV exportado.");
              return;
            }
            short[] s =
                Wav.decode(
                    readBounded(getContentResolver().openInputStream(uri), 24 * 1024 * 1024));
            if (request == 11) {
              audio.dsp.imported = s;
              audio.dsp.environment = 4;
              runOnUiThread(() -> render(0));
              message("Ambiente importado.");
            } else if (request == 12) {
              sourceFile = new File(getFilesDir(), "fala-original.wav");
              Wav.write(sourceFile, s, 16000);
              short[] mixed = new short[s.length];
              VoiceDsp dsp = copyDsp();
              dsp.process(s, mixed, s.length);
              resultFile = new File(getFilesDir(), "resultado.wav");
              Wav.write(resultFile, mixed, 16000);
              message("Fala importada e efeitos aplicados.");
            }
          } catch (Exception e) {
            message(e.getMessage());
          }
        });
  }

  private VoiceDsp copyDsp() {
    VoiceDsp d = new VoiceDsp();
    d.preset = audio.dsp.preset;
    d.environment = audio.dsp.environment;
    d.voiceGain = audio.dsp.voiceGain;
    d.environmentGain = audio.dsp.environmentGain;
    d.imported = audio.dsp.imported;
    return d;
  }

  private JSONObject api(String path, JSONObject body) throws Exception {
    Request.Builder r =
        new Request.Builder().url(server + path).header("Authorization", "Bearer " + token);
    if (HttpUrl.get(server).host().endsWith(".app.github.dev") && !githubToken.isEmpty())
      r.header("X-Github-Token", githubToken);
    if (body != null)
      r.post(RequestBody.create(body.toString(), MediaType.get("application/json")));
    try (Response response = http.newCall(r.build()).execute()) {
      String text = response.body() != null ? response.body().string() : "{}";
      JSONObject j = new JSONObject(text);
      if (!response.isSuccessful())
        throw new IOException(j.optString("error", "HTTP " + response.code()));
      return j;
    }
  }

  private void refreshVoices() {
    if (!endpoint()) return;
    busy("Consultando servidor…");
    io.execute(
        () -> {
          try {
            JSONObject h = api("/api/health", null);
            JSONArray list = api("/api/voices", null).getJSONArray("voices");
            ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
              JSONObject v = list.getJSONObject(i);
              ids.add(v.getString("id"));
              names.add(v.getString("name"));
            }
            runOnUiThread(
                () -> {
                  voiceIds.clear();
                  voiceIds.addAll(ids);
                  voiceNames.clear();
                  voiceNames.addAll(names);
                  if (!voiceIds.contains(voiceId))
                    voiceId = voiceIds.isEmpty() ? "" : voiceIds.get(0);
                  render(1);
                  cloneStatus.setText(
                      h.optBoolean("engine_ready")
                          ? "Motor OpenVoice disponível · gravações"
                          : "Servidor conectado · motor IA indisponível");
                });
          } catch (Exception e) {
            busy("Falha: " + e.getMessage());
          }
        });
  }

  private JSONObject sourceBody() throws Exception {
    if (sourceFile == null) throw new IOException("Grave ou importe uma fala primeiro.");
    return new JSONObject()
        .put(
            "audio",
            android.util.Base64.encodeToString(
                Files.readAllBytes(sourceFile.toPath()), android.util.Base64.NO_WRAP));
  }

  private void registerVoice() {
    if (!endpoint()) return;
    String name = voiceName.getText().toString().trim();
    new AlertDialog.Builder(this)
        .setTitle("Referência de voz")
        .setMessage(
            "Use sua própria voz ou uma amostra cujo uso foi autorizado. Enviar a fala atual ao seu"
                + " servidor como referência?")
        .setPositiveButton(
            "Cadastrar",
            (d, w) -> {
              busy("Cadastrando referência…");
              io.execute(
                  () -> {
                    try {
                      JSONObject j = sourceBody().put("name", name).put("authorized", true);
                      api("/api/voices", j);
                      runOnUiThread(this::refreshVoices);
                    } catch (Exception e) {
                      busy("Falha: " + e.getMessage());
                    }
                  });
            })
        .setNegativeButton("Cancelar", null)
        .show();
  }

  private void deleteVoice() {
    if (!endpoint() || voiceId.isEmpty()) return;
    String id = voiceId;
    new AlertDialog.Builder(this)
        .setTitle("Excluir referência?")
        .setMessage("A amostra será removida do seu servidor.")
        .setPositiveButton(
            "Excluir",
            (d, w) ->
                io.execute(
                    () -> {
                      try {
                        Request.Builder rb =
                            new Request.Builder()
                                .url(server + "/api/voices/" + id)
                                .header("Authorization", "Bearer " + token);
                        if (HttpUrl.get(server).host().endsWith(".app.github.dev")
                            && !githubToken.isEmpty()) rb.header("X-Github-Token", githubToken);
                        Request r = rb.delete().build();
                        try (Response response = http.newCall(r).execute()) {
                          if (!response.isSuccessful()) throw new IOException("Falha ao excluir");
                        }
                        runOnUiThread(this::refreshVoices);
                      } catch (Exception e) {
                        busy(e.getMessage());
                      }
                    }))
        .setNegativeButton("Cancelar", null)
        .show();
  }

  private void convertVoice() {
    if (!endpoint()) return;
    if (voiceId.isEmpty()) {
      message("Cadastre e selecione uma referência.");
      return;
    }
    String id = voiceId;
    busy("Convertendo no servidor…");
    io.execute(
        () -> {
          try {
            JSONObject j = api("/api/convert", sourceBody().put("voice_id", id));
            short[] s =
                Wav.decode(
                    android.util.Base64.decode(j.getString("audio"), android.util.Base64.DEFAULT));
            short[] out = new short[s.length];
            VoiceDsp dsp = copyDsp();
            dsp.preset = 0;
            dsp.process(s, out, s.length);
            resultFile = new File(getFilesDir(), "resultado-ia.wav");
            Wav.write(resultFile, out, 16000);
            busy("Convertido. Use Ouvir resultado convertido.");
          } catch (Exception e) {
            busy("Falha: " + e.getMessage());
          }
        });
  }

  private void busy(String s) {
    runOnUiThread(
        () -> {
          if (cloneStatus != null) cloneStatus.setText(s);
          else message(s);
        });
  }

  private void joinCall() {
    if (inCall) {
      message("Você já está em uma sala.");
      return;
    }
    if (!endpoint() || !permission()) return;
    if (!room.matches("[A-Za-z0-9_-]{6,64}")) {
      message("Código: 6 a 64 letras, números, hífen ou sublinhado.");
      return;
    }
    audio.setMonitor(false);
    startAudio();
    if (!audio.isRunning()) return;
    inCall = true;
    selectedVersion = 0;
    beepedVersion = 0;
    outgoingPhone = false;
    callStarted = System.currentTimeMillis();
    call =
        new CallClient(
            new CallClient.Events() {
              public void state(String s) {
                lastCallState = s;
                runOnUiThread(
                    () -> {
                      if (status != null) status.setText(s);
                      if (s.startsWith("Em chamada")) outgoingPhone = false;
                      if (s.startsWith("Outra pessoa") && !menuOpen && !outgoingPhone)
                        callMenu(
                            () -> {
                              if (call != null) call.accept();
                            });
                    });
              }

              public void audio(byte[] b) {
                audio.remoteFrame(b);
              }

              public void ended() {
                runOnUiThread(() -> endCall());
              }

              public void ready(int version, String confirmation) {
                runOnUiThread(
                    () -> {
                      if (inCall && version == selectedVersion && version != beepedVersion) {
                        beepedVersion = version;
                        audio.suppressMicrophone(240);
                        if (readyTone != null)
                          readyTone.startTone(ToneGenerator.TONE_PROP_BEEP, 140);
                        if (status != null)
                          status.setText(
                              confirmation.equals("provider_playback")
                                  ? "✓ Pronto · áudio processado reproduzido no destino"
                                  : "✓ Pronto · ajustes aplicados, transmissão ativa");
                      }
                    });
              }
            });
    call.join(server, token, room, githubToken);
    lastCallState = "Conectando…";
    if (status != null) status.setText(lastCallState);
  }

  private void callMenu(Runnable next) {
    if (!inCall || call == null) {
      message("Entre numa sala primeiro.");
      return;
    }
    if (menuOpen) return;
    menuOpen = true;
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(20), dp(10), dp(20), dp(10));
    panel.setBackgroundColor(CARD);
    panel.addView(
        text(
            "Selecione os ajustes. O bip confirma a transmissão; na central, aguarda a confirmação"
                + " de reprodução do áudio processado.",
            14,
            MUTED));
    int[] values = {audio.dsp.preset, audio.dsp.environment};
    float[] gains = {audio.dsp.voiceGain, audio.dsp.environmentGain};
    spinner(
        panel,
        new String[] {
          "Original",
          "Mais grave · efeito",
          "Mais aguda · efeito",
          "Robô · efeito",
          "Rádio · efeito"
        },
        values[0],
        p -> values[0] = p);
    spinner(
        panel,
        new String[] {
          "Sem ambiente",
          "Chuva · sintetizada",
          "Trânsito · sintetizado",
          "Ondas · sintetizadas",
          "Meu ambiente WAV"
        },
        values[1],
        p -> values[1] = p);
    slider(panel, "Volume da voz", 150, (int) (gains[0] * 100), p -> gains[0] = p / 100f);
    slider(panel, "Volume do ambiente", 70, (int) (gains[1] * 100), p -> gains[1] = p / 100f);
    panel.addView(
        text(
            "Timbres reais clonados ao vivo ainda não estão disponíveis nesta versão.", 12, MUTED));
    new AlertDialog.Builder(this)
        .setTitle("Voz e ambiente da ligação")
        .setView(panel)
        .setPositiveButton(
            "Aplicar e continuar",
            (dialog, which) -> {
              menuOpen = false;
              if (call == null) return;
              if (values[1] == 4 && audio.dsp.imported == null) {
                message("Importe um ambiente WAV no Estúdio antes de ligar.");
                callMenu(next);
                return;
              }
              selectedVersion = audio.configure(values[0], values[1], gains[0], gains[1]);
              call.configure(selectedVersion);
              if (status != null)
                status.setText("Ajustes aplicados · aguardando confirmação do áudio…");
              if (next != null) next.run();
            })
        .setNegativeButton(
            "Cancelar",
            (d, w) -> {
              menuOpen = false;
              if (selectedVersion == 0) endCall();
            })
        .setOnCancelListener(
            d -> {
              menuOpen = false;
              if (selectedVersion == 0) endCall();
            })
        .show();
  }

  private void dialPhone() {
    if (!inCall) {
      message("Entre na sala e aceite antes de telefonar.");
      return;
    }
    if (!endpoint()) return;
    String number = phoneInput.getText().toString().replaceAll("[\\s()-]", "");
    if (!number.matches("\\+[1-9][0-9]{7,14}")) {
      message("Use o formato +55DDDnúmero.");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Ligar pela central?")
        .setMessage(
            "A telefonia configurada pode consumir saldo por minuto. O app enviará sua voz com os"
                + " efeitos e o ambiente selecionados.")
        .setPositiveButton(
            "Ligar",
            (d, w) ->
                callMenu(
                    () -> {
                      outgoingPhone = true;
                      io.execute(
                          () -> {
                            try {
                              api(
                                  "/api/phone/outgoing",
                                  new JSONObject()
                                      .put("number", number)
                                      .put("room", room)
                                      .put("configuration_version", selectedVersion));
                              runOnUiThread(
                                  () -> {
                                    if (status != null) status.setText("Discando pela central…");
                                  });
                            } catch (Exception e) {
                              outgoingPhone = false;
                              message("Central: " + e.getMessage());
                            }
                          });
                    }))
        .setNegativeButton("Cancelar", null)
        .show();
  }

  private void endCall() {
    if (!inCall) return;
    inCall = false;
    CallClient c = call;
    call = null;
    if (c != null) c.close();
    audio.stop();
    abandonAudio();
    long seconds = (System.currentTimeMillis() - callStarted) / 1000;
    String previous = getPreferences(0).getString("history", "");
    String entry =
        new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new Date())
            + " · sessão "
            + seconds
            + " s";
    getPreferences(0)
        .edit()
        .putString(
            "history",
            (entry + "\n" + previous)
                .substring(0, Math.min(2000, entry.length() + 1 + previous.length())))
        .apply();
    lastCallState = "ENCERRADA";
    if (status != null) status.setText(lastCallState);
  }

  private void stopAll() {
    if (recording) stopRecording();
    endCall();
    audio.stop();
    abandonAudio();
    if (playback != null) {
      playback.release();
      playback = null;
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    stopAll();
  }

  @Override
  protected void onDestroy() {
    timer.removeCallbacksAndMessages(null);
    stopAll();
    if (readyTone != null) {
      readyTone.release();
      readyTone = null;
    }
    io.shutdownNow();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
    super.onDestroy();
  }

  private void message(String s) {
    runOnUiThread(
        () -> Toast.makeText(this, s == null ? "Falha inesperada" : s, Toast.LENGTH_LONG).show());
  }

  private final class WaveView extends View {
    volatile float level = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    WaveView() {
      super(MainActivity.this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      paint.setColor(GREEN);
      paint.setStrokeWidth(dp(3));
      paint.setStrokeCap(Paint.Cap.ROUND);
      float center = getHeight() / 2f;
      long t = System.currentTimeMillis();
      for (int i = 0; i < 42; i++) {
        float x = (i + .5f) * getWidth() / 42f;
        float envelope = (float) Math.sin(Math.PI * (i + 1) / 43);
        float h =
            dp(4)
                + (float)
                    (Math.abs(Math.sin(i * 1.73 + t * .005))
                        * level
                        * getHeight()
                        * .42
                        * envelope);
        canvas.drawLine(x, center - h, x, center + h, paint);
      }
    }
  }
}
