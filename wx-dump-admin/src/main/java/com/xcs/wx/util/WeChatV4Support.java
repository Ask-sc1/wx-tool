package com.xcs.wx.util;

import cn.hutool.core.util.HexUtil;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 微信 4.x（WCDB / SQLCipher 4）兼容支持。
 *
 * <p>微信 4.1.11 以后不再在内存中直接缓存可扫描的 {@code x'<key><salt>'}，
 * 而是将它 XOR 掩码后放进 {@code com.Tencent.WCDB.Config.Cipher} 对象。
 * 本类只读取并校验密钥，不修改微信原始数据库。</p>
 */
@Slf4j
public final class WeChatV4Support {

    private static final int PAGE_SIZE = 4096;
    private static final int SALT_SIZE = 16;
    private static final int RESERVE_SIZE = 80;
    private static final int HMAC_SIZE = 64;
    private static final int IV_SIZE = 16;
    private static final int IV_OFFSET = PAGE_SIZE - RESERVE_SIZE;
    private static final int KEY_SIZE = 32;
    private static final int WAL_HEADER_SIZE = 32;
    private static final int WAL_FRAME_SIZE = 24 + PAGE_SIZE;

    private static final byte[] SQLITE_HEADER =
            "SQLite format 3\u0000".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONFIG_CIPHER_NAME =
            "com.Tencent.WCDB.Config.Cipher".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONFIG_CIPHER_XOR_MASK = HexUtil.decodeHex(
            "d2c7442458020000004889442450488b"
                    + "450048844c2448488944254048584c24");
    private static final Pattern CONFIG_CIPHER_PATTERN =
            Pattern.compile("[xX]'([0-9a-fA-F]{64,192})'");
    private static final Pattern LEGACY_KEY_PATTERN =
            Pattern.compile("x'([0-9a-fA-F]{64,192})'");
    private static final Pattern ACCOUNT_PATTERN =
            Pattern.compile("wxid_[A-Za-z0-9_-]{6,80}");

    private static final long MAX_USER_ADDRESS = 0x0000_8000_0000_0000L;
    private static final int MEM_COMMIT = 0x1000;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final Set<Integer> READABLE_PROTECTIONS = new HashSet<>(Arrays.asList(
            WinNT.PAGE_READONLY,
            WinNT.PAGE_READWRITE,
            WinNT.PAGE_WRITECOPY,
            WinNT.PAGE_EXECUTE_READ,
            WinNT.PAGE_EXECUTE_READWRITE,
            0x80
    ));

    private WeChatV4Support() {
    }

