import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class LoganLocalDecoder {
    private static final byte ENCRYPT_CONTENT_START = 0x01;
    private static final int LENGTH_BYTES = 4;
    private static final String AES_PKCS5 = "AES/CBC/PKCS5Padding";
    private static final String AES_NO_PADDING = "AES/CBC/NoPadding";

    private LoganLocalDecoder() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: LoganLocalDecoder <input-log-file> <output-txt> <aes-key-16> <aes-iv-16>");
            System.exit(2);
        }

        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        byte[] key = require16Bytes(args[2], "LOGAN_AES_KEY");
        byte[] iv = require16Bytes(args[3], "LOGAN_AES_IV");
        DecodeStats stats = decode(input, output, key, iv);
        System.out.println("decoded blocks=" + stats.blocks + " bytes=" + stats.bytes);
    }

    private static DecodeStats decode(Path input, Path output, byte[] key, byte[] iv)
        throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(input)) {
            throw new IOException("Input is not a file: " + input);
        }

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        byte[] fileBytes = Files.readAllBytes(input);
        int position = 0;
        int blocks = 0;
        long decodedBytes = 0L;

        try (OutputStream outputStream = Files.newOutputStream(
            output,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )) {
            while (position < fileBytes.length) {
                if (fileBytes[position++] != ENCRYPT_CONTENT_START) {
                    continue;
                }

                if (fileBytes.length - position < LENGTH_BYTES) {
                    throw new IOException("Invalid Logan block header at byte " + (position - 1));
                }

                int encryptedLength = readBigEndianInt(fileBytes, position);
                position += LENGTH_BYTES;
                if (encryptedLength <= 0 || encryptedLength > fileBytes.length - position) {
                    throw new IOException(
                        "Invalid Logan block length " + encryptedLength + " at byte " + (position - LENGTH_BYTES)
                    );
                }

                byte[] encrypted = Arrays.copyOfRange(fileBytes, position, position + encryptedLength);
                position += encryptedLength;
                byte[] compressed = decrypt(encrypted, key, iv);
                byte[] plainText = gunzip(compressed);
                outputStream.write(plainText);
                blocks++;
                decodedBytes += plainText.length;
            }
        }

        if (blocks == 0) {
            throw new IOException("No Logan encrypted blocks found in " + input);
        }
        return new DecodeStats(blocks, decodedBytes);
    }

    private static byte[] decrypt(byte[] encrypted, byte[] key, byte[] iv) throws GeneralSecurityException {
        try {
            return decryptWith(AES_PKCS5, encrypted, key, iv);
        } catch (BadPaddingException | IllegalBlockSizeException ignored) {
            return decryptWith(AES_NO_PADDING, encrypted, key, iv);
        }
    }

    private static byte[] decryptWith(String transformation, byte[] encrypted, byte[] key, byte[] iv)
        throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            while (true) {
                try {
                    int read = gzipInputStream.read(buffer);
                    if (read < 0) {
                        return output.toByteArray();
                    }
                    output.write(buffer, 0, read);
                } catch (IOException exception) {
                    byte[] partial = truncateToLastLine(output.toByteArray());
                    if (partial.length > 0) {
                        return partial;
                    }
                    throw exception;
                }
            }
        }
    }

    private static byte[] truncateToLastLine(byte[] bytes) {
        for (int index = bytes.length - 1; index >= 0; index--) {
            if (bytes[index] == '\n') {
                return Arrays.copyOf(bytes, index + 1);
            }
        }
        return new byte[0];
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private static byte[] require16Bytes(String value, String name) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16) {
            throw new IllegalArgumentException(name + " must be exactly 16 UTF-8 bytes.");
        }
        return bytes;
    }

    private static final class DecodeStats {
        final int blocks;
        final long bytes;

        DecodeStats(int blocks, long bytes) {
            this.blocks = blocks;
            this.bytes = bytes;
        }
    }
}
