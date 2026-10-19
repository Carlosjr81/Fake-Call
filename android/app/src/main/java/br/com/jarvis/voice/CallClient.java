package br.com.jarvis.voice;

import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

public final class CallClient extends WebSocketListener {
  public interface Events {
    void state(String s);

    void audio(byte[] b);

    void ended();

    void ready(int version, String confirmation);
  }

  private final OkHttpClient http =
      new OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build();
  private WebSocket socket;
  private final Events events;
  private volatile boolean active = false;
  private volatile int configVersion = 0;

  public CallClient(Events events) {
    this.events = events;
  }

  public void join(String base, String token, String room, String githubToken) {
    HttpUrl url =
        HttpUrl.get(base).newBuilder().addPathSegment("signal").addPathSegment(room).build();
    String ws = url.toString().replaceFirst("https:", "wss:");
    Request.Builder request =
        new Request.Builder().url(ws).header("Authorization", "Bearer " + token);
    if (url.host().endsWith(".app.github.dev") && !githubToken.isEmpty())
      request.header("X-Github-Token", githubToken);
    socket = http.newWebSocket(request.build(), this);
  }

  @Override
  public void onMessage(WebSocket w, String text) {
    try {
      JSONObject j = new JSONObject(text);
      String type = j.optString("type");
      if (type.equals("active")) {
        active = true;
        events.state("Em chamada · voz com efeitos locais");
      } else if (type.equals("waiting")) {
        active = false;
        events.state("Aguardando outra pessoa nesta sala");
      } else if (type.equals("ringing")) {
        active = false;
        events.state("Outra pessoa conectada · ambos devem aceitar");
      } else if (type.equals("ended")) {
        active = false;
        events.state("A outra pessoa saiu");
        events.ended();
      } else if (type.equals("ready") && j.optInt("version") == configVersion)
        events.ready(configVersion, j.optString("confirmation"));
      else if (type.equals("error")) events.state(j.optString("message", "Falha na chamada"));
    } catch (Exception e) {
      events.state("Resposta inválida do servidor");
    }
  }

  @Override
  public void onMessage(WebSocket w, ByteString b) {
    if (active) events.audio(b.toByteArray());
  }

  @Override
  public void onFailure(WebSocket w, Throwable t, Response response) {
    active = false;
    events.state(
        "Conexão encerrada: "
            + (response != null ? response.code() : t.getClass().getSimpleName()));
    events.ended();
  }

  @Override
  public void onClosed(WebSocket w, int code, String reason) {
    active = false;
    events.ended();
  }

  public void accept() {
    if (socket != null) socket.send("{\"type\":\"accept\"}");
  }

  public void configure(int version) {
    configVersion = version;
    if (socket != null) socket.send("{\"type\":\"configure\",\"version\":" + version + "}");
  }

  public void send(byte[] b, int version) {
    WebSocket s = socket;
    if (active && configVersion > 0 && version == configVersion && s != null) {
      if (s.queueSize() > 16000) {
        s.cancel();
        return;
      }
      byte[] packet =
          java.nio.ByteBuffer.allocate(b.length + 6)
              .put((byte) 'J')
              .put((byte) 'V')
              .putInt(version)
              .put(b)
              .array();
      s.send(ByteString.of(packet));
    }
  }

  public void close() {
    active = false;
    if (socket != null) {
      socket.close(1000, "hangup");
      socket = null;
    }
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
