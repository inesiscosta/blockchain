package pt.tecnico.blockchainist.node.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pt.tecnico.blockchainist.common.DuplicateException;
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
     * @param ownerId           the user ID who owns the wallet
     * @param balance           the current balance in the wallet
     * @param optimisticBalance the optimistic balance in the wallet
     * @param organization      the organization to which the wallet belongs
     */
    private record Wallet(String ownerId, long balance, long optimisticBalance, String organization) {
    }

    private final Map<String, Wallet> wallets = new HashMap<>();
    private final List<Transaction> ledger = new ArrayList<>();
    private final Map<String, IllegalArgumentException> executionErrors = new HashMap<>();
    private final Set<String> optimisticTransfers = new java.util.HashSet<>();
    private final Map<String, Long> pendingTransfers = new HashMap<>();
    private final Set<String> pendingTransferIds = new java.util.HashSet<>();
    private final Set<String> pendingDeletes = new java.util.HashSet<>();
    private int currentSequence = 0;
    private int currentBlock = 0;

    /**
     * Constructs a new NodeState and initializes it with the bank's default wallet.
     */
    public NodeState() {
        wallets.put("bc", new Wallet("BC", 1000, 1000, "OrgA"));
    }

    /**
     * Creates a new wallet for a user.
     *
     * @param userId   the user ID requesting the new wallet
     * @param walletId the unique identifier for the new wallet
     * @throws IllegalArgumentException if the wallet already exists
     */
    private void createWallet(String userId, String walletId, String organization) {
        // Check
        if (wallets.containsKey(walletId)) {
            throw new IllegalArgumentException("Wallet already exists: " + walletId);
        }

        // Create
        wallets.put(walletId, new Wallet(userId, 0, 0, organization));

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
     * @param srcUserId     the user ID initiating the transfer
     * @param srcWalletId   the source wallet ID
     * @param dstWalletId   the destination wallet ID
     * @param amount        the amount to transfer
     * @param optimistic    if true, the transfer is added to pending transfers for
     * @param transactionId the ID of the transaction
     * @throws IllegalArgumentException if wallets not found, insufficient funds,
     *                                  invalid amount, or user is not the owner
     */
    private void transfer(String srcUserId, String srcWalletId, String dstWalletId, long amount, boolean optimistic,
            boolean wasOptimistic) {
        // Check
        Wallet srcW = wallets.get(srcWalletId);
        Wallet dstW = wallets.get(dstWalletId);
        if (srcW == null)
            throw new IllegalArgumentException("Source Wallet not found");
        if (dstW == null)
            throw new IllegalArgumentException("Destination Wallet not found");
        if (!srcW.ownerId().equals(srcUserId))
            throw new IllegalArgumentException("Cannot transfer from a different owner's Wallet");
        if (amount <= 0)
            throw new IllegalArgumentException("Cannot transfer negative amounts");

        // Transfer
        if (optimistic) {
            optimisticTransfer(srcW, dstW, srcWalletId, dstWalletId, amount);
        } else {
            if (srcW.balance() < amount)
                throw new IllegalArgumentException("Not enough funds in Wallet to transfer");

            // Calculate optimistic balance
            long newOptSrc = srcW.optimisticBalance();
            long newOptDst = dstW.optimisticBalance();
            if (!wasOptimistic) {
                newOptSrc -= amount;
                newOptDst += amount;
            }

            wallets.put(srcWalletId,
                    new Wallet(srcW.ownerId(), srcW.balance() - amount, newOptSrc, srcW.organization()));
            wallets.put(dstWalletId,
                    new Wallet(dstW.ownerId(), dstW.balance() + amount, newOptDst, dstW.organization()));
        }
        // Log
        Utils.debug("Transferred %d from %s to %s", amount, srcWalletId, dstWalletId);
    }

    private void optimisticTransfer(Wallet srcW, Wallet dstW, String srcWalletId, String dstWalletId, long amount) {
        if (srcW.optimisticBalance() < amount)
            throw new IllegalArgumentException("Not enough funds in Wallet to transfer");

        wallets.put(srcWalletId,
                new Wallet(srcW.ownerId(), srcW.balance(), srcW.optimisticBalance() - amount, srcW.organization()));
        wallets.put(dstWalletId,
                new Wallet(dstW.ownerId(), dstW.balance(), dstW.optimisticBalance() + amount, dstW.organization()));
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

        return w.optimisticBalance();
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
        Utils.debug("Applying operation: type=%s userId=%s, srcWallet=%s, dstWallet=%s, amount=%s (seqNum=%d, txId=%s)",
                tx.type(), tx.srcUserId(),
                tx.srcWalletId().isEmpty() ? "null" : tx.srcWalletId(),
                tx.dstWalletId().isEmpty() ? "null" : tx.dstWalletId(),
                tx.amount(), seqNum, tx.transactionId());

        // Execute
        IllegalArgumentException executionException = null;
        boolean wasPending = optimisticTransfers.contains(tx.transactionId());
        try {
            if (isDuplicate(tx.transactionId()))
                throw new DuplicateException(seqNum);

            switch (tx.type()) {
                case CREATE -> this.createWallet(tx.srcUserId(), tx.dstWalletId(), tx.organization());
                case DELETE -> this.deleteWallet(tx.srcUserId(), tx.srcWalletId());
                case TRANSFER ->
                    this.transfer(tx.srcUserId(), tx.srcWalletId(), tx.dstWalletId(), tx.amount(), false, wasPending);
            }
        } catch (IllegalArgumentException e) {
            // Store Error for later
            executionException = e;
        } catch (DuplicateException e) {
            executionException = executionErrors.get(tx.transactionId());
        } finally {
            // Log
            ledger.add(tx);
            executionErrors.put(tx.transactionId(), executionException);

            // Optimistic Cleanup
            if (wasPending)
                optimisticTransfers.remove(tx.transactionId());
            if (tx.type() == Transaction.Type.DELETE)
                pendingDeletes.remove(tx.srcWalletId());
            if (tx.type() == Transaction.Type.TRANSFER && pendingTransferIds.contains(tx.transactionId())
                    && pendingTransfers.containsKey(tx.srcWalletId())) {
                long newPending = pendingTransfers.get(tx.srcWalletId()) - tx.amount();
                if (newPending <= 0) {
                    pendingTransfers.remove(tx.srcWalletId());
                } else {
                    pendingTransfers.put(tx.srcWalletId(), newPending);
                }
                pendingTransferIds.remove(tx.transactionId());
            }

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
    public synchronized void waitForSequence(int target, String transactionId) throws InterruptedException {
        while (currentSequence < target) {
            wait();
        }

        // If error exists, throw it in the right thread
        IllegalArgumentException e = executionErrors.get(transactionId);
        if (e != null)
            throw e;
    }

    /**
     * Returns the next expected block number.
     *
     * @return the next block number to be processed
     */
    public synchronized int getNextBlockNumber() {
        return currentBlock + 1;
    }

    private boolean isDuplicate(String transactionId) {
        if (executionErrors.containsKey(transactionId)) {
            Utils.debug("Duplicate transaction detected: %s)", transactionId);
            return true;
        }
        return false;
    }

    public synchronized String getWalletOrganization(String walletId) {
        Wallet w = wallets.get(walletId);
        if (w == null)
            throw new IllegalArgumentException("Wallet Not found");
        return w.organization();
    }

    public synchronized void optimisticTransfer(String srcUserId, String srcWalletId, String dstWalletId, long amount,
            String transactionId) {
        // Check
        if (optimisticTransfers.contains(transactionId))
            throw new IllegalArgumentException("Duplicate Transfer: " + transactionId);
        if (pendingDeletes.contains(srcWalletId))
            throw new IllegalArgumentException("Cannot transfer from a wallet pending deletion");
        if (pendingDeletes.contains(dstWalletId))
            throw new IllegalArgumentException("Cannot transfer to a wallet pending deletion");
        if (pendingTransfers.containsKey(srcWalletId)
                && pendingTransfers.get(srcWalletId) + amount > wallets.get(srcWalletId).optimisticBalance()) {
            throw new DependencyException("Can't Optimize transfer with undetermined dependencies");
        }

        // Apply
        transfer(srcUserId, srcWalletId, dstWalletId, amount, true, false);
        optimisticTransfers.add(transactionId);
    }

    public synchronized void markPendingDelete(String walletId) {
        pendingDeletes.add(walletId);
    }

    public synchronized void markPendingTransfer(String transactionId, String srcWalletId, long amount) {
        Long oldAmount = pendingTransfers.get(srcWalletId);
        if (oldAmount != null) {
            pendingTransfers.put(srcWalletId, oldAmount + amount);
        } else {
            pendingTransfers.put(srcWalletId, amount);
        }
        pendingTransferIds.add(transactionId);
    }
}
