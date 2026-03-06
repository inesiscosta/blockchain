package pt.tecnico.blockchainist.common;

import pt.tecnico.blockchainist.contract.CreateWalletRequest;
import pt.tecnico.blockchainist.contract.DeleteWalletRequest;
import pt.tecnico.blockchainist.contract.TransferRequest;

/**
 * Utility class providing common helper methods for the blockchain system.
 * Includes debugging utilities and conversion methods between domain and protocol buffer representations.
 */
public class Utils {

    private static final boolean DEBUG = System.getProperty("debug") != null;

    /**
     * Prints a debug message to stderr if debug mode is enabled.
     * Debug mode is activated by setting the "debug" system property.
     *
     * @param format the format string (printf-style)
     * @param args the arguments referenced by the format specifiers
     */
    public static void debug(String format, Object... args) {
        if (DEBUG)
            System.err.printf("[DEBUG] " + format + "%n", args);
    }

    /**
     * Parses and validates a port number string.
     *
     * @param portStr the port number as a string
     * @param name descriptive name for error messages (e.g., "node", "sequencer")
     * @return the validated port number
     * @throws IllegalArgumentException if the port is invalid or out of range (0-65535)
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
     * Converts a domain Transaction object to its protocol buffer representation.
     *
     * @param domainTx the domain transaction to convert
     * @return the protocol buffer Transaction object
     */
    public static pt.tecnico.blockchainist.contract.Transaction convertToProto(Transaction domainTx) {
        var builder = pt.tecnico.blockchainist.contract.Transaction.newBuilder();

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
                    0);
            case DELETE_WALLET -> new Transaction(
                    Transaction.Type.DELETE,
                    proto.getDeleteWallet().getUserId(),
                    proto.getDeleteWallet().getWalletId(),
                    "",
                    0);
            case TRANSFER -> new Transaction(
                    Transaction.Type.TRANSFER,
                    proto.getTransfer().getSrcUserId(),
                    proto.getTransfer().getSrcWalletId(),
                    proto.getTransfer().getDstWalletId(),
                    proto.getTransfer().getValue());
            default -> throw new IllegalArgumentException("Unknown transaction type");
        };
    }
}
