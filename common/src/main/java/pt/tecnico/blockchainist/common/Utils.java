package pt.tecnico.blockchainist.common;

import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.MessageLite;

import pt.tecnico.blockchainist.contract.CreateWalletRequest;
import pt.tecnico.blockchainist.contract.DeleteWalletRequest;
import pt.tecnico.blockchainist.contract.DeliverBlockResponse;
import pt.tecnico.blockchainist.contract.TransferRequest;
import io.grpc.Status;

import java.io.InputStream;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Utility class providing common helper methods for the blockchain system.
 * Includes debugging utilities and conversion methods between domain and
 * protocol buffer representations.
 */
public class Utils {

    private static final boolean DEBUG = System.getProperty("debug") != null;
    private static final ConcurrentHashMap<String, Key> keyCache = new ConcurrentHashMap<>();

    /**
     * Prints a debug message to stderr if debug mode is enabled.
     * Debug mode is activated by setting the "debug" system property.
     *
     * @param format the format string (printf-style)
     * @param args   the arguments referenced by the format specifiers
     */
    public static void debug(String format, Object... args) {
        if (DEBUG)
            System.err.printf("[DEBUG] " + format + "%n", args);
    }

    /**
     * Parses and validates a port number string.
     *
     * @param portStr the port number as a string
     * @param name    descriptive name for error messages (e.g., "node",
     *                "sequencer")
     * @return the validated port number
     * @throws IllegalArgumentException if the port is invalid or out of range
     *                                  (0-65535)
     */
    public static int parsePort(String portStr, String name) {
        try {
            int p = Integer.parseInt(portStr);
            if (p < 0 || p > 65535) {
                throw new IllegalArgumentException(name + " port out of range: " + portStr);
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name + " port: " + portStr);
        }
    }

    /**
     * Parses and validates a positive integer string.
     * 
     * @param intStr the integer as a string
     * @param name   descriptive name for error messages (e.g., "block
     *               transactions", "block time")
     * @return the validated positive integer
     * @throws IllegalArgumentException if the integer is invalid or not positive
     */
    public static int parsePositiveInt(String intStr, String name) {
        try {
            int value = Integer.parseInt(intStr);
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be a positive integer: " + intStr);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name + ": " + intStr);
        }
    }

    /**
     * Converts a domain Transaction object to its protocol buffer representation.
     *
     * @param domainTx the domain transaction to convert
     * @return the protocol buffer Transaction object
     */
    public static pt.tecnico.blockchainist.contract.Transaction convertToProto(Transaction domainTx) {
        var builder = pt.tecnico.blockchainist.contract.Transaction.newBuilder()
                .setTransactionId(domainTx.transactionId());

        switch (domainTx.type()) {
            case CREATE -> builder.setCreateWallet(CreateWalletRequest.newBuilder()
                    .setUserId(domainTx.srcUserId())
                    .setWalletId(domainTx.dstWalletId())
                    .build());

            case DELETE -> builder.setDeleteWallet(DeleteWalletRequest.newBuilder()
                    .setUserId(domainTx.srcUserId())
                    .setWalletId(domainTx.srcWalletId())
                    .build());

            case TRANSFER -> builder.setTransfer(TransferRequest.newBuilder()
                    .setSrcUserId(domainTx.srcUserId())
                    .setSrcWalletId(domainTx.srcWalletId())
                    .setDstWalletId(domainTx.dstWalletId())
                    .setValue(domainTx.amount())
                    .build());
        }
        return builder.build();
    }

    /**
     * Converts a protocol buffer Transaction object to its domain representation.
     *
     * @param proto the protocol buffer transaction to convert
     * @return the domain Transaction object
     * @throws IllegalArgumentException if the transaction type is unknown
     */
    public static Transaction convertToDomain(pt.tecnico.blockchainist.contract.Transaction proto) {
        return switch (proto.getOperationCase()) {
            case CREATE_WALLET -> new Transaction(
                    Transaction.Type.CREATE,
                    proto.getCreateWallet().getUserId(),
                    "",
                    proto.getCreateWallet().getWalletId(),
                    0,
                    proto.getTransactionId());
            case DELETE_WALLET -> new Transaction(
                    Transaction.Type.DELETE,
                    proto.getDeleteWallet().getUserId(),
                    proto.getDeleteWallet().getWalletId(),
                    "",
                    0,
                    proto.getTransactionId());
            case TRANSFER -> new Transaction(
                    Transaction.Type.TRANSFER,
                    proto.getTransfer().getSrcUserId(),
                    proto.getTransfer().getSrcWalletId(),
                    proto.getTransfer().getDstWalletId(),
                    proto.getTransfer().getValue(),
                    proto.getTransactionId());
            default -> throw new IllegalArgumentException("Unknown transaction type");
        };
    }

    public static Status verifySignature(MessageLite payload, byte[] signatureBytes, PublicKey key) {
        if (signatureBytes == null) {
            return Status.UNAUTHENTICATED.withDescription("Missing mandatory signature trailer!");
        }

        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(payload.toByteArray());

            if (!sig.verify(signatureBytes)) {
                System.err.println("[Security] Invalid signature");
                return Status.UNAUTHENTICATED.withDescription("Signature verification failed!");
            }

            debug("%s", "[Security] Signature verified successfully.");
            return Status.OK;
        } catch (Exception e) {
            return Status.INTERNAL.withDescription("Error verifying signature: " + e.getMessage());
        }
    }

    public static void sign(DeliverBlockResponse response, PrivateKey privateKey) throws SignatureException {
        try {
            // Apply metadata
            AtomicReference<byte[]> sigRef = Constants.SIGNATURE_REF_KEY.get();
            if (sigRef != null) {
                sigRef.set(signBytes(response, privateKey));
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new SignatureException(e.getMessage());
        }
    }

    public static byte[] signBytes(MessageLite payload, PrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(payload.toByteArray());
        return sig.sign();
    }

    public static byte[] readResource(String path) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("File not Found: " + path);
            }
            return is.readAllBytes();
        }
    }

    public static Key loadKey(String resourcePath, boolean isPrivate) throws Exception {
        if (keyCache.containsKey(resourcePath)) {
            return keyCache.get(resourcePath);
        }

        byte[] keyBytes = readResource(resourcePath);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (isPrivate) {
            // Private keys strictly use PKCS8
            PKCS8EncodedKeySpec privateSpec = new PKCS8EncodedKeySpec(keyBytes);
            Key key = kf.generatePrivate(privateSpec);
            keyCache.put(resourcePath, key);
            return key;
        } else {
            // Public keys strictly use X509
            X509EncodedKeySpec publicSpec = new X509EncodedKeySpec(keyBytes);
            Key key = kf.generatePublic(publicSpec);
            keyCache.put(resourcePath, key);
            return key;
        }
    }
}
