package pt.tecnico.blockchainist.sequencer.domain;

import java.util.ArrayList;
import java.util.List;

import pt.tecnico.blockchainist.common.Transaction;
import pt.tecnico.blockchainist.common.Utils;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages the state of the sequencer.
 * This class maintains the ordered transaction ledger and assigns sequence
 * numbers to transactions.
 */
public class SequencerState {

    private final List<List<Transaction>> blocks = new ArrayList<>();
    private List<Transaction> pendingBlock = new ArrayList<>();

    private final int maxBlockTransactions;
    private final int maxBlockTimeout;
    private int globalSequenceNumber = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timeoutTask;

    /**
     * Constructs a new SequencerState with an empty ledger.
     */
    public SequencerState(int maxBlockTransactions, int maxBlockTimeout) {
        this.maxBlockTransactions = maxBlockTransactions;
        this.maxBlockTimeout = maxBlockTimeout;
        resetTimer();
    }

    /**
     * Broadcasts a transaction to be ordered by the sequencer.
     *
     * @param tx the transaction to order
     * @return the assigned sequence number (1-based)
     */
    public synchronized int broadcast(Transaction tx) {
        // Execute
        pendingBlock.add(tx);
        globalSequenceNumber++;
        Utils.debug("Transaction added to pending block. Global SeqNum=%d, type=%s", globalSequenceNumber, tx.type());

        // Cut if needed
        if (pendingBlock.size() >= maxBlockTransactions) {
            Utils.debug("Max block size reached. Cutting block...");
            cutBlock();
        }

        return globalSequenceNumber;
    }

    /**
     * Delivers a block by its number.
     *
     * @param blockNum the block number to retrieve (1-based)
     * @return the list of transactions in the specified block, or an empty list if
     *         the block is not yet available
     * @throws InterruptedException
     */
    public synchronized List<Transaction> deliverBlock(int blockNum) throws InterruptedException {
        // Check
        while (blockNum > blocks.size()) {
            wait();
        }

        // Execute
        return blocks.get(blockNum - 1);
    }

    /**
     * Retrieves the last block number that has been cut.
     *
     * @return the last block number (0 if no blocks have been cut)
     */
    public synchronized int getLastBlockNumber() {
        return blocks.size();
    }

    /**
     * Cuts the current pending block and adds it to the ledger.
     */
    private synchronized void cutBlock() {
        // Check
        if (pendingBlock.isEmpty())
            return;

        // Execute
        blocks.add(new ArrayList<>(pendingBlock));
        Utils.debug("Block %d cut with %d transactions.", blocks.size(), pendingBlock.size());

        // Ready next block
        pendingBlock.clear();
        resetTimer();

        // Deliver blocks
        notifyAll();
    }

    /**
     * Resets the block timeout timer. This method cancels any existing timer and
     * starts a new one that will trigger a block cut if the pending block is not
     * cut within the specified timeout.
     */
    private synchronized void resetTimer() {
        // Check
        if (timeoutTask != null && !timeoutTask.isCancelled()) {
            timeoutTask.cancel(false);
        }

        // Execute
        timeoutTask = scheduler.scheduleAtFixedRate(() -> {
            synchronized (SequencerState.this) {
                if (!pendingBlock.isEmpty()) {
                    Utils.debug("Block timeout reached. Cutting block...");
                    cutBlock();
                }
            }
        }, maxBlockTimeout, maxBlockTimeout, TimeUnit.SECONDS);
    }

    /**
     * Shuts down the sequencer state, stopping any scheduled tasks.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
