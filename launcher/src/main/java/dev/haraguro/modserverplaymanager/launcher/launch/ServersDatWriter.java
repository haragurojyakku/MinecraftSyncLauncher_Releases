package dev.haraguro.modserverplaymanager.launcher.launch;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds/updates an entry in installDir/servers.dat — the game's own
 * Multiplayer server list, an uncompressed NBT file — so the active server
 * shows up there without the player ever using Multiplayer -> Add Server.
 * See {@link GameLauncher}, which calls {@link #addOrUpdate} right before
 * every launch. Implements just enough of the NBT spec (compound, list,
 * byte/string, ...) to round-trip an existing file's other entries and
 * fields (icon, hidden, acceptTextures, ...) untouched.
 */
final class ServersDatWriter {

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private ServersDatWriter() {
    }

    /** One NBT tag: its type id plus the decoded payload — see readPayload/writePayload for the shape per type. */
    private record Tag(int type, Object value) {
    }

    /** TAG_List payload: element type id (meaningless when items is empty) plus the raw element values/compounds. */
    private record NbtList(int elementType, List<Object> items) {
    }

    /**
     * Adds a name/address entry to installDir/servers.dat, or updates the
     * existing entry with that name in place (preserving its other fields).
     * Best-effort: a missing or unparseable file is replaced with a fresh
     * one containing just this entry; any other I/O failure is swallowed
     * since this is a convenience feature, not required for Play to work.
     */
    static void addOrUpdate(Path installDir, String name, String address) {
        Path file = installDir.resolve("servers.dat");
        Map<String, Tag> root;
        try {
            root = Files.exists(file) ? readRoot(file) : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            root = new LinkedHashMap<>();
        }

        Tag serversTag = root.get("servers");
        List<Object> entries = (serversTag != null && serversTag.value() instanceof NbtList list && list.elementType() == TAG_COMPOUND)
                ? new ArrayList<>(list.items())
                : new ArrayList<>();

        Map<String, Tag> match = null;
        for (Object entry : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Tag> compound = (Map<String, Tag>) entry;
            Tag nameTag = compound.get("name");
            if (nameTag != null && name.equals(nameTag.value())) {
                match = compound;
                break;
            }
        }

        if (match != null) {
            match.put("ip", new Tag(TAG_STRING, address));
        } else {
            Map<String, Tag> newEntry = new LinkedHashMap<>();
            newEntry.put("ip", new Tag(TAG_STRING, address));
            newEntry.put("name", new Tag(TAG_STRING, name));
            entries.add(newEntry);
        }

        root.put("servers", new Tag(TAG_LIST, new NbtList(TAG_COMPOUND, entries)));

        try {
            Files.createDirectories(installDir);
            writeRoot(file, root);
        } catch (IOException ignored) {
            // Best-effort -- the launcher's own server-selection flow doesn't depend on this file.
        }
    }

    private static Map<String, Tag> readRoot(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int rootType = in.readUnsignedByte();
            in.readUTF(); // root name, normally ""
            if (rootType != TAG_COMPOUND) {
                throw new IOException("servers.dat root is not a compound (type " + rootType + ")");
            }
            return readCompound(in);
        }
    }

    private static void writeRoot(Path file, Map<String, Tag> root) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            writeCompound(out, root);
        }
    }

    private static Map<String, Tag> readCompound(DataInputStream in) throws IOException {
        Map<String, Tag> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, new Tag(type, readPayload(in, type)));
        }
    }

    private static void writeCompound(DataOutputStream out, Map<String, Tag> map) throws IOException {
        for (Map.Entry<String, Tag> entry : map.entrySet()) {
            Tag tag = entry.getValue();
            out.writeByte(tag.type());
            out.writeUTF(entry.getKey());
            writePayload(out, tag.type(), tag.value());
        }
        out.writeByte(TAG_END);
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                return bytes;
            }
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elementType = in.readUnsignedByte();
                int length = in.readInt();
                List<Object> items = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    items.add(readPayload(in, elementType));
                }
                return new NbtList(elementType, items);
            }
            case TAG_COMPOUND:
                return readCompound(in);
            case TAG_INT_ARRAY: {
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case TAG_LONG_ARRAY: {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("Unsupported NBT tag type " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, int type, Object value) throws IOException {
        switch (type) {
            case TAG_BYTE -> out.writeByte((Byte) value);
            case TAG_SHORT -> out.writeShort((Short) value);
            case TAG_INT -> out.writeInt((Integer) value);
            case TAG_LONG -> out.writeLong((Long) value);
            case TAG_FLOAT -> out.writeFloat((Float) value);
            case TAG_DOUBLE -> out.writeDouble((Double) value);
            case TAG_BYTE_ARRAY -> {
                byte[] bytes = (byte[]) value;
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case TAG_STRING -> out.writeUTF((String) value);
            case TAG_LIST -> {
                NbtList list = (NbtList) value;
                out.writeByte(list.elementType());
                out.writeInt(list.items().size());
                for (Object item : list.items()) {
                    writePayload(out, list.elementType(), item);
                }
            }
            case TAG_COMPOUND -> writeCompound(out, (Map<String, Tag>) value);
            case TAG_INT_ARRAY -> {
                int[] ints = (int[]) value;
                out.writeInt(ints.length);
                for (int i : ints) {
                    out.writeInt(i);
                }
            }
            case TAG_LONG_ARRAY -> {
                long[] longs = (long[]) value;
                out.writeInt(longs.length);
                for (long l : longs) {
                    out.writeLong(l);
                }
            }
            default -> throw new IOException("Unsupported NBT tag type " + type);
        }
    }
}
