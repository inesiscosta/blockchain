package pt.tecnico.blockchainist.sequencer.domain;

import java.util.ArrayList;
import java.util.List;

import pt.tecnico.blockchainist.common.Transaction;
import pt.tecnico.blockchainist.common.Utils;

/**
 * Manages the state of the sequencer.
 * This class maintains the ordered transaction ledger and assigns sequence numbers to transactions.
 */
public class SequencerState {

    private final List<Transaction> ledger = new ArrayList<>();

    /**
     * Constructs a new SequencerState with an empty ledger.
     */
    public SequencerState() {
    }

    /**
     * Broadcasts a transaction to be ordered by the sequencer.
     *
     * @param tx the transaction to order
     * @return the assigned sequence number (1-based)
     */
    public synchronized int broadcast(Transaction tx) {
        // Execute
        ledger.add(tx);
        Utils.debug("Transaction ordered: seqNum=%d, type=%s", ledger.size(), tx.type());
        return ledger.size();
    }

    /**
     * Delivers a transaction by its sequence number.
     *
     * @param seqNum the sequence number of the transaction to retrieve (1-based)
     * @return the transaction at the specified sequence number, or null if not yet available
     */
    public synchronized Transaction deliverTransaction(int seqNum) {
        // Check
        if (seqNum > ledger.size())
            return null;

        // Execute
        return ledger.get(seqNum - 1);
    }

}
