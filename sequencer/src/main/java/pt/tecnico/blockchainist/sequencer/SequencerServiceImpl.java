package pt.tecnico.blockchainist.sequencer;

import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.common.Utils;
import pt.tecnico.blockchainist.contract.*;
import pt.tecnico.blockchainist.sequencer.domain.SequencerState;
import pt.tecnico.blockchainist.common.Transaction;
import io.grpc.Status;

/**
 * Implementation of the sequencer gRPC service.
 * This class handles transaction ordering requests from nodes by assigning sequence numbers
 * and delivering ordered transactions.
 */
public class SequencerServiceImpl extends SequencerServiceGrpc.SequencerServiceImplBase {
    private final SequencerState seqState = new SequencerState();

    /**
     * Constructs a new SequencerServiceImpl.
     */
    public SequencerServiceImpl() {
    }

    /**
     * Handles broadcast requests from nodes.
     * Assigns a sequence number to the incoming transaction.
     *
     * @param request the broadcast request containing the transaction to order
     * @param responseObserver the observer to send the sequence number back to the node
     */
    @Override
    public void broadcast(BroadcastRequest request, StreamObserver<BroadcastResponse> responseObserver) {
        try {
            // Check
            if (!request.hasTransaction())
                throw new IllegalArgumentException("Transaction can't be null");

            // Map
            Transaction tx = Utils.convertToDomain(request.getTransaction());
            Utils.debug("Received Broadcast request: type=%s", tx.type());

            // Execute
            int seqNum = seqState.broadcast(tx);

            // Respond
            responseObserver.onNext(BroadcastResponse.newBuilder().setSequenceNumber(seqNum).build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }

    }

    /**
     * Handles transaction delivery requests from nodes.
     * Returns the transaction for the requested sequence number if available.
     *
     * @param request the delivery request containing the sequence number
     * @param responseObserver the observer to send the transaction back to the node
     */
    @Override
    public void deliverTransaction(DeliverTransactionRequest request,
            StreamObserver<DeliverTransactionResponse> responseObserver) {
        try {
            // Check
            if (request.getSequenceNumber() <= 0)
                throw new IllegalArgumentException("Sequence numbers start at 1!");

            // Execute
            Transaction tx = seqState.deliverTransaction(request.getSequenceNumber());

            // Map
            DeliverTransactionResponse.Builder builder = DeliverTransactionResponse.newBuilder();
            if (tx != null) {
                // Only map and set if it actually exists
                Utils.debug("Delivering Transaction: seqNum=%d", request.getSequenceNumber());
                builder.setTransaction(Utils.convertToProto(tx));
            }

            // Respond
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }

    }

}
