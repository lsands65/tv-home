package com.gathin.tvhome;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The TV's video player checks certificates in the system process, which does not trust Apple's
 * CDN. This forwards one clip from that CDN to 127.0.0.1, including the range requests the player
 * uses to find the end of a movie file.
 */
final class AerialProxy {

    private final ServerSocket server;
    private final ExecutorService pool;
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile String upstream;
    private volatile boolean closed;

    AerialProxy() throws Exception {
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        pool = Executors.newCachedThreadPool();
        pool.execute(this::acceptLoop);
    }

    String urlFor(String httpsUrl) {
        upstream = httpsUrl;
        return "http://127.0.0.1:" + server.getLocalPort() + "/a/" + sequence.incrementAndGet();
    }

    void close() {
        closed = true;
        try {
            server.close();
        } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                String target = upstream;
                pool.execute(() -> serve(client, target));
            } catch (Exception e) {
                if (closed || server.isClosed()) return;
            }
        }
    }

    private void serve(Socket client, String target) {
        HttpURLConnection conn = null;
        try {
            client.setSoTimeout(20000);
            String header = readHeader(client.getInputStream());
            client.setSoTimeout(0);
            if (header == null || target == null || !target.startsWith("https://")) {
                writeStatus(client, 404);
                return;
            }
            boolean head = header.startsWith("HEAD ");
            String range = rangeHeader(header);
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "TvHome");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) conn.setRequestProperty("Range", range);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                Log.w("TvHome", "aerial fetch HTTP " + code);
                writeStatus(client, 502);
                return;
            }
            String type = conn.getContentType() != null ? conn.getContentType() : "video/quicktime";
            long length = conn.getContentLengthLong();
            String contentRange = conn.getHeaderField("Content-Range");
            StringBuilder response = new StringBuilder();
            response.append("HTTP/1.1 ").append(code);
            response.append(code == HttpURLConnection.HTTP_PARTIAL ? " Partial Content" : " OK");
            response.append("\r\nContent-Type: ").append(type).append("\r\n");
            if (length >= 0) response.append("Content-Length: ").append(length).append("\r\n");
            if (contentRange != null) response.append("Content-Range: ").append(contentRange).append("\r\n");
            response.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            OutputStream out = client.getOutputStream();
            out.write(response.toString().getBytes(StandardCharsets.US_ASCII));
            if (!head) {
                try (InputStream body = conn.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = body.read(buf)) >= 0) out.write(buf, 0, n);
                }
            }
            out.flush();
        } catch (Throwable ignored) {
            // The player closes its side when it seeks or the dream stops. Errors stay on this
            // thread: an uncaught one would take the whole launcher down with the screensaver.
        } finally {
            if (conn != null) conn.disconnect();
            try {
                client.close();
            } catch (Exception ignored) { }
        }
    }

    private static String readHeader(InputStream in) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            header.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) return new String(header.toByteArray(), StandardCharsets.US_ASCII);
            if (header.size() > 8192) return null;
        }
        return null;
    }

    private static String rangeHeader(String header) {
        for (String line : header.split("\r\n")) {
            if (line.regionMatches(true, 0, "Range:", 0, 6)) return line.substring(6).trim();
        }
        return null;
    }

    private static void writeStatus(Socket client, int code) throws Exception {
        String reason = code == 404 ? "Not Found" : "Bad Gateway";
        String body = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        client.getOutputStream().write(body.getBytes(StandardCharsets.US_ASCII));
    }
}
