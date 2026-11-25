package com.gathin.tvhome;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A page on the TV's own address, open only while Screensaver is on screen.
 * The phone scans a QR code, then sends jpeg, png, or webp files. The token in the
 * address is new each time the screen opens, and the page is gone when it closes.
 */
final class PhotoServer {
    private static final String TAG = "TvHome";
    private static final int MAX_PHOTO = 25 * 1024 * 1024;
    /** What the phone may fill the folder up to, and what the TV keeps free for itself. */
    private static final long MAX_FOLDER = 512L * 1024 * 1024;
    private static final long KEEP_FREE = 256L * 1024 * 1024;

    interface Ready {
        void up(String url);
        void down();
    }

    private final File folder;
    private final String token;
    private final Ready ready;
    private final AtomicInteger saved = new AtomicInteger();
    private volatile ServerSocket socket;
    private Thread thread;
    private volatile ThreadPoolExecutor clients;

    PhotoServer(File folder, Ready ready) {
        this.folder = folder;
        this.ready = ready;
        byte[] bits = new byte[8];
        new SecureRandom().nextBytes(bits);
        StringBuilder hex = new StringBuilder();
        for (byte bit : bits) hex.append(String.format(Locale.US, "%02x", bit));
        token = hex.toString();
    }

    void start() {
        thread = new Thread(this::run, "photo-server");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        ServerSocket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (Exception ignored) {
            }
        }
        ThreadPoolExecutor pool = clients;
        if (pool != null) pool.shutdownNow();
    }

    private void run() {
        String ip = lanIp();
        if (ip == null || folder == null || (!folder.isDirectory() && !folder.mkdirs())) {
            ready.down();
            return;
        }
        ServerSocket server = null;
        int port = -1;
        for (int candidate = 8765; candidate < 8775 && server == null; candidate++) {
            try {
                ServerSocket opened = new ServerSocket();
                opened.setReuseAddress(true);
                opened.bind(new InetSocketAddress(ip, candidate));
                server = opened;
                port = candidate;
            } catch (Exception ignored) {
            }
        }
        if (server == null) {
            ready.down();
            return;
        }
        socket = server;
        // A few connections at once, so one that sends nothing cannot hold the page for everyone else.
        // Past the small queue a connection is closed rather than kept waiting.
        clients = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(6), r -> {
            Thread worker = new Thread(r, "photo-client");
            worker.setDaemon(true);
            return worker;
        });
        ready.up("http://" + ip + ":" + port + "/p/" + token);
        try {
            while (!server.isClosed()) {
                Socket client;
                try {
                    client = server.accept();
                } catch (Exception e) {
                    if (server.isClosed()) break;
                    Log.w(TAG, "photo page " + e.getMessage());
                    continue;
                }
                try {
                    clients.execute(() -> serve(client));
                } catch (RejectedExecutionException e) {
                    close(client);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "photo server " + e.getMessage());
        } finally {
            clients.shutdownNow();
        }
    }

    private void serve(Socket client) {
        try {
            client.setSoTimeout(20000);
            handle(client);
        } catch (Exception e) {
            Log.w(TAG, "photo page " + e.getMessage());
        } finally {
            close(client);
        }
    }

    private static void close(Socket client) {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }

    private void handle(Socket client) throws Exception {
        InputStream in = new BufferedInputStream(client.getInputStream(), 16384);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (head.size() < 16384 && matched < 4) {
            int b = in.read();
            if (b < 0) return;
            head.write(b);
            matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        if (matched < 4) return;
        String header = new String(head.toByteArray(), StandardCharsets.US_ASCII);
        int line = header.indexOf("\r\n");
        if (line < 0) return;
        String[] request = header.substring(0, line).split(" ");
        if (request.length < 2 || !request[1].equals("/p/" + token)) {
            respond(client, 404, "text/plain", "Not found");
            return;
        }
        if ("GET".equals(request[0])) {
            respond(client, 200, "text/html; charset=utf-8", page());
            return;
        }
        if (!"POST".equals(request[0])) {
            respond(client, 405, "text/plain", "Use GET or POST");
            return;
        }
        int length = contentLength(header);
        if (length < 32 || length > MAX_PHOTO) {
            respond(client, 413, "text/plain", "That photo is too large");
            return;
        }
        if (!roomFor(length)) {
            respond(client, 507, "text/plain", "The TV has no room for more photos");
            return;
        }
        byte[] magic = new byte[12];
        int got = readFully(in, magic, magic.length);
        String ext = kind(magic, got);
        if (ext == null) {
            respond(client, 415, "text/plain", "Use a jpeg, png, or webp");
            return;
        }
        File dest = new File(folder, "phone-" + System.currentTimeMillis() + "-" + saved.getAndIncrement() + ext);
        File part = new File(dest.getPath() + ".part");
        long written = got;
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(magic, 0, got);
            byte[] buf = new byte[16384];
            while (written < length) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, length - written));
                if (n < 0) break;
                out.write(buf, 0, n);
                written += n;
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        }
        if (written != length || !part.renameTo(dest)) {
            part.delete();
            respond(client, written != length ? 400 : 500, "text/plain", "Could not save");
            return;
        }
        respond(client, 200, "text/plain", "ok");
    }

    /** Whether one more photo of this size keeps the folder under its cap and the TV's storage free. */
    private boolean roomFor(long length) {
        if (folder.getUsableSpace() - length < KEEP_FREE) return false;
        long used = 0;
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) used += file.length();
        return used + length <= MAX_FOLDER;
    }

    private static int readFully(InputStream in, byte[] into, int want) throws Exception {
        int off = 0;
        while (off < want) {
            int n = in.read(into, off, want - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static int contentLength(String header) {
        for (String line : header.split("\r\n")) {
            if (line.length() > 15 && line.substring(0, 15).equalsIgnoreCase("Content-Length:")) {
                try {
                    return Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** The extension for a jpeg, png, or webp body, or null when it is something else. */
    private static String kind(byte[] body, int length) {
        if (length >= 3 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8 && (body[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (length >= 8 && body[0] == (byte) 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G') {
            return ".png";
        }
        if (length >= 12 && body[0] == 'R' && body[1] == 'I' && body[2] == 'F' && body[3] == 'F'
                && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    private static void respond(Socket client, int status, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String text = status == 200 ? "OK" : "No";
        byte[] head = ("HTTP/1.1 " + status + " " + text + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        OutputStream out = client.getOutputStream();
        out.write(head);
        out.write(bytes);
        out.flush();
    }

    private static String page() {
        return """
                <!doctype html>
                <meta name=viewport content="width=device-width,initial-scale=1">
                <title>TV photos</title>
                <style>
                body{font-family:sans-serif;background:#14181e;color:#fff;margin:0;padding:28px}
                p{color:#d7d7d7;font-size:17px;line-height:1.4}
                label{display:block;background:#fff;color:#14181e;text-align:center;padding:18px;border-radius:14px;font-size:18px;margin-top:20px}
                input{display:none}
                #status{margin-top:18px;font-size:17px}
                </style>
                <h1>Add photos</h1>
                <p>They stay on this TV and play in the screensaver. Jpeg, png, or webp.</p>
                <label>Choose photos<input id=files type=file accept="image/jpeg,image/png,image/webp" multiple></label>
                <div id=status></div>
                <script>
                const files=document.getElementById('files'), status=document.getElementById('status');
                files.onchange=async()=>{
                  const list=[...files.files];
                  let added=0;
                  for (const file of list) {
                    status.textContent='Sending '+(added+1)+' of '+list.length;
                    try {
                      const response=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':file.type||'application/octet-stream'},body:file});
                      if (!response.ok) { status.textContent='Could not add '+file.name; return; }
                    } catch (e) { status.textContent='The TV screen was closed. Scan the code again.'; return; }
                    added++;
                  }
                  status.textContent=added===1?'Added 1 photo':'Added '+added+' photos';
                  files.value='';
                };
                </script>
                """;
    }

    /** A site-local IPv4 address, preferring the home network over a tunnel. */
    private static String lanIp() {
        String other = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                String name = iface.getName().toLowerCase(Locale.US);
                if (name.startsWith("tun") || name.startsWith("wg") || name.contains("docker")) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) continue;
                    String host = address.getHostAddress();
                    if (host != null && host.startsWith("192.168.")) return host;
                    if (other == null) other = host;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return other;
    }
}
