package io.lumenlink.server;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

final class PasswordHasher {
    private static final int MEMORY_KIB = 65_536;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    String hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
        return String.join("$", "argon2id", "v=19", "m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM,
                Base64.getEncoder().withoutPadding().encodeToString(salt),
                Base64.getEncoder().withoutPadding().encodeToString(hash));
    }

    boolean verify(char[] password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 5 || !"argon2id".equals(parts[0]) || !"v=19".equals(parts[1])) return false;
            String[] parameters = parts[2].split(",");
            int memory = Integer.parseInt(parameters[0].substring(2));
            int iterations = Integer.parseInt(parameters[1].substring(2));
            int parallelism = Integer.parseInt(parameters[2].substring(2));
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] expected = Base64.getDecoder().decode(parts[4]);
            byte[] actual = derive(password, salt, memory, iterations, parallelism);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int memory, int iterations, int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] result = new byte[HASH_BYTES];
        generator.generateBytes(password, result);
        return result;
    }
}
