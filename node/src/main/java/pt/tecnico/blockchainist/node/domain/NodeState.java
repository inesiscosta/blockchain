package pt.tecnico.blockchainist.node.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pt.tecnico.blockchainist.common.Transaction;
import pt.tecnico.blockchainist.common.Utils;

/**
 * Manages the state of the blockchain node.
 * This class maintains the wallet registry, transaction ledger, and handles
 * transaction execution.
 * All public methods are synchronized to ensure thread-safe state
 * modifications.
 */
public class NodeState {

    /**
     * Internal record representing a wallet with its owner and balance.
     *
     * @param ownerId the user ID who owns the wallet
     * @param balance the current balance in the wallet
     */
    private record Wallet(String ownerId, long balance) {
    }

    private final Map<String, Wallet> wallets = new HashMap<>();
    private final List<Transaction> ledger = new ArrayList<>();
    private final Map<Integer, IllegalArgumentException> executionErrors = new HashMap<>();
    private int currentSequence = 0;
    private int currentBlock = 0;

    /**
     * Constructs a new NodeState and initializes it with the bank's default wallet.
     */
    public NodeState() {
        wallets.put("bc", new Wallet("BC", 1000));
    }

    /**
     * Creates a new wallet for a user.
     *
     * @param userId   the user ID requesting the new wallet
     * @param walletId the unique identifier for the new wallet
     * @throws IllegalArgumentException if the wallet already exists
     */
    private void createWallet(String userId, String walletId) {
        // Check
        if (wallets.containsKey(walletId)) {
            throw new IllegalArgumentException("Wallet already exists: " + walletId);
        }

        // Create
        wallets.put(walletId, new Wallet(userId, 0));

        // Log
        Utils.debug("Wallet %s created for user %s", walletId, userId);
    }

    /**
     * Deletes an existing wallet.
     *
     * @param userId   the user ID requesting the deletion
     * @param walletId the wallet ID to delete
     * @throws IllegalArgumentException if wallet not found, has positive balance,
     *                                  or user is not the owner
     */
    private void deleteWallet(String userId, String walletId) {
        // Check
        Wallet w = wallets.get(walletId);
        if (w == null)
            throw new IllegalArgumentException("Wallet not found");
        if (w.balance() > 0)
            throw new IllegalArgumentException("Cannot delete wallet with positive balance");
        if (!w.ownerId().equals(userId))
            throw new IllegalArgumentException("Cannot delete wallet of a different owner");

        // Remove
        wallets.remove(walletId);

        // Log
        Utils.debug("Wallet %s deleted from user %s", walletId, userId);
    }

    /**
     * Transfers funds from one wallet to another.
     *
     * @param srcUserId   the user ID initiating the transfer
     * @param srcWalletId the source wallet ID
     * @param dstWalletId the destination wallet ID
     * @param amount      the amount to transfer
     * @throws IllegalArgumentException if wallets not found, insufficient funds,
     *                                  invalid amount, or user is not the owner
     */
    private void transfer(String srcUserId, String srcWalletId, String dstWalletId, long amount) {
        // Check
        Wallet srcW = wallets.get(srcWalletId);
        Wallet dstW = wallets.get(dstWalletId);
        if (srcW == null)
            throw new IllegalArgumentException("Source Wallet not found");
        if (dstW == null)
            throw new IllegalArgumentException("Destination Wallet not found");
        if (!srcW.ownerId().equals(srcUserId))
            throw new IllegalArgumentException("Cannot transfer from a different owner's Wallet");
        if (srcW.balance() < amount)
            throw new IllegalArgumentException("Not enough funds in Wallet to transfer");
        if (amount <= 0)
            throw new IllegalArgumentException("Cannot transfer negative amounts");

        // Transfer
        wallets.put(srcWalletId, new Wallet(srcW.ownerId(), srcW.balance() - amount));
        wallets.put(dstWalletId, new Wallet(dstW.ownerId(), dstW.balance() + amount));

        // Log
        Utils.debug("Transferred %d from %s to %s", amount, srcWalletId, dstWalletId);
    }

    /**
     * Reads the current balance of a wallet.
     *
     * @param walletId the wallet ID to query
     * @return the current balance of the wallet
     * @throws IllegalArgumentException if the wallet is not found
     */
    public synchronized long readBalance(String walletId) {
        Wallet w = wallets.get(walletId);
        if (w == null)
            throw new IllegalArgumentException("Wallet Not found");

        return w.balance();
    }

    /**
     * Retrieves a copy of the current blockchain ledger.
     *
     * @return a list of all transactions in the ledger
     */
    public synchronized List<Transaction> getBlockchainState() {
        return new ArrayList<>(ledger);
    }

    /**
     * Applies a transaction to the blockchain state.
     * Execution errors are stored and later thrown in the requesting thread.
     *
     * @param tx     the transaction to apply
     * @param seqNum the sequence number of the transaction
     */
    public synchronized void applyTransaction(Transaction tx, int seqNum) {
        // Log
        Utils.debug("Applying operation: type=%s userId=%s, srcWallet=%s, dstWallet=%s, amount=%s (seqNum=%d)",
                tx.type(), tx.srcUserId(),
                tx.srcWalletId().isEmpty() ? "null" : tx.srcWalletId(),
                tx.dstWalletId().isEmpty() ? "null" : tx.dstWalletId(),
                tx.amount(), seqNum);

        // Execute
        try {
            switch (tx.type()) {
                case CREATE -> this.createWallet(tx.srcUserId(), tx.dstWalletId());
                case DELETE -> this.deleteWallet(tx.srcUserId(), tx.srcWalletId());
                case TRANSFER -> this.transfer(tx.srcUserId(), tx.srcWalletId(), tx.dstWalletId(), tx.amount());
            }
        } catch (IllegalArgumentException e) {
            // Store Error for later
            executionErrors.put(seqNum, e);
        } finally {
            // Log
            ledger.add(tx);

            // Wake
            this.currentSequence = seqNum;
            notifyAll();
        }
    }

    /**
     * Applies a block of transactions to the blockchain state.
     *
     * @param block the list of transactions to apply
     */
    public synchronized void applyBlock(List<Transaction> block) {
        for (Transaction tx : block) {
            applyTransaction(tx, currentSequence + 1);
        }
        currentBlock++;
    }

    /**
     * Waits until a specific sequence number has been processed.
     * If the transaction at the target sequence failed, the stored exception is
     * thrown.
     *
     * @param target the sequence number to wait for
     * @throws InterruptedException     if the thread is interrupted while waiting
     * @throws IllegalArgumentException if the transaction at the target sequence
     *                                  number failed
     */
    public synchronized void waitForSequence(int target) throws InterruptedException {
        while (currentSequence < target) {
            wait();
        }

        // If error exists, throw it in the right thread
        if (executionErrors.containsKey(target)) {
            throw executionErrors.remove(target);
        }
    }

    /**
     * Returns the next expected block number.
     *
     * @return the next block number to be processed
     */
    public synchronized int getNextBlockNumber() {
        return currentBlock + 1;
    }

}
