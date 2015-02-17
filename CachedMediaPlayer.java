/*
 The MIT License (MIT)

Copyright (c) 2015 vydd

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

import android.media.MediaPlayer;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;


public class CachedMediaPlayer extends MediaPlayer {
    private static final String TAG = "CachedMediaPlayer";

    private Proxy proxy;
    private Thread proxyThread;

    public CachedMediaPlayer() {
        super();
        proxy = new Proxy();
        proxyThread = new Thread(proxy);
        proxyThread.start();
    }

    public CachedMediaPlayer(int proxyBufferSize, int proxyConnectTimeout, int proxyReadTimeout) {
        super();
        proxy = new Proxy(proxyBufferSize, proxyConnectTimeout, proxyReadTimeout);
        proxyThread = new Thread(proxy);
        proxyThread.start();
    }

    public void setCacheDir(String path) {
        proxy.setCacheDir(path);
        File dir = new File(path);
        dir.mkdirs();
    }

    public void setDataSource(String path) throws IOException {
        super.setDataSource(String.format(Locale.US, "http://127.0.0.1:%d/%s", proxy.getPort(), path));
    }

    /**
     * INTERNAL PROXY IMPLEMENTATION
     */

    private class Proxy implements Runnable {
        private Selector selector;
        private ServerSocketChannel serverChannel;
        private int port;
        private ByteBuffer buffer;
        private byte[] bytes;
        private String cacheDir;

        static private final int DEFAULT_BUFFER_SIZE = 1024 * 64;
        static private final int DEFAULT_CONNECT_TIMEOUT = 3000;
        static private final int DEFAULT_READ_TIMEOUT = 3000;

        private int BUFFER_SIZE;
        private int CONNECT_TIMEOUT;
        private int READ_TIMEOUT;

        public Proxy() {
            this(DEFAULT_BUFFER_SIZE, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
        }

        public Proxy(int bufferSize, int connectTimeout, int readTimeout) {
            BUFFER_SIZE = bufferSize;
            CONNECT_TIMEOUT = connectTimeout;
            READ_TIMEOUT = readTimeout;

            buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            bytes = new byte[buffer.capacity()];
            try {
                selector = Selector.open();
                serverChannel = ServerSocketChannel.open();
                serverChannel.socket().bind(null);
                port = serverChannel.socket().getLocalPort();
                serverChannel.configureBlocking(false);
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            } catch (IOException e) {
                Log.e(TAG, "Proxy initialization failed.");
            }
        }

        public void setCacheDir(String cacheDir) {
            this.cacheDir = cacheDir;
        }

        public int getPort() {
            return port;
        }

        @Override
        public void run() {
            while (!Thread.interrupted()) {
                try {
                    selector.select();
                    Set<SelectionKey> selected = selector.selectedKeys();
                    if (selected.isEmpty()) {
                        continue;
                    }
                    Iterator<SelectionKey> it = selected.iterator();
                    while (it.hasNext()) {
                        SelectionKey key = (SelectionKey) it.next();
                        if (key.isAcceptable()) {
                            accept(key);
                        } else if (key.isReadable()) {
                            process(key);
                        }
                    }
                    selected.clear();
                } catch (IOException e) {
                    Log.e(TAG, "Proxy died.");
                }
            }
            try {
                selector.close();
                serverChannel.close();
            } catch (IOException e) {
                Log.e(TAG, "Proxy cleanup failed.");
            }
        }

        private void accept(SelectionKey key) throws IOException {
            SocketChannel channel = serverChannel.accept();
            if (channel != null) {
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ);
            }
        }

        private String buildStringFromRequest(SelectionKey key) throws IOException {
            StringBuilder builder = new StringBuilder();
            SocketChannel socketChannel = (SocketChannel) key.channel();
            buffer.clear();
            while (socketChannel.read(buffer) > 0) {
                buffer.flip();
                byte[] dst = new byte[buffer.limit()];
                buffer.get(dst);
                builder.append(new String(dst));
                buffer.clear();
            }
            return builder.toString();
        }

        private ByteArrayInputStream buildResponseHeadersStream(HttpURLConnection conn) {
            String protocol = conn.getHeaderField(null);
            StringBuilder builder = new StringBuilder();
            builder.append(protocol + "\r\n");
            for (String key: conn.getHeaderFields().keySet()) {
                if (key != null) {
                    builder.append(key + ": " + conn.getHeaderField(key) + "\r\n");
                }
            }
            builder.append("\r\n");
            return new ByteArrayInputStream(builder.toString().getBytes());
        }


        private void process(SelectionKey key) throws IOException {
            GetRequest request = new GetRequest(buildStringFromRequest(key));

            if (new File(cacheDir + "/" + request.getHash()).exists()) {
                write((SocketChannel) key.channel(), new FileInputStream(cacheDir + "/" + request.getHash()), null);
            } else {
                HttpURLConnection conn = (HttpURLConnection) request.getUrl().openConnection();
                for (String hkey: request.getHeaders().keySet()) {
                    conn.setRequestProperty(hkey, request.getHeaders().get(hkey));
                }
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.connect();

                FileOutputStream fos = new FileOutputStream((cacheDir + "/" + request.getHash()));

                write((SocketChannel) key.channel(), buildResponseHeadersStream(conn), fos);
                write((SocketChannel) key.channel(), conn.getInputStream(), fos);

                fos.close();
                conn.disconnect();
            }

            key.channel().close();
            key.cancel();
        }

        private void write(SocketChannel channel, InputStream stream, FileOutputStream fos) {
            int n = 0;
            try {
                while (-1 != (n = stream.read(bytes))) {
                    if (fos != null) {
                        fos.write(bytes, 0, n);
                    }
                    buffer.clear();
                    buffer.put(bytes, 0, n);
                    buffer.flip();

                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                };
            } catch (IOException e) {
                Log.e(TAG, "Write to channel/cache failed.");
            } finally {
                try {
                    stream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Could not close the stream.");
                }
            }
        }



        private class GetRequest {
            private HashMap<String, String> headers;
            private URL url;
            private String requestHash;

            public GetRequest(String request) {
                StringBuilder builder = new StringBuilder();
                headers = new HashMap<String, String>();
                for (String line: request.split("\r\n")) {
                    if (line.startsWith("GET")) {
                        try {
                            url = new URL(line.split(" ")[1].substring(1));
                        } catch (MalformedURLException e) {
                            Log.e(TAG, "CachedMediaPlayer data source URL is malformed.");
                            url = null;
                        }
                        builder.append(line);
                    } else {
                        String[] parts = line.split(":", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            if (!key.equals("Host")) {
                                builder.append(value);
                            }
                            headers.put(key, value);
                        }
                    }
                }
                requestHash = md5(builder.toString());
            }

            public String md5(String s) {
                try {
                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    digest.update(s.getBytes());
                    byte messageDigest[] = digest.digest();

                    StringBuffer hex = new StringBuffer();
                    for (int i = 0; i < messageDigest.length; ++i)
                        hex.append(Integer.toHexString(0xFF & messageDigest[i]));
                    return hex.toString();

                } catch (NoSuchAlgorithmException e) {
                    Log.wtf(TAG, "No md5 support. Results unpredictable.");
                }
                return "";
            }

            public HashMap<String, String> getHeaders() { return headers; }
            public URL getUrl() { return url; }
            public String getHash() { return requestHash; }
        }
    }
}
