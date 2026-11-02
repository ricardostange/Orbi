package br.com.assistente.teste;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only ciphertext and IV are persisted. The AES key remains in Android Keystore. */
public final class ApiKeyStore {
    private static final String ALIAS = "orbi.deepseek.api.v1";
    private final SharedPreferences preferences;

    public ApiKeyStore(Context context) {
        preferences = context.getSharedPreferences("api_credentials", Context.MODE_PRIVATE);
    }

    public boolean hasKey() { return preferences.contains("deepseek"); }

    private SecretKey encryptionKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new GeneralSecurityException("Encryption key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    public void save(String value) throws GeneralSecurityException, IOException {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Empty API key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true));
        byte[] plaintext = value.trim().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plaintext); }
        finally { java.util.Arrays.fill(plaintext, (byte) 0); }
        String record = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!preferences.edit().putString("deepseek", record).commit()) throw new IOException("Save failed");
    }

    /** Reserved for the future API client; never display or log the returned value. */
    public String read() throws GeneralSecurityException, IOException {
        String record = preferences.getString("deepseek", null);
        if (record == null) return null;
        try {
            String[] parts = record.split(":", -1);
            if (parts.length != 2) throw new GeneralSecurityException("Invalid encrypted record");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            byte[] plaintext = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
            try { return new String(plaintext, StandardCharsets.UTF_8); }
            finally { java.util.Arrays.fill(plaintext, (byte) 0); }
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid encrypted record", e);
        }
    }

    public void remove() throws IOException {
        if (!preferences.edit().remove("deepseek").commit()) throw new IOException("Remove failed");
    }
}
