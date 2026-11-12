package com.kragnoth.mobrules.editor;

import com.kragnoth.mobrules.MobRules;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A deliberately tiny HTTP/1.1 server on plain sockets (no jdk.httpserver, which some bundled
 * Java runtimes leave out). Loopback only, one request per connection.
 */
final class MiniHttp {
    record Request(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String q(String key, String def) {
            return query.getOrDefault(key, def);
        }
    }

    record Response(int status, String contentType, byte[] body) {
        static Response json(String json) {
            return new Response(200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }

        static Response error(int status, String message) {
            String esc = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            return new Response(status, "application/json; charset=utf-8", ("{\"error\":\"" + esc + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private final ServerSocket socket;
    private final ExecutorService pool;
    private final Function<Request, Response> handler;
    private volatile boolean running = true;

    MiniHttp(int port, Function<Request, Response> handler) throws IOException {
        this.handler = handler;
        ServerSocket s;
        try {
            s = new ServerSocket();
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        } catch (IOException busy) {
            s = new ServerSocket();
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        }
        socket = s;
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "MobRules-Editor");
            t.setDaemon(true);
            return t;
        });
        Thread accept = new Thread(this::acceptLoop, "MobRules-Editor-Accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                pool.execute(() -> serve(client));
            } catch (IOException ex) {
                if (running) MobRules.LOGGER.warn("[Mob Rules] editor accept failed", ex);
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            int len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (len > 32 * 1024 * 1024) {
                write(client.getOutputStream(), Response.error(413, "too large"));
                return;
            }
            byte[] body = in.readNBytes(len);
            String target = parts[1];
            String path = target;
            Map<String, String> query = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int e = kv.indexOf('=');
                    String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), StandardCharsets.UTF_8);
                    String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), StandardCharsets.UTF_8);
                    query.put(k, v);
                }
            }
            Response r;
            try {
                r = handler.apply(new Request(parts[0].toUpperCase(Locale.ROOT), path, query, headers, body));
            } catch (Exception ex) {
                MobRules.LOGGER.warn("[Mob Rules] editor request {} failed", path, ex);
                r = Response.error(500, ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            write(client.getOutputStream(), r);
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private static void write(OutputStream out, Response r) throws IOException {
        String head = "HTTP/1.1 " + r.status() + " " + (r.status() < 400 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + r.contentType() + "\r\n"
                + "Content-Length: " + r.body().length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body());
        out.flush();
    }
}
