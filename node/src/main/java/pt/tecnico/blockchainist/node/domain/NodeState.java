package pt.tecnico.blockchainist.node.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodeState {

    private record Wallet(String ownerId, long balance) {
    }

    private final Map<String, Wallet> wallets = new HashMap<>();
    private final List<Transaction> ledger = new ArrayList<>();

    public NodeState() {
        wallets.put("bc", new Wallet("BC", 1000));
    }

    private void createWallet(String userId, String walletId) {
        // Check
        if (wallets.containsKey(walletId)) {
            throw new IllegalArgumentException("Wallet already exists: " + walletId);
        }

        // Create
        wallets.put(walletId, new Wallet(userId, 0));
    }

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
    }

    private void transfer(String srcUserId, String srcWalletId, String dstWalletId, Long amount) {
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
            throw new IllegalArgumentException("Cannot transfer negative ammounts");

        // Transfer
        wallets.put(srcWalletId, new Wallet(srcW.ownerId(), srcW.balance() - amount));
        wallets.put(dstWalletId, new Wallet(dstW.ownerId(), dstW.balance() + amount));
    }

    public synchronized long readBalance(String walletId) {
        Wallet w = wallets.get(walletId);
        if (w == null)
            throw new IllegalArgumentException("Wallet Not found");

        return w.balance();
    }

    public synchronized List<Transaction> getBlockchainState() {
        return new ArrayList<>(ledger);
    }

    public synchronized void applyTransaction(Transaction tx) {
        switch (tx.type()) {
            case CREATE -> this.createWallet(tx.srcUserId(), tx.dstWalletId());
            case DELETE -> this.deleteWallet(tx.srcUserId(), tx.srcWalletId());
            case TRANSFER -> this.transfer(tx.srcUserId(), tx.srcWalletId(), tx.dstWalletId(), tx.amount());
        }

        // Log
        ledger.add(tx);
    }

}
