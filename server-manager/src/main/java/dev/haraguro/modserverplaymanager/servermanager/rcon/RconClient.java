package dev.haraguro.modserverplaymanager.servermanager.rcon;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal client for the Source RCON protocol (the same one Minecraft's
 * server uses) — https://developer.valvesoftware.com/wiki/Source_RCON_Protocol.
 * The protocol is a handful of well-known, unchanged-since-2004 packet
 * types, so this hand-rolled ~150 lines is more stable long-term than
 * pulling in a Maven dependency for it.
 *
 * Each call site is expected to open a short-lived connection (connect,
 * authenticate, run a couple of commands, close) rather than holding one
 * open for the process lifetime — simpler, and avoids idle-socket edge
 * cases across a long-running server-manager process.
 */
public class RconClient implements AutoCloseable {

    private static final int TYPE_RESPONSE_VALUE = 0;
    private static final int TYPE_EXEC_COMMAND = 2;
    private static final int TYPE_AUTH_RESPONSE = 2; // same wire value as EXEC_COMMAND; distinguished by call order
    private static final int TYPE_AUTH = 3;

    /** Minecraft truncates RCON responses well under this; a larger-than-expected packet means protocol desync. */
    private static final int MAX_PACKET_SIZE = 1 << 16;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int nextRequestId = 1;

    private RconClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Connects to RCON on localhost (server-manager always runs alongside the server it supervises) and authenticates. */
    public static RconClient connectAndAuthenticate(int port, String password, Duration timeout) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), toMillis(timeout));
        socket.setSoTimeout(toMillis(timeout));
        RconClient client = new RconClient(socket);
        try {
            client.authenticate(password);
        } catch (IOException e) {
            client.close();
            throw e;
        }
        return client;
    }

    private void authenticate(String password) throws IOException {
        int requestId = nextRequestId++;
        writePacket(requestId, TYPE_AUTH, password);

        // Many Source-RCON servers (Minecraft included) send an empty
        // SERVERDATA_RESPONSE_VALUE packet immediately before the real
        // SERVERDATA_AUTH_RESPONSE — skip it if present.
        Packet packet = readPacket();
        if (packet.type() == TYPE_RESPONSE_VALUE) {
            packet = readPacket();
        }

        if (packet.type() != TYPE_AUTH_RESPONSE || packet.requestId() == -1) {
            throw new RconException("RCON authentication failed — check rcon.password in server.properties");
        }
    }

    /** Sends a command and returns the server's response text. */
    public String execute(String command) throws IOException {
        int requestId = nextRequestId++;
        writePacket(requestId, TYPE_EXEC_COMMAND, command);
        Packet response = readPacket();
        if (response.requestId() != requestId) {
            throw new RconException("RCON response id mismatch (expected " + requestId + ", got " + response.requestId() + ")");
        }
        return response.body();
    }

    private void writePacket(int requestId, int type, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        // payload = id(4) + type(4) + body + 2 null terminators
        int payloadLength = 4 + 4 + bodyBytes.length + 2;
        ByteBuffer buffer = ByteBuffer.allocate(4 + payloadLength).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(payloadLength);
        buffer.putInt(requestId);
        buffer.putInt(type);
        buffer.put(bodyBytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    private Packet readPacket() throws IOException {
        int length = ByteBuffer.wrap(readFully(4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > MAX_PACKET_SIZE) {
            throw new RconException("invalid RCON packet length " + length + " (protocol desync?)");
        }
        ByteBuffer payload = ByteBuffer.wrap(readFully(length)).order(ByteOrder.LITTLE_ENDIAN);
        int requestId = payload.getInt();
        int type = payload.getInt();
        byte[] bodyBytes = new byte[length - 4 - 4 - 2];
        payload.get(bodyBytes);
        // trailing 2 null bytes intentionally left unread
        return new Packet(requestId, type, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private byte[] readFully(int count) throws IOException {
        byte[] buffer = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = in.read(buffer, offset, count - offset);
            if (read < 0) {
                throw new EOFException("RCON connection closed mid-packet");
            }
            offset += read;
        }
        return buffer;
    }

    private static int toMillis(Duration timeout) {
        return Math.toIntExact(timeout.toMillis());
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private record Packet(int requestId, int type, String body) {
    }
}
