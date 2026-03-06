package pt.tecnico.blockchainist.common;

/**
 * Represents a blockchain transaction in the domain model.
 * This record encapsulates all transaction types (CREATE, DELETE, TRANSFER) with a unified structure.
 *
 * @param type the type of transaction operation
 * @param srcUserId the user ID initiating the transaction
 * @param srcWalletId the source wallet ID (used for DELETE and TRANSFER operations)
 * @param dstWalletId the destination wallet ID (used for CREATE and TRANSFER operations)
 * @param amount the amount to transfer (used for TRANSFER operations only)
 */
public record Transaction(
    Type type, 
    String srcUserId, 
    String srcWalletId, 
    String dstWalletId, 
    long amount
) {
    /**
     * Enumeration of supported transaction types.
     */
    public enum Type {
        /** Create a new wallet */
        CREATE,
        /** Delete an existing wallet */
        DELETE,
        /** Transfer funds between wallets */
        TRANSFER
    }
}