    public static boolean isVersion4(String version) {
        if (version == null || version.trim().isEmpty()) {
            return false;
        }
        try {
            return Integer.parseInt(version.trim().split("\\.")[0]) >= 4;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 根据微信 4.x 的配置定位当前账号目录，目录内包含 db_storage。
     */
    public static Path findAccountDirectory() throws IOException {
        List<Path> roots = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path configDir = Paths.get(appData, "Tencent", "xwechat", "config");
            if (Files.isDirectory(configDir)) {
                try (Stream<Path> files = Files.list(configDir)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".ini"))
                            .forEach(path -> {
                                try {
                                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                                    if (!lines.isEmpty()) {
                                        Path root = Paths.get(lines.get(0).trim(), "xwechat_files");
                                        if (Files.isDirectory(root)) {
                                            roots.add(root);
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug("Failed to read WeChat data root from {}", path, e);
                                }
                            });
                }
            }
        }
        String home = System.getProperty("user.home");
        roots.add(Paths.get(home, "Documents", "xwechat_files"));

        List<Path> accounts = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(path -> path.getFileName().toString().startsWith("wxid_"))
                        .filter(path -> Files.isDirectory(path.resolve("db_storage")))
                        .forEach(accounts::add);
            }
        }
        if (accounts.isEmpty()) {
            throw new IOException("未找到微信 4.x 账号目录（xwechat_files/*/db_storage）");
        }
        accounts.sort(Comparator.comparingLong(WeChatV4Support::lastDatabaseActivity).reversed());
        return accounts.get(0);
    }

    private static long lastDatabaseActivity(Path accountDir) {
        long latest = 0L;
        Path sessionDb = accountDir.resolve("db_storage").resolve("session").resolve("session.db");
        if (Files.exists(sessionDb)) {
            try {
                latest = Files.getLastModifiedTime(sessionDb).toMillis();
            } catch (IOException ignored) {
            }
        }
        if (latest > 0) {
            return latest;
        }
        Path messageDir = accountDir.resolve("db_storage").resolve("message");
        if (Files.isDirectory(messageDir)) {
            try (Stream<Path> files = Files.list(messageDir)) {
                return files.filter(path -> path.getFileName().toString().endsWith(".db"))
                        .map(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toMillis();
                            } catch (IOException ignored) {
                                return 0L;
                            }
                        })
                        .max(Long::compareTo)
                        .orElse(0L);
            } catch (IOException ignored) {
            }
        }
        return latest;
    }

    public static String accountId(Path accountDir) {
        String directoryName = accountDir.getFileName().toString();
        return directoryName.replaceFirst("_[0-9a-fA-F]+$", "");
    }

    public static String findLoggedInAccountId(int pid, Path accountDir) {
        String fallback = accountId(accountDir);
        Set<String> candidates;
        try (ProcessMemory process = ProcessMemory.open(pid)) {
            candidates = process.findAsciiMatches(ACCOUNT_PATTERN);
        } catch (Exception e) {
            log.debug("Unable to identify the logged-in WeChat account from process memory", e);
            return fallback;
        }
        String directoryName = accountDir.getFileName().toString();
        return candidates.stream()
                .filter(directoryName::startsWith)
                .max(Comparator.comparingInt(String::length))
                .map(candidate -> candidate.replaceFirst("_[0-9a-fA-F]+$", ""))
                .orElse(fallback);
    }

    /**
     * 从微信主进程提取每个数据库的 raw enc_key，全部候选都会通过 page 1 HMAC 校验。
     *
     * @return 数据库文件绝对路径 -> 32 字节 raw enc_key
     */
    public static Map<Path, byte[]> extractDatabaseKeys(int pid, Path dbStorage) throws IOException {
        List<DatabaseFile> databases = collectDatabases(dbStorage);
        if (databases.isEmpty()) {
            throw new IOException("微信 4.x 数据目录中没有可解密的 .db 文件: " + dbStorage);
        }

        Map<String, DatabaseFile> saltToDatabase = new LinkedHashMap<>();
        for (DatabaseFile database : databases) {
            saltToDatabase.putIfAbsent(database.saltHex, database);
        }
        Map<String, String> saltToKey = new LinkedHashMap<>();
        Set<Integer> candidatePids = new LinkedHashSet<>();
        candidatePids.add(pid);
        ProcessHandle.allProcesses()
                .filter(handle -> handle.info().command()
                        .map(command -> command.toLowerCase(Locale.ROOT).endsWith("weixin.exe"))
                        .orElse(false))
                .forEach(handle -> candidatePids.add((int) handle.pid()));

        for (Integer candidatePid : candidatePids) {
            Set<String> remainingSalts = new HashSet<>(saltToDatabase.keySet());
            remainingSalts.removeAll(saltToKey.keySet());
            if (remainingSalts.isEmpty()) {
                break;
            }
            try (ProcessMemory process = ProcessMemory.open(candidatePid)) {
                saltToKey.putAll(extractConfigCipherKeys(process, remainingSalts));
                remainingSalts.removeAll(saltToKey.keySet());
                if (!remainingSalts.isEmpty()) {
                    saltToKey.putAll(extractLegacyKeys(process, remainingSalts));
                }
            } catch (Exception e) {
                log.debug("Unable to scan Weixin process {}", candidatePid, e);
            }
        }

        Map<Path, byte[]> result = new LinkedHashMap<>();
        for (DatabaseFile database : databases) {
            String keyHex = saltToKey.get(database.saltHex);
            if (keyHex != null && verifyKey(HexUtil.decodeHex(keyHex), database.page1)) {
                result.put(database.path, HexUtil.decodeHex(keyHex));
            }
        }
        if (result.isEmpty()) {
            throw new IOException("未能从微信 4.x 进程中取得经过 HMAC 校验的数据库密钥");
        }
        log.info("微信 4.x 密钥提取完成: {}/{}", result.size(), databases.size());
        return result;
    }

    private static List<DatabaseFile> collectDatabases(Path dbStorage) throws IOException {
        List<DatabaseFile> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dbStorage)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(item -> item.getFileName().toString().endsWith(".db"))
                    .collect(Collectors.toList())) {
                if (Files.size(path) < PAGE_SIZE) {
                    continue;
                }
                byte[] page1 = readPage(path, 1);
                String salt = HexUtil.encodeHexStr(Arrays.copyOf(page1, SALT_SIZE));
                result.add(new DatabaseFile(dbStorage.relativize(path), path, page1, salt.toLowerCase(Locale.ROOT)));
            }
        }
        result.sort(Comparator.comparing(item -> item.relative.toString()));
        return result;
    }

    private static Map<String, String> extractConfigCipherKeys(
            ProcessMemory process, Set<String> remainingSalts) {
        Map<String, String> found = new HashMap<>();
        Set<Long> needleAddresses = process.findAll(CONFIG_CIPHER_NAME);
        if (needleAddresses.isEmpty()) {
            return found;
        }

        List<byte[]> referencePatterns = needleAddresses.stream()
                .map(address -> {
                    ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                    buffer.putLong(address);
                    buffer.putLong(CONFIG_CIPHER_NAME.length);
                    return buffer.array();
                })
                .collect(Collectors.toList());

        Set<Long> seenConfigPointers = new HashSet<>();
        for (MemoryChunk chunk : process.chunks(0x80)) {
            for (byte[] pattern : referencePatterns) {
                int position = indexOf(chunk.data, pattern, 0);
                while (position >= 0) {
                    long referenceAddress = chunk.baseAddress + position;
                    byte[] node = process.read(referenceAddress - 0x10, 0x50);
                    if (node != null
                            && node.length >= 0x40
                            && needleAddresses.contains(u64(node, 0x10))
                            && u64(node, 0x18) == CONFIG_CIPHER_NAME.length) {
                        long configPointer = u64(node, 0x28);
                        if (isUserAddress(configPointer) && seenConfigPointers.add(configPointer)) {
                            readConfigCipherBlob(process, configPointer, remainingSalts, found);
                        }
                    }
                    position = indexOf(chunk.data, pattern, position + 1);
                }
            }
            if (found.size() >= remainingSalts.size()) {
                break;
            }
        }
        return found;
    }

    private static void readConfigCipherBlob(
            ProcessMemory process,
            long configPointer,
            Set<String> remainingSalts,
            Map<String, String> found) {
        byte[] object = process.read(configPointer + 0x88, 0x28);
        if (object == null || object.length < 0x18) {
            return;
        }
        long dataPointer = u64(object, 0x08);
        long dataLength = u64(object, 0x10);
        if (!isUserAddress(dataPointer) || dataLength <= 0 || dataLength > 1024) {
            return;
        }
        byte[] blob = process.read(dataPointer, (int) dataLength);
        if (blob == null || blob.length != dataLength) {
            return;
        }
        for (int index = 0; index < blob.length; index++) {
            blob[index] ^= CONFIG_CIPHER_XOR_MASK[index % CONFIG_CIPHER_XOR_MASK.length];
        }
        String decoded = new String(blob, StandardCharsets.US_ASCII);
        Matcher matcher = CONFIG_CIPHER_PATTERN.matcher(decoded);
        while (matcher.find()) {
            addVerifiedCandidate(matcher.group(1), remainingSalts, found);
        }
    }

    private static Map<String, String> extractLegacyKeys(
            ProcessMemory process, Set<String> remainingSalts) {
        Map<String, String> found = new HashMap<>();
        for (MemoryChunk chunk : process.chunks(320)) {
            if (found.size() >= remainingSalts.size()) {
                break;
            }
            String data = new String(chunk.data, StandardCharsets.US_ASCII);
            Matcher matcher = LEGACY_KEY_PATTERN.matcher(data);
            while (matcher.find()) {
                addVerifiedCandidate(matcher.group(1), remainingSalts, found);
            }
        }
        return found;
    }

    private static void addVerifiedCandidate(
            String run, Set<String> remainingSalts, Map<String, String> found) {
        String normalized = run.toLowerCase(Locale.ROOT);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        if (normalized.length() > 96) {
            for (int start = 0; start <= normalized.length() - 64; start += 32) {
                starts.add(start);
            }
            starts.add(normalized.length() - 64);
        }
        for (Integer start : starts) {
            if (start < 0 || start + 64 > normalized.length()) {
                continue;
            }
            String keyHex = normalized.substring(start, start + 64);
            String saltHex = start + 96 <= normalized.length()
                    ? normalized.substring(start + 64, start + 96)
                    : null;
            int keyIndex = indexOfHex(keyHex);
            if (keyIndex < 0) {
                continue;
            }
            if (saltHex != null && remainingSalts.contains(saltHex) && !found.containsKey(saltHex)) {
                found.put(saltHex, keyHex);
            }
        }
    }

    private static int indexOfHex(String value) {
        int distinct = 0;
        int[] seen = new int[128];
        for (int index = 0; index < value.length(); index++) {
            char ch = Character.toLowerCase(value.charAt(index));
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                return -1;
            }
            if (seen[ch]++ == 0) {
                distinct++;
            }
        }
        return distinct >= 15 ? 0 : -1;
    }

    public static void decryptDatabase(Path source, Path target, byte[] key) throws IOException {
        if (key == null || key.length != KEY_SIZE) {
            throw new IOException("微信 4.x 数据库密钥长度必须为 32 字节");
        }
        Files.createDirectories(target.getParent());
        long fileSize = Files.size(source);
        if (fileSize < PAGE_SIZE) {
            throw new IOException("数据库文件小于一个页面: " + source);
        }

        try (InputStream input = new BufferedInputStream(Files.newInputStream(source), PAGE_SIZE * 2);
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                     target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE), PAGE_SIZE * 2)) {
            byte[] firstPage = readExactly(input, PAGE_SIZE);
            if (!verifyKey(key, firstPage)) {
                throw new IOException("微信 4.x page 1 HMAC 校验失败: " + source);
            }
            output.write(decryptPage(key, firstPage, 1));

            long pageNumber = 1L;
            byte[] page = new byte[PAGE_SIZE];
            int read;
            while ((read = readFully(input, page)) > 0) {
                if (read < PAGE_SIZE) {
                    Arrays.fill(page, read, PAGE_SIZE, (byte) 0);
                }
                pageNumber++;
                output.write(decryptPage(key, page, pageNumber));
            }
        }
        applyWal(source, target, key);
    }

    private static byte[] decryptPage(byte[] key, byte[] page, long pageNumber) throws IOException {
        int bodyLength = pageNumber == 1 ? IV_OFFSET - SALT_SIZE : IV_OFFSET;
        int bodyOffset = pageNumber == 1 ? SALT_SIZE : 0;
        byte[] iv = Arrays.copyOfRange(page, IV_OFFSET, IV_OFFSET + IV_SIZE);
        byte[] encrypted = Arrays.copyOfRange(page, bodyOffset, bodyOffset + bodyLength);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            if (pageNumber == 1) {
                if (decrypted.length != PAGE_SIZE - SALT_SIZE - RESERVE_SIZE) {
                    throw new IOException("微信 4.x 第一页解密长度异常");
                }
                byte[] result = new byte[PAGE_SIZE];
                System.arraycopy(SQLITE_HEADER, 0, result, 0, SQLITE_HEADER.length);
                System.arraycopy(decrypted, 0, result, SQLITE_HEADER.length, decrypted.length);
                return result;
            }
            byte[] result = new byte[PAGE_SIZE];
            System.arraycopy(decrypted, 0, result, 0, decrypted.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IOException("微信 4.x 数据库页面解密失败", e);
        }
    }

    private static void applyWal(Path source, Path target, byte[] key) throws IOException {
        Path wal = source.resolveSibling(source.getFileName().toString() + "-wal");
        if (!Files.isRegularFile(wal) || Files.size(wal) <= WAL_HEADER_SIZE) {
            return;
        }
        byte[] walBytes;
        try (InputStream input = Files.newInputStream(wal)) {
            walBytes = readExactly(input, (int) Math.min(Files.size(wal), Integer.MAX_VALUE));
        }
        int salt1 = ByteBuffer.wrap(walBytes, 16, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        int salt2 = ByteBuffer.wrap(walBytes, 20, 4).order(ByteOrder.BIG_ENDIAN).getInt();

        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            for (int offset = WAL_HEADER_SIZE;
                 offset + WAL_FRAME_SIZE <= walBytes.length;
                 offset += WAL_FRAME_SIZE) {
                ByteBuffer frameHeader = ByteBuffer.wrap(walBytes, offset, 24).order(ByteOrder.BIG_ENDIAN);
                long pageNumber = Integer.toUnsignedLong(frameHeader.getInt());
                frameHeader.getInt();
                int frameSalt1 = frameHeader.getInt();
                int frameSalt2 = frameHeader.getInt();
                if (pageNumber == 0 || pageNumber > 1_000_000
                        || frameSalt1 != salt1 || frameSalt2 != salt2) {
                    continue;
                }
                byte[] page = Arrays.copyOfRange(
                        walBytes, offset + 24, offset + 24 + PAGE_SIZE);
                byte[] decrypted = decryptPage(key, page, pageNumber);
                channel.write(ByteBuffer.wrap(decrypted), (pageNumber - 1) * PAGE_SIZE);
            }
        }
    }

    public static boolean verifyKey(byte[] key, byte[] page1) {
        if (key == null || key.length != KEY_SIZE || page1 == null || page1.length < PAGE_SIZE) {
            return false;
        }
        try {
            byte[] salt = Arrays.copyOf(page1, SALT_SIZE);
            byte[] macKey = pbkdf2Sha512(key, xor(salt, (byte) 0x3a), 2);
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(macKey, "HmacSHA512"));
            mac.update(page1, SALT_SIZE, IV_OFFSET + IV_SIZE - SALT_SIZE);
            mac.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array());
            byte[] expected = Arrays.copyOfRange(page1, PAGE_SIZE - HMAC_SIZE, PAGE_SIZE);
            return Arrays.equals(mac.doFinal(), expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2Sha512(byte[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(password, "HmacSHA512"));
        byte[] blockIndex = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array();
        mac.update(salt);
        mac.update(blockIndex);
        byte[] current = mac.doFinal();
        byte[] result = Arrays.copyOf(current, KEY_SIZE);
        for (int iteration = 1; iteration < iterations; iteration++) {
            current = mac.doFinal(current);
            for (int index = 0; index < result.length; index++) {
                result[index] ^= current[index];
            }
        }
        return result;
    }

    private static byte[] xor(byte[] input, byte value) {
        byte[] result = new byte[input.length];
        for (int index = 0; index < input.length; index++) {
            result[index] = (byte) (input[index] ^ value);
        }
        return result;
    }

    private static byte[] readPage(Path path, int pageNumber) throws IOException {
        long offset = (long) (pageNumber - 1) * PAGE_SIZE;
        byte[] page = new byte[PAGE_SIZE];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(page);
            channel.read(buffer, offset);
            if (buffer.position() < PAGE_SIZE) {
                throw new IOException("无法读取完整页面: " + path);
            }
        }
        return page;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int total = 0;
        while (total < length) {
            int read = input.read(result, total, length - total);
            if (read < 0) {
                throw new IOException("文件长度不足");
            }
            total += read;
        }
        return result;
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static long u64(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 8 > data.length) {
            return 0L;
        }
        return ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static boolean isUserAddress(long address) {
        return address >= 0x10000L && address < MAX_USER_ADDRESS;
    }

    private static int indexOf(byte[] source, byte[] target, int start) {
        if (target.length == 0) {
            return start;
        }
        int last = source.length - target.length;
        outer:
        for (int index = Math.max(0, start); index <= last; index++) {
            for (int targetIndex = 0; targetIndex < target.length; targetIndex++) {
                if (source[index + targetIndex] != target[targetIndex]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static final class DatabaseFile {
        private final Path relative;
        private final Path path;
        private final byte[] page1;
        private final String saltHex;

        private DatabaseFile(Path relative, Path path, byte[] page1, String saltHex) {
            this.relative = relative;
            this.path = path;
            this.page1 = page1;
            this.saltHex = saltHex;
        }
    }

    private static final class MemoryChunk {
        private final long baseAddress;
        private final byte[] data;

        private MemoryChunk(long baseAddress, byte[] data) {
            this.baseAddress = baseAddress;
            this.data = data;
        }
    }

    private static final class ProcessMemory implements AutoCloseable {
        private static final int CHUNK_SIZE = 2 * 1024 * 1024;
        private final WinNT.HANDLE handle;

        private ProcessMemory(WinNT.HANDLE handle) {
            this.handle = handle;
        }

        private static ProcessMemory open(int pid) throws IOException {
            WinNT.HANDLE handle = Kernel32.INSTANCE.OpenProcess(
                    PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, false, pid);
            if (handle == null || handle.getPointer() == null) {
                throw new IOException("无法读取微信进程内存，请以管理员身份运行后端。PID=" + pid);
            }
            return new ProcessMemory(handle);
        }

        private byte[] read(long address, int size) {
            Memory memory = new Memory(size);
            IntByReference bytesRead = new IntByReference();
            if (!Kernel32.INSTANCE.ReadProcessMemory(
                    handle, new Pointer(address), memory, size, bytesRead)) {
                return null;
            }
            return memory.getByteArray(0, bytesRead.getValue());
        }

        private List<MemoryRegion> regions() {
            List<MemoryRegion> result = new ArrayList<>();
            long address = 0L;
            while (address < 0x7FFF_FFFF_FFFFL) {
                WinNT.MEMORY_BASIC_INFORMATION info = new WinNT.MEMORY_BASIC_INFORMATION();
                BaseTSD.SIZE_T queried = Kernel32.INSTANCE.VirtualQueryEx(
                        handle, new Pointer(address), info,
                        new BaseTSD.SIZE_T(info.size()));
                if (queried == null || queried.longValue() == 0) {
                    break;
                }
                long base = Pointer.nativeValue(info.baseAddress);
                long size = info.regionSize.longValue();
                long next = base + size;
                if (next <= address) {
                    break;
                }
                if (info.state.intValue() == MEM_COMMIT
                        && READABLE_PROTECTIONS.contains(info.protect.intValue())
                        && size > 0 && size <= 512L * 1024 * 1024) {
                    result.add(new MemoryRegion(base, size));
                }
                address = next;
            }
            return result;
        }

        private List<MemoryChunk> chunks(int overlap) {
            List<MemoryChunk> chunks = new ArrayList<>();
            for (MemoryRegion region : regions()) {
                long offset = 0L;
                byte[] tail = new byte[0];
                long tailBase = region.baseAddress;
                while (offset < region.size) {
                    int take = (int) Math.min(CHUNK_SIZE, region.size - offset);
                    byte[] read = read(region.baseAddress + offset, take);
                    if (read == null) {
                        tail = new byte[0];
                        tailBase = region.baseAddress + offset + take;
                    } else {
                        byte[] data;
                        long dataBase;
                        if (tail.length > 0) {
                            data = new byte[tail.length + read.length];
                            System.arraycopy(tail, 0, data, 0, tail.length);
                            System.arraycopy(read, 0, data, tail.length, read.length);
                            dataBase = tailBase;
                        } else {
                            data = read;
                            dataBase = region.baseAddress + offset;
                        }
                        chunks.add(new MemoryChunk(dataBase, data));
                        if (overlap > 0 && data.length > overlap) {
                            tail = Arrays.copyOfRange(data, data.length - overlap, data.length);
                            tailBase = dataBase + data.length - tail.length;
                        } else {
                            tail = new byte[0];
                            tailBase = dataBase + data.length;
                        }
                    }
                    offset += take;
                }
            }
            return chunks;
        }

        private Set<Long> findAll(byte[] needle) {
            Set<Long> addresses = new HashSet<>();
            for (MemoryChunk chunk : chunks(needle.length - 1)) {
                int position = indexOf(chunk.data, needle, 0);
                while (position >= 0) {
                    addresses.add(chunk.baseAddress + position);
                    position = indexOf(chunk.data, needle, position + 1);
                }
            }
            return addresses;
        }

        private Set<String> findAsciiMatches(Pattern pattern) {
            Set<String> matches = new HashSet<>();
            for (MemoryChunk chunk : chunks(16)) {
                String text = new String(chunk.data, StandardCharsets.US_ASCII);
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    matches.add(matcher.group());
                }
            }
            return matches;
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    private static final class MemoryRegion {
        private final long baseAddress;
        private final long size;

        private MemoryRegion(long baseAddress, long size) {
            this.baseAddress = baseAddress;
            this.size = size;
        }
    }
}